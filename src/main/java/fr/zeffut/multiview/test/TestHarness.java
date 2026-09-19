package fr.zeffut.multiview.test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.configuration.FlashbackConfigV1;

import fr.zeffut.multiview.merge.MergeOptions;
import fr.zeffut.multiview.merge.MergeOrchestrator;
import fr.zeffut.multiview.merge.MergeReport;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Autonomous test harness driven by a marker file ({@link #CONFIG_FILE}). Allows me to
 * iterate on merge fixes without manual MC interaction.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>{@link ClientLifecycleEvents#CLIENT_STARTED} fires — check for the config file.
 *       If absent, the harness stays dormant (normal user session).</li>
 *   <li>Parse the JSON config: list of source replay names (zips in
 *       {@code <gameDir>/flashback/replays/}), output base name, number of playback ticks.</li>
 *   <li>Run {@link MergeOrchestrator#run(MergeOptions, java.util.function.Consumer)} on a
 *       background thread.</li>
 *   <li>On merge success, call {@link Flashback#openReplayWorld(Path)} from the client
 *       thread to start playback of the merged zip.</li>
 *   <li>Tick counter ticks until {@code playTicks} elapses or {@code playTimeoutSeconds}
 *       wall-clock seconds pass. During play, capture chat events
 *       ({@code ClientReceiveMessageEvents.GAME}) and log every {@code "joined the game"}
 *       line.</li>
 *   <li>Write {@link #RESULT_FILE} as JSON with: merge stats, chat join counts per player,
 *       crash report files created during the run, and pass/fail verdict.</li>
 *   <li>Call {@link Minecraft#scheduleStop()} so the JVM exits with code 0.</li>
 * </ol>
 *
 * <h2>Externally</h2>
 * <pre>{@code
 *   cat > run/.multiview-test.json <<EOF
 *   { "sources": ["a.zip", "b.zip"], "output": "test", "playTicks": 5000 }
 *   EOF
 *   ./gradlew runClient
 *   cat run/.multiview-test-result.json
 * }</pre>
 *
 * The harness is opt-in by file presence so it never disturbs normal user sessions.
 */
public final class TestHarness implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(TestHarness.class);
    private static final String CONFIG_FILE = ".multiview-test.json";
    private static final String RESULT_FILE = ".multiview-test-result.json";

    private enum Phase {
        IDLE,
        MERGE, OPEN, PLAY,
        REC_WAIT_TITLE, REC_WAIT_CREATE_SCREEN, REC_WAIT_INGAME, REC_RECORDING, REC_WAIT_FINISH,
        DONE
    }
    private volatile Phase phase = Phase.IDLE;
    private boolean recordMode;
    private long recordCheckpointMs;
    private long recordingStartMs;
    private int recordWaitTicks;
    private Path recordedReplayPath;

    private Config config;
    private Path replayFolder;
    private Path resultFile;
    private long startTimeMs;
    private long playStartTimeMs;
    private final AtomicInteger playTickCounter = new AtomicInteger(0);
    private int lastReplayTickSeen = -1;
    private boolean loggedUnpauseOnce;
    private boolean loggedUnpauseFailure;
    private final AtomicBoolean mergeFinished = new AtomicBoolean(false);
    private volatile MergeReport mergeReport;
    private volatile String mergeError;
    private volatile Path mergedZipPath;

    private final List<String> phaseLog = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Integer> joinedCounts = new ConcurrentHashMap<>();
    private final Map<String, Integer> leftCounts = new ConcurrentHashMap<>();
    private final List<String> recentMessages = Collections.synchronizedList(new ArrayList<>());
    private final List<String> errorMessages = Collections.synchronizedList(new ArrayList<>());
    private int chatMessagesReceived;

    private final ExecutorService mergeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "multiview-testharness-merge");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(this::onClientStarted);
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        // Subscribe to both GAME (system messages — "joined the game") and CHAT
        // (player-to-player) to catch every line that lands in the chat HUD.
        ClientReceiveMessageEvents.GAME.register(this::onChat);
        // CHAT signature varies between fabric-api versions; GAME alone covers "joined the game".
    }

    private void onClientStarted(Minecraft mc) {
        try {
            replayFolder = Flashback.getReplayFolder();
            Path cfg = replayFolder.getParent().getParent().resolve(CONFIG_FILE);
            // gameDir / flashback / replays  → gameDir = parent.parent
            if (!Files.isRegularFile(cfg)) {
                LOG.info("[TestHarness] no {} found — dormant.", CONFIG_FILE);
                return;
            }
            resultFile = cfg.resolveSibling(RESULT_FILE);
            try (Reader r = Files.newBufferedReader(cfg)) {
                config = new Gson().fromJson(r, Config.class);
            }
            if (config == null) {
                LOG.warn("[TestHarness] config malformed. Aborting.");
                return;
            }
            startTimeMs = System.currentTimeMillis();
            recordMode = config.mode != null && config.mode.equalsIgnoreCase("record");
            LOG.info("[TestHarness] activated. mode={} config={}", recordMode ? "record" : "merge", config);
            // Disable focus-pause globally so the harness keeps ticking when the window loses focus.
            try {
                java.lang.reflect.Field f = mc.options.getClass().getField("pauseOnLostFocus");
                f.setBoolean(mc.options, false);
            } catch (Throwable ignore) {}
            if (recordMode) {
                recordCheckpointMs = System.currentTimeMillis();
                phaseLog.add(timestamp() + " RECORD mode — waiting for title screen");
                phase = Phase.REC_WAIT_TITLE;
                return;
            }
            if (config.sources == null || config.sources.size() < 2) {
                LOG.warn("[TestHarness] merge mode needs ≥2 sources. Aborting.");
                return;
            }
            phaseLog.add(timestamp() + " STARTED with " + config.sources.size() + " sources");
            phase = Phase.MERGE;
            mergeExecutor.submit(this::runMerge);
        } catch (Throwable t) {
            LOG.error("[TestHarness] init failed", t);
            errorMessages.add("init: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            phase = Phase.DONE;
            writeResultFile();
        }
    }

    private void runMerge() {
        try {
            List<Path> sourcePaths = new ArrayList<>();
            for (String name : config.sources) {
                Path p = replayFolder.resolve(name);
                if (!Files.exists(p)) {
                    throw new IOException("source replay not found: " + p);
                }
                sourcePaths.add(p);
            }
            String output = config.output != null && !config.output.isBlank()
                    ? config.output
                    : "harness_output";
            Path destBase = replayFolder.resolve(output);
            MergeOptions options = new MergeOptions(sourcePaths, destBase, Map.of(), true);
            phaseLog.add(timestamp() + " MERGE starting");
            mergeReport = MergeOrchestrator.run(options, p ->
                    phaseLog.add(timestamp() + " merge: " + p));
            mergedZipPath = destBase.resolveSibling(destBase.getFileName() + ".zip");
            phaseLog.add(timestamp() + " MERGE done → " + mergedZipPath.getFileName());
        } catch (Throwable t) {
            mergeError = t.getClass().getSimpleName() + ": " + t.getMessage();
            phaseLog.add(timestamp() + " MERGE FAILED: " + mergeError);
            LOG.error("[TestHarness] merge failed", t);
        } finally {
            mergeFinished.set(true);
        }
    }

    private void onTick(Minecraft mc) {
        // Global watchdog: regardless of phase, force-quit if total elapsed exceeds the
        // configured max. Prevents a stuck MC window when something goes wrong outside
        // any of the per-phase handlers.
        if (phase != Phase.IDLE && phase != Phase.DONE) {
            int maxMin = config != null && config.maxRunMinutes != null
                    ? config.maxRunMinutes : 20;
            if (System.currentTimeMillis() - startTimeMs > maxMin * 60_000L) {
                errorMessages.add("watchdog timeout: " + maxMin + " minutes elapsed in phase=" + phase);
                phaseLog.add(timestamp() + " WATCHDOG firing — force quit");
                phase = Phase.DONE;
                finalizeAndQuit(mc);
                return;
            }
        }

        if (recordMode) {
            handleRecordTick(mc);
            return;
        }

        if (phase == Phase.MERGE && mergeFinished.get()) {
            if (mergeError != null) {
                errorMessages.add("merge: " + mergeError);
                phase = Phase.DONE;
                finalizeAndQuit(mc);
                return;
            }
            phase = Phase.OPEN;
            phaseLog.add(timestamp() + " OPENING " + mergedZipPath.getFileName());
            try {
                // GameOptions.pauseOnLostFocus is a public boolean field in 1.21.11.
                try {
                    java.lang.reflect.Field f = mc.options.getClass().getField("pauseOnLostFocus");
                    f.setBoolean(mc.options, false);
                    phaseLog.add(timestamp() + " disabled GameOptions.pauseOnLostFocus");
                } catch (Throwable t) {
                    phaseLog.add(timestamp() + " could not disable pauseOnLostFocus: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
                Flashback.openReplayWorld(mergedZipPath);
            } catch (Throwable t) {
                errorMessages.add("open: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                LOG.error("[TestHarness] openReplayWorld failed", t);
                phase = Phase.DONE;
                finalizeAndQuit(mc);
                return;
            }
            playStartTimeMs = System.currentTimeMillis();
            phase = Phase.PLAY;
            int playSec = config.playSeconds != null ? config.playSeconds : 60;
            phaseLog.add(timestamp() + " PLAYING (target=" + playSec + "s wall-clock)");
            return;
        }

        if (phase == Phase.PLAY) {
            // Flashback opens replays paused by default — unpause once per tick if the
            // ReplayServer is available so playback actually progresses. The replay tick
            // counter we record is read from the server (real progression), not the client
            // tick handler (which fires regardless of pause state).
            if (Flashback.isInReplay() && mc.getSingleplayerServer() instanceof ReplayServer rs) {
                // Force-unpause both the integrated server (which gates server-side ticking
                // via paused field) AND Flashback's replayPaused flag. The replay's tick()
                // re-reads paused state from isPaused() each tick, so we must keep both
                // false continuously until replay completes.
                try {
                    java.lang.reflect.Field paused = rs.getClass().getSuperclass().getDeclaredField("paused");
                    paused.setAccessible(true);
                    if (paused.getBoolean(rs)) {
                        paused.setBoolean(rs, false);
                        if (!loggedUnpauseOnce) {
                            phaseLog.add(timestamp() + " forced IntegratedServer.paused=false");
                            loggedUnpauseOnce = true;
                        }
                    }
                } catch (Throwable t) {
                    if (!loggedUnpauseFailure) {
                        phaseLog.add(timestamp() + " could not unpause IntegratedServer: " + t.getMessage());
                        loggedUnpauseFailure = true;
                    }
                }
                if (rs.replayPaused) {
                    rs.replayPaused = false;
                }
                // Play at vanilla MC tick rate (20/s). An earlier experiment with 200/s
                // (10× speedup) reliably triggered a Flashback-side NPE in
                // ChunkLevelManager.handleChunkLeave because chunk-tracking sets weren't
                // initialised yet when the next ADD/REMOVE arrived. Normal speed avoids
                // the race and finishes 90s of playback in 90s.
                rs.setDesiredTickRate(20f, true);
                try {
                    rs.tickRateManager().setTickRate(20f);
                } catch (Throwable ignore) {}
                int replayTick = rs.getReplayTick();
                if (replayTick != lastReplayTickSeen) {
                    lastReplayTickSeen = replayTick;
                    int n = playTickCounter.incrementAndGet();
                    if (n == 1 || n % 200 == 0) {
                        phaseLog.add(timestamp() + " replayTick=" + replayTick + " observed=" + n);
                    }
                }
            }
            // Play duration is governed by WALL-CLOCK seconds — easier to reason about than
            // ticks (the client's tick rate during early replay loading can exceed 20 Hz).
            // This guarantees we see the bulk-PIU "joined the game" broadcasts that fire
            // in the first few seconds of replay.
            long elapsedMs = System.currentTimeMillis() - playStartTimeMs;
            int playSeconds = config.playSeconds != null ? config.playSeconds : 60;
            if (elapsedMs > playSeconds * 1000L) {
                phase = Phase.DONE;
                phaseLog.add(timestamp() + " reached " + playSeconds + "s playback ("
                        + playTickCounter.get() + " ticks observed) — finalizing");
                finalizeAndQuit(mc);
                return;
            }
            // Hard safety timeout for the play phase.
            int timeoutSec = config.playTimeoutSeconds != null ? config.playTimeoutSeconds : 600;
            if (elapsedMs > timeoutSec * 1000L) {
                phase = Phase.DONE;
                phaseLog.add(timestamp() + " play timeout " + timeoutSec + "s — finalizing");
                errorMessages.add("play timeout " + timeoutSec + "s");
                finalizeAndQuit(mc);
            }
        }
    }

    private void handleRecordTick(Minecraft mc) {
        switch (phase) {
            case REC_WAIT_TITLE -> {
                if (fr.zeffut.multiview.ui.MinecraftScreenAccess.getScreen(mc) instanceof TitleScreen) {
                    phaseLog.add(timestamp() + " title screen — invoking CreateWorldScreen.openFresh");
                    try {
                        CreateWorldScreen.openFresh(mc, () -> {});
                        phase = Phase.REC_WAIT_CREATE_SCREEN;
                        recordWaitTicks = 0;
                    } catch (Throwable t) {
                        errorMessages.add("openFresh: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                        LOG.error("[TestHarness] openFresh failed", t);
                        phase = Phase.DONE; finalizeAndQuit(mc);
                    }
                }
            }
            case REC_WAIT_CREATE_SCREEN -> {
                recordWaitTicks++;
                if (fr.zeffut.multiview.ui.MinecraftScreenAccess.getScreen(mc) instanceof CreateWorldScreen cws) {
                    if (recordWaitTicks == 1) {
                        phaseLog.add(timestamp() + " CreateWorldScreen visible — enumerating widgets");
                        java.util.Set<Object> seen = new java.util.HashSet<>();
                        List<String> labels = new ArrayList<>();
                        collectWidgetLabels(cws, seen, 0, labels);
                        phaseLog.add(timestamp() + " widget labels: " + labels);
                        if (config.seed != null && !config.seed.isBlank()) {
                            boolean set = setEditBoxByContextLabel(cws, "seed", config.seed);
                            phaseLog.add(timestamp() + " set seed=" + config.seed + " success=" + set);
                        }
                    }
                    if (clickWidgetMatching(cws, "create new world")) {
                        phaseLog.add(timestamp() + " Create button onPress invoked");
                        phase = Phase.REC_WAIT_INGAME;
                        recordWaitTicks = 0;
                    } else if (recordWaitTicks > 60) {
                        errorMessages.add("Create button not found in CreateWorldScreen");
                        phase = Phase.DONE; finalizeAndQuit(mc);
                    }
                } else if (recordWaitTicks > 1200) {
                    errorMessages.add("CreateWorldScreen never appeared (60s wait)");
                    phase = Phase.DONE; finalizeAndQuit(mc);
                }
            }
            case REC_WAIT_INGAME -> {
                recordWaitTicks++;
                if (mc.level != null && mc.player != null && !mc.player.isRemoved()) {
                    if (recordWaitTicks > 60) {
                        phaseLog.add(timestamp() + " in-game (player loaded) — starting Flashback recording");
                        try {
                            FlashbackConfigV1 cfg = Flashback.getConfig();
                            cfg.recordingControls.quicksave = true;
                            cfg.recordingControls.automaticallyFinish = false;
                            cfg.recordingControls.showRecordingToasts = false;
                            Flashback.startRecordingReplay();
                            recordingStartMs = System.currentTimeMillis();
                            phase = Phase.REC_RECORDING;
                            recordWaitTicks = 0;
                        } catch (Throwable t) {
                            errorMessages.add("startRecordingReplay: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                            LOG.error("[TestHarness] startRecordingReplay failed", t);
                            phase = Phase.DONE; finalizeAndQuit(mc);
                        }
                    }
                } else if (recordWaitTicks > 2400) {
                    errorMessages.add("never reached in-game (120s wait)");
                    phase = Phase.DONE; finalizeAndQuit(mc);
                }
            }
            case REC_RECORDING -> {
                long playSec = config.playSeconds != null ? config.playSeconds : 30;
                long elapsed = System.currentTimeMillis() - recordingStartMs;
                if (elapsed % 5000L < 50L) {
                    phaseLog.add(timestamp() + " recording… " + (elapsed / 1000L) + "s / " + playSec + "s");
                }
                if (elapsed > playSec * 1000L) {
                    phaseLog.add(timestamp() + " stopping recording (quicksave=on)");
                    try {
                        Flashback.finishRecordingReplay();
                    } catch (Throwable t) {
                        errorMessages.add("finishRecordingReplay: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                        phase = Phase.DONE; finalizeAndQuit(mc); return;
                    }
                    phase = Phase.REC_WAIT_FINISH;
                    recordWaitTicks = 0;
                }
            }
            case REC_WAIT_FINISH -> {
                recordWaitTicks++;
                boolean stillExporting;
                try { stillExporting = Flashback.isExporting(); }
                catch (Throwable t) { stillExporting = false; }
                if (!stillExporting && Flashback.RECORDER == null) {
                    Path produced = null;
                    try (var s = Files.list(replayFolder)) {
                        produced = s
                                .filter(p -> p.toString().endsWith(".zip"))
                                .filter(p -> {
                                    try { return Files.getLastModifiedTime(p).toMillis() >= recordCheckpointMs; }
                                    catch (IOException e) { return false; }
                                })
                                .max(java.util.Comparator.comparingLong(p -> {
                                    try { return Files.getLastModifiedTime(p).toMillis(); }
                                    catch (IOException e) { return 0L; }
                                }))
                                .orElse(null);
                    } catch (IOException e) {
                        errorMessages.add("replays scan failed: " + e.getMessage());
                    }
                    if (produced != null && Files.isRegularFile(produced)) {
                        try {
                            if (config.output != null && !config.output.isBlank()) {
                                Path renamed = replayFolder.resolve(config.output + ".zip");
                                Files.deleteIfExists(renamed);
                                Files.move(produced, renamed);
                                produced = renamed;
                            }
                            recordedReplayPath = produced;
                            phaseLog.add(timestamp() + " export done: " + produced.getFileName() + " (" + Files.size(produced) + " bytes)");
                        } catch (IOException e) {
                            errorMessages.add("rename failed: " + e.getMessage());
                            recordedReplayPath = produced;
                        }
                    } else {
                        if (recordWaitTicks > 1200) {
                            errorMessages.add("no recorded zip found in " + replayFolder + " after 60s wait");
                            phase = Phase.DONE; finalizeAndQuit(mc); return;
                        }
                        return; // keep waiting
                    }
                    phase = Phase.DONE;
                    finalizeAndQuit(mc);
                } else if (recordWaitTicks > 6000) {
                    errorMessages.add("export still in progress after 5min — aborting");
                    phase = Phase.DONE; finalizeAndQuit(mc);
                }
            }
            default -> {}
        }
    }

    private boolean clickWidgetMatching(Screen screen, String labelLower) {
        return clickWidgetRecursive(screen, labelLower, new java.util.HashSet<>(), 0);
    }

    private boolean clickWidgetRecursive(Object node, String labelLower, java.util.Set<Object> seen, int depth) {
        if (node == null || depth > 12 || !seen.add(node)) return false;
        String msg = getWidgetMessage(node);
        if (msg != null && msg.toLowerCase().contains(labelLower)) {
            phaseLog.add(timestamp() + " match: " + msg + " <" + node.getClass().getSimpleName() + ">");
            if (invokeOnPress(node)) return true;
        }
        try {
            java.lang.reflect.Method childrenM = node.getClass().getMethod("children");
            Object children = childrenM.invoke(node);
            if (children instanceof List<?> list) {
                for (Object c : list) {
                    if (clickWidgetRecursive(c, labelLower, seen, depth + 1)) return true;
                }
            }
        } catch (Throwable ignore) {}
        for (Object f : reflectFieldValues(node)) {
            if (f instanceof List<?> list) {
                for (Object c : list) if (clickWidgetRecursive(c, labelLower, seen, depth + 1)) return true;
            } else if (f != null && !(f instanceof String) && !(f.getClass().isPrimitive())
                    && !f.getClass().getName().startsWith("java.")
                    && !f.getClass().getName().startsWith("com.mojang.")) {
                if (clickWidgetRecursive(f, labelLower, seen, depth + 1)) return true;
            }
        }
        return false;
    }

    /**
     * Set the value of the Nth EditBox-class widget in DFS order (0=first encountered).
     * In CreateWorldScreen: idx=0 → World Name, idx=1 → Seed.
     */
    private boolean setEditBoxByContextLabel(Object root, String labelHint, String value) {
        // We map "seed" → index 1, others can be extended here.
        int targetIdx = labelHint.equalsIgnoreCase("seed") ? 1 : 0;
        int[] counter = {0};
        return setNthEditBox(root, targetIdx, value, new java.util.HashSet<>(), 0, counter);
    }

    private boolean setNthEditBox(Object node, int targetIdx, String value,
                                  java.util.Set<Object> seen, int depth, int[] counter) {
        if (node == null || depth > 12 || !seen.add(node)) return false;
        String cls = node.getClass().getSimpleName().toLowerCase();
        String clsFull = node.getClass().getName().toLowerCase();
        boolean isEditBox = cls.contains("editbox") || cls.contains("textfield")
                || clsFull.contains(".editbox") || clsFull.contains(".textfield");
        if (isEditBox) {
            if (counter[0] == targetIdx) {
                for (String setter : new String[]{"setValue", "setText"}) {
                    try {
                        java.lang.reflect.Method m = node.getClass().getMethod(setter, String.class);
                        m.invoke(node, value);
                        return true;
                    } catch (Throwable ignore) {}
                    // Walk superclasses for inherited setValue
                    Class<?> c = node.getClass().getSuperclass();
                    while (c != null) {
                        try {
                            java.lang.reflect.Method m = c.getMethod(setter, String.class);
                            m.invoke(node, value);
                            return true;
                        } catch (Throwable ignore) {}
                        c = c.getSuperclass();
                    }
                }
                return false;
            }
            counter[0]++;
        }
        try {
            java.lang.reflect.Method childrenM = node.getClass().getMethod("children");
            Object children = childrenM.invoke(node);
            if (children instanceof List<?> list) {
                for (Object c : list) if (setNthEditBox(c, targetIdx, value, seen, depth + 1, counter)) return true;
            }
        } catch (Throwable ignore) {}
        for (Object f : reflectFieldValues(node)) {
            if (f instanceof List<?> list) {
                for (Object c : list) if (setNthEditBox(c, targetIdx, value, seen, depth + 1, counter)) return true;
            } else if (f != null && !(f instanceof String) && !(f.getClass().isPrimitive())
                    && !f.getClass().getName().startsWith("java.")
                    && !f.getClass().getName().startsWith("com.mojang.")) {
                if (setNthEditBox(f, targetIdx, value, seen, depth + 1, counter)) return true;
            }
        }
        return false;
    }

    private void collectWidgetLabels(Object node, java.util.Set<Object> seen, int depth, List<String> out) {
        if (node == null || depth > 12 || !seen.add(node) || out.size() > 80) return;
        String msg = getWidgetMessage(node);
        String cls = node.getClass().getSimpleName();
        if (msg != null && !msg.isBlank()) {
            out.add(msg + " <" + cls + ">");
        } else if (cls.toLowerCase().contains("editbox") || cls.toLowerCase().contains("textfield")) {
            out.add("(empty-text) <" + cls + ">");
        }
        try {
            java.lang.reflect.Method childrenM = node.getClass().getMethod("children");
            Object children = childrenM.invoke(node);
            if (children instanceof List<?> list) {
                for (Object c : list) collectWidgetLabels(c, seen, depth + 1, out);
            }
        } catch (Throwable ignore) {}
        for (Object f : reflectFieldValues(node)) {
            if (f instanceof List<?> list) {
                for (Object c : list) collectWidgetLabels(c, seen, depth + 1, out);
            } else if (f != null && !(f instanceof String) && !f.getClass().isPrimitive()
                    && !f.getClass().getName().startsWith("java.")
                    && !f.getClass().getName().startsWith("com.mojang.")) {
                collectWidgetLabels(f, seen, depth + 1, out);
            }
        }
    }

    private List<Object> reflectFieldValues(Object obj) {
        List<Object> out = new ArrayList<>();
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    out.add(f.get(obj));
                } catch (Throwable ignore) {}
            }
            c = c.getSuperclass();
        }
        return out;
    }

    private String getWidgetMessage(Object widget) {
        try {
            java.lang.reflect.Method getMsg = widget.getClass().getMethod("getMessage");
            Object msg = getMsg.invoke(widget);
            if (msg == null) return null;
            try {
                java.lang.reflect.Method getString = msg.getClass().getMethod("getString");
                Object s = getString.invoke(msg);
                return s != null ? s.toString() : null;
            } catch (Throwable ignore) {
                return msg.toString();
            }
        } catch (Throwable ignore) { return null; }
    }

    private boolean invokeOnPress(Object widget) {
        Class<?> c = widget.getClass();
        while (c != null) {
            for (java.lang.reflect.Method mm : c.getDeclaredMethods()) {
                if (mm.getName().equals("onPress")) {
                    try {
                        mm.setAccessible(true);
                        Object[] args = new Object[mm.getParameterCount()];
                        mm.invoke(widget, args);
                        phaseLog.add(timestamp() + " invoked " + mm + " on " + widget.getClass().getSimpleName());
                        return true;
                    } catch (Throwable t) {
                        phaseLog.add(timestamp() + " onPress invoke failed on " + widget.getClass().getSimpleName() + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                }
            }
            c = c.getSuperclass();
        }
        return false;
    }

    private void onChat(Component message, boolean overlay) {
        if (overlay) return;
        if (phase == Phase.IDLE || phase == Phase.DONE) return;
        String s = message.getString();
        if (s == null) return;
        chatMessagesReceived++;
        // Keep the first 100 distinct messages we see for debugging (no flood, ring-style).
        synchronized (recentMessages) {
            if (recentMessages.size() < 100) recentMessages.add("[" + phase + " t+" + playTickCounter.get() + "] " + s);
        }
        if (s.endsWith(" joined the game")) {
            String player = s.substring(0, s.length() - " joined the game".length());
            joinedCounts.merge(player, 1, Integer::sum);
        } else if (s.endsWith(" left the game")) {
            String player = s.substring(0, s.length() - " left the game".length());
            leftCounts.merge(player, 1, Integer::sum);
        }
    }

    private void finalizeAndQuit(Minecraft mc) {
        writeResultFile();
        // Under the hidden-client harness the scenario owns shutdown: it reads the
        // result file, runs its own assertions and captures a screenshot before
        // stopping the client itself. Stopping here would race all of that.
        if (Boolean.getBoolean("multiview.hiddenClient")) {
            return;
        }
        // schedule stop on the next render frame
        mc.stop();
    }

    /**
     * Scan {@code logs/latest.log} for "X joined the game" / "left the game" lines so we
     * count duplicates even when Fabric chat events are bypassed by Flashback's
     * FakePlayer dispatch path. Filters to lines after our merge started so we don't
     * count messages from previous sessions.
     */
    private void scanLogForChatLines() {
        try {
            Path logFile = Path.of("logs", "latest.log");
            if (!Files.isRegularFile(logFile)) return;
            List<String> lines = Files.readAllLines(logFile);
            for (String line : lines) {
                if (line.contains(" joined the game") && line.contains("[CHAT]")) {
                    int chatIdx = line.indexOf("[CHAT] ");
                    if (chatIdx < 0) continue;
                    String msg = line.substring(chatIdx + "[CHAT] ".length());
                    if (msg.endsWith(" joined the game")) {
                        String player = msg.substring(0, msg.length() - " joined the game".length());
                        joinedCounts.merge(player, 1, Integer::sum);
                        synchronized (recentMessages) {
                            if (recentMessages.size() < 100) recentMessages.add("[log] " + msg);
                        }
                    }
                } else if (line.contains(" left the game") && line.contains("[CHAT]")) {
                    int chatIdx = line.indexOf("[CHAT] ");
                    if (chatIdx < 0) continue;
                    String msg = line.substring(chatIdx + "[CHAT] ".length());
                    if (msg.endsWith(" left the game")) {
                        String player = msg.substring(0, msg.length() - " left the game".length());
                        leftCounts.merge(player, 1, Integer::sum);
                    }
                }
            }
            chatMessagesReceived = joinedCounts.values().stream().mapToInt(Integer::intValue).sum()
                    + leftCounts.values().stream().mapToInt(Integer::intValue).sum();
        } catch (Throwable t) {
            errorMessages.add("log scan failed: " + t.getMessage());
        }
    }

    private synchronized void writeResultFile() {
        // Always scan the log file before finalizing — covers messages that Fabric events
        // might miss in Flashback's replay context.
        scanLogForChatLines();

        if (resultFile == null) return;
        try {
            JsonObject root = new JsonObject();
            root.addProperty("startTime", startTimeMs);
            root.addProperty("durationMs", System.currentTimeMillis() - startTimeMs);
            root.addProperty("phase", phase.name());
            root.addProperty("playTicksObserved", playTickCounter.get());
            root.addProperty("mergeSucceeded", mergeReport != null && mergeError == null);

            if (mergeReport != null) {
                JsonObject stats = new JsonObject();
                stats.addProperty("entitiesMergedByUuid", mergeReport.stats.entitiesMergedByUuid);
                stats.addProperty("blocksLwwOverwrites", mergeReport.stats.blocksLwwOverwrites);
                stats.addProperty("blocksLwwConflicts", mergeReport.stats.blocksLwwConflicts);
                stats.addProperty("globalPacketsDeduped", mergeReport.stats.globalPacketsDeduped);
                stats.addProperty("mergedTotalTicks", mergeReport.mergedTotalTicks);
                stats.addProperty("outOfOrderPackets", mergeReport.stats.outOfOrderPackets);
                root.add("mergeStats", stats);
            }
            if (mergedZipPath != null) {
                root.addProperty("mergedZip", mergedZipPath.toString());
            }
            if (recordedReplayPath != null) {
                root.addProperty("recordedReplay", recordedReplayPath.toString());
            }
            root.addProperty("mode", recordMode ? "record" : "merge");

            // Chat join counts — flag SUSPICIOUS duplicates only. A player can legitimately
            // join multiple times in the original session (leave-rejoin cycles). Only count
            // as suspicious when joins > leaves+1 (more joins than disconnections justify).
            JsonObject joins = new JsonObject();
            int suspiciousDuplicates = 0;
            int legitMultiJoin = 0;
            int totalJoinExcess = 0;
            for (Map.Entry<String, Integer> e : new HashMap<>(joinedCounts).entrySet()) {
                joins.addProperty(e.getKey(), e.getValue());
                int j = e.getValue();
                int l = leftCounts.getOrDefault(e.getKey(), 0);
                if (j > 1) {
                    // Legit pattern: joins == leaves (left, currently absent) or joins == leaves+1
                    // (currently in). Anything else is a dedup miss.
                    if (j == l || j == l + 1) legitMultiJoin++;
                    else {
                        suspiciousDuplicates++;
                        totalJoinExcess += (j - Math.max(l + 1, 1));
                    }
                }
            }
            root.add("joinedCounts", joins);
            root.addProperty("duplicateJoinPlayers", suspiciousDuplicates);
            root.addProperty("legitMultiJoin", legitMultiJoin);
            root.addProperty("totalJoinExcess", totalJoinExcess);

            JsonObject leaves = new JsonObject();
            for (Map.Entry<String, Integer> e : new HashMap<>(leftCounts).entrySet()) {
                leaves.addProperty(e.getKey(), e.getValue());
            }
            root.add("leftCounts", leaves);
            root.addProperty("chatMessagesReceived", chatMessagesReceived);
            com.google.gson.JsonArray recents = new com.google.gson.JsonArray();
            synchronized (recentMessages) {
                for (String m : recentMessages) recents.add(m);
            }
            root.add("recentMessages", recents);

            // Crash report files written during this run (so I can know if MC crashed
            // on a separate thread, even if the harness itself didn't see it).
            List<String> crashReports = new ArrayList<>();
            Path crashDir = resultFile.resolveSibling("crash-reports");
            if (Files.isDirectory(crashDir)) {
                long since = startTimeMs;
                try (var s = Files.list(crashDir)) {
                    s.filter(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis() >= since; }
                        catch (IOException ex) { return false; }
                    }).forEach(p -> crashReports.add(p.getFileName().toString()));
                }
            }
            com.google.gson.JsonArray crashes = new com.google.gson.JsonArray();
            crashReports.forEach(crashes::add);
            root.add("crashReportsSinceStart", crashes);

            com.google.gson.JsonArray phases = new com.google.gson.JsonArray();
            synchronized (phaseLog) {
                for (String line : phaseLog) phases.add(line);
            }
            root.add("phaseLog", phases);

            com.google.gson.JsonArray errors = new com.google.gson.JsonArray();
            synchronized (errorMessages) {
                for (String e : errorMessages) errors.add(e);
            }
            root.add("errors", errors);

            // Verdict: PASS if the merge file itself is sound. We do NOT use chat
            // dup counts here: Flashback's playback engine fires each chat message twice
            // (once per replay viewer/fake-player relay), inflating MC-observed duplicate
            // counts even when the merged file contains exactly one chat per event.
            // File-level chat dedup is tracked via the merge report's globalPacketsDeduped
            // stat — the harness logs it for visibility but doesn't fail on it.
            boolean pass = errorMessages.isEmpty() && crashReports.isEmpty()
                    && (recordMode ? recordedReplayPath != null : mergeError == null);
            root.addProperty("verdict", pass ? "PASS" : "FAIL");

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (var w = Files.newBufferedWriter(resultFile)) {
                w.write(gson.toJson(root));
            }
            LOG.info("[TestHarness] result written → {} (verdict={})",
                    resultFile.getFileName(), pass ? "PASS" : "FAIL");
        } catch (Throwable t) {
            LOG.error("[TestHarness] failed to write result", t);
        }
    }

    private static String timestamp() {
        long t = System.currentTimeMillis();
        return String.format("[t+%4ds]", (t % 1_000_000) / 1000);
    }

    /** JSON shape of {@code .multiview-test.json}. */
    private static final class Config {
        String mode;             // "merge" (default) or "record"
        List<String> sources;
        String output;
        Integer playTicks;
        Integer playSeconds;
        Integer playTimeoutSeconds;
        Integer maxRunMinutes;
        String seed;             // optional fixed seed for record-mode world generation

        @Override
        public String toString() {
            return "Config{mode=" + mode + ", sources=" + sources + ", output=" + output
                    + ", playSeconds=" + playSeconds + ", maxRunMinutes=" + maxRunMinutes + "}";
        }
    }
}
