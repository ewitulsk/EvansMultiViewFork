package fr.zeffut.multiview.testing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.screen.select_replay.SelectReplayScreen;
import fr.zeffut.multiview.ui.MinecraftScreenAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scripted hidden-client scenario driver. Enabled with -Dmultiview.hiddenClient=true and a
 * -Dmultiview.clientScenario=<name> selection. Runs entirely in-process: no desktop
 * mouse/keyboard automation. Validates the hidden-window contract every rendered frame and
 * captures framebuffer evidence to the run directory before stopping the client.
 *
 * <h2>Scenarios</h2>
 * <ul>
 *   <li>{@code smoke} — reach the title screen, render frames, capture a screenshot.</li>
 *   <li>{@code merge} — the {@link fr.zeffut.multiview.test.TestHarness} performs the merge
 *       (its config file is written by the runner before launch); this scenario waits for
 *       {@code .multiview-test-result.json}, asserts the PASS verdict and merge stats.</li>
 *   <li>{@code mergeplay} / {@code merge8} — same, plus during playback asserts a secondary
 *       (non-local) player entity exists in the client level and captures in-playback
 *       framebuffer evidence.</li>
 *   <li>{@code ui} — opens {@link SelectReplayScreen}, asserts MultiView's merge button is
 *       injected, drives row selection through the real MouseHandler path, and asserts the
 *       button enable/disable chain.</li>
 * </ul>
 */
public final class HiddenClientScenario {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static int ticks, readyTicks;
    private static int disconnectedTicks;
    private static boolean finished;
    private static boolean screenshotRequested;
    private static final AtomicReference<NativeImage> pendingScreenshot = new AtomicReference<>();

    // merge/mergeplay/merge8 state
    private static boolean replayEntityFound;
    private static int replayActiveTicks;
    private static int resultGraceTicks;
    private static boolean cameraLifted;
    private static int postResultTicks;
    private static int screenshotRetries;

    // ui state
    private static boolean uiOpened;
    private static int uiOpenedTick;
    private static int uiPhase;
    private static int uiPhaseTicks;
    private static Button mergeButton;
    private static int uiClicksIssued;

    private HiddenClientScenario() {}

    public static boolean enabled() {
        return Boolean.getBoolean("multiview.hiddenClient");
    }

    private static String scenario() {
        return System.getProperty("multiview.clientScenario", "smoke");
    }

    public static void tick() {
        if (!enabled() || finished) {
            return;
        }
        var minecraft = Minecraft.getInstance();
        disconnectedTicks = MinecraftScreenAccess.getScreen(minecraft) instanceof DisconnectedScreen
                ? disconnectedTicks + 1 : 0;
        if (disconnectedTicks > 40) {
            throw new IllegalStateException("Hidden test client remained disconnected; see the connection error in its log");
        }
        ticks++;
        String scenario = scenario();
        int limit = switch (scenario) {
            case "merge", "mergeplay" -> 36000;  // merge runs off-thread; 30min at 20Hz
            case "merge8" -> 72000;
            default -> 4800;
        };
        if (ticks > limit) {
            throw new IllegalStateException("Hidden client scenario timed out after " + limit + " ticks");
        }
        if (MinecraftScreenAccess.getScreen(minecraft) instanceof TitleScreen
                && minecraft.gui.overlay() == null) {
            readyTicks++;
        }

        var image = pendingScreenshot.getAndSet(null);
        if (image != null) {
            finishWithEvidence(minecraft, image, scenario);
            return;
        }
        if (screenshotRequested) {
            return;
        }
        switch (scenario) {
            case "smoke" -> {
                if (readyTicks >= 40) {
                    requestScreenshot(minecraft);
                }
            }
            case "merge" -> runMergeScenario(minecraft, false);
            case "mergeplay", "merge8" -> runMergeScenario(minecraft, true);
            case "ui" -> runUiScenario(minecraft);
            default -> throw new IllegalStateException("Unknown client scenario " + scenario);
        }
    }

    // ------------------------------------------------------------------ merge family

    /**
     * The TestHarness (fed by {@code .multiview-test.json} in the game dir) performs the
     * merge, opens the merged replay and plays it, then writes
     * {@code .multiview-test-result.json} and stops the client. While playback is live we
     * additionally verify — for mergeplay/merge8 — that a synthesized secondary player
     * exists as an entity in the client level, which is the whole point of MultiView.
     */
    private static void runMergeScenario(Minecraft minecraft, boolean requireRemotePlayer) {
        // Playback-side assertions while the harness drives the replay.
        if (requireRemotePlayer && !replayEntityFound && Flashback.isInReplay() && minecraft.level != null) {
            var players = minecraft.level.players();
            var local = minecraft.player;
            boolean remote = players.stream().anyMatch(p -> local == null || !p.getUUID().equals(local.getUUID()));
            if (remote) {
                replayEntityFound = true;
                LOGGER.info("HIDDEN_MERGEPLAY_ENTITY players={} local={}", players.size(),
                        local == null ? "none" : local.getUUID());
            } else {
                replayActiveTicks++;
            }
            // Fall through when the result file already exists so we can finish/fail;
            // otherwise keep polling for the secondary player.
            if (!Files.isRegularFile(minecraft.gameDirectory.toPath().resolve(".multiview-test-result.json"))) {
                return;
            }
            if (resultGraceTicks++ < 200) {
                return; // short grace — entities may still be streaming in
            }
        }

        Path resultFile = minecraft.gameDirectory.toPath().resolve(".multiview-test-result.json");
        if (!Files.isRegularFile(resultFile)) {
            return;
        }
        JsonObject result;
        try (Reader r = Files.newBufferedReader(resultFile)) {
            result = JsonParser.parseReader(r).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse TestHarness result file", e);
        }
        String verdict = result.has("verdict") ? result.get("verdict").getAsString() : "MISSING";
        boolean mergeSucceeded = result.has("mergeSucceeded") && result.get("mergeSucceeded").getAsBoolean();
        long uuidMerges = 0;
        if (result.has("mergeStats") && result.getAsJsonObject("mergeStats").has("entitiesMergedByUuid")) {
            uuidMerges = result.getAsJsonObject("mergeStats").get("entitiesMergedByUuid").getAsLong();
        }
        LOGGER.info("HIDDEN_MERGE_RESULT verdict={} mergeSucceeded={} entitiesMergedByUuid={} playTicks={}",
                verdict, mergeSucceeded, uuidMerges,
                result.has("playTicksObserved") ? result.get("playTicksObserved").getAsInt() : -1);
        if (!"PASS".equals(verdict) || !mergeSucceeded) {
            throw new IllegalStateException("TestHarness reported verdict=" + verdict
                    + " mergeSucceeded=" + mergeSucceeded + " errors=" + result.get("errors"));
        }
        if (requireRemotePlayer) {
            if (!replayEntityFound) {
                throw new IllegalStateException("No secondary player entity observed in client level during playback");
            }
            if (uuidMerges <= 0) {
                throw new IllegalStateException("entitiesMergedByUuid == 0 — no cross-source entity merging happened");
            }
        }

        // Verdict passed — capture the world as evidence. Replay spectators frequently
        // sit inside terrain (uniform dark frame), so lift the camera above ground and
        // pitch it down before shooting; low-variation shots retry a few times.
        if (!cameraLifted) {
            var player = minecraft.player;
            if (player != null && minecraft.level != null) {
                player.setPos(player.getX(), player.getY() + 80.0, player.getZ());
                player.setXRot(70.0f);
                LOGGER.info("HIDDEN_MERGE_CAMERA lifted spectator for evidence shot");
            }
            cameraLifted = true;
            postResultTicks = 0;
            return;
        }
        if (postResultTicks++ < 40) {
            return; // let chunks stream in and the world render the new viewpoint
        }
        if (!screenshotRequested) {
            requestScreenshot(minecraft);
        }
    }

    // ------------------------------------------------------------------ ui

    /**
     * Opens {@link SelectReplayScreen} on top of the title screen, waits for the replay
     * list to populate, then exercises MultiView's injected merge button: it must exist
     * and start inactive; clicking two replay rows through the real MouseHandler →
     * ScreenMouseEvents → row-click chain must activate it (the fixture zips overlap).
     */
    private static void runUiScenario(Minecraft minecraft) {
        switch (uiPhase) {
            case 0 -> {
                if (readyTicks < 20) {
                    return;
                }
                LOGGER.info("HIDDEN_UI_OPEN SelectReplayScreen");
                MinecraftScreenAccess.setScreen(minecraft,
                        new SelectReplayScreen(new TitleScreen()));
                uiOpened = true;
                uiOpenedTick = ticks;
                uiPhase = 1;
            }
            case 1 -> {
                // Wait for AFTER_INIT to inject the button and the list to populate.
                var screen = MinecraftScreenAccess.getScreen(minecraft);
                if (!(screen instanceof SelectReplayScreen srs)) {
                    if (ticks - uiOpenedTick > 400) {
                        throw new IllegalStateException("SelectReplayScreen never became the active screen");
                    }
                    return;
                }
                if (mergeButton == null) {
                    mergeButton = findMergeButton(srs);
                    if (mergeButton == null) {
                        if (ticks - uiOpenedTick > 400) {
                            throw new IllegalStateException("MultiView merge button was not injected into SelectReplayScreen");
                        }
                        return;
                    }
                    LOGGER.info("HIDDEN_UI_BUTTON found active={}", mergeButton.active);
                    if (mergeButton.active) {
                        throw new IllegalStateException("Merge button active before any selection");
                    }
                }
                if (replayListSize(srs) >= 2) {
                    LOGGER.info("HIDDEN_UI_ROWS count={}", replayListSize(srs));
                    uiPhase = 2;
                    uiPhaseTicks = 0;
                } else if (ticks - uiOpenedTick > 1200) {
                    throw new IllegalStateException("Replay list never populated (need >=2 replays in flashback/replays)");
                }
            }
            case 2 -> {
                // Click every replay row, one click per few ticks, through the real
                // MouseHandler entry points.
                int rows = replayListSize((SelectReplayScreen) MinecraftScreenAccess.getScreen(minecraft));
                if (uiClicksIssued < rows && uiPhaseTicks++ > 10) {
                    clickRow(minecraft, (SelectReplayScreen) MinecraftScreenAccess.getScreen(minecraft),
                            uiClicksIssued);
                    uiClicksIssued++;
                    uiPhaseTicks = 0;
                }
                if (uiClicksIssued >= rows) {
                    uiPhase = 3;
                    uiPhaseTicks = 0;
                }
            }
            case 3 -> {
                // All rows selected → merge button must become active. A single
                // overlap-connected selection carries no tooltip warning; a disjoint
                // set still activates but shows the "separate groups" warning.
                if (uiPhaseTicks == 30) {
                    // Diagnostic: did native selection see the clicks at all?
                    try {
                        var field = SelectReplayScreen.class.getDeclaredField("list");
                        field.setAccessible(true);
                        Object list = field.get(MinecraftScreenAccess.getScreen(minecraft));
                        Object sel = list.getClass().getMethod("getSelected").invoke(list);
                        LOGGER.info("HIDDEN_UI_DIAG nativeSelected={}", sel == null ? "null" : sel.getClass().getSimpleName());
                    } catch (ReflectiveOperationException e) {
                        LOGGER.info("HIDDEN_UI_DIAG getSelected failed: {}", e.getMessage());
                    }
                }
                if (uiPhaseTicks++ > 60) {
                    boolean active = mergeButton.active;
                    // AbstractWidget.tooltip is a private WidgetTooltipHolder in 26.3 —
                    // reflect to check whether the disjoint-groups warning was set.
                    boolean warns = false;
                    try {
                        var tf = net.minecraft.client.gui.components.AbstractWidget.class.getDeclaredField("tooltip");
                        tf.setAccessible(true);
                        Object holder = tf.get(mergeButton);
                        warns = holder.getClass().getMethod("get").invoke(holder) != null;
                    } catch (ReflectiveOperationException e) {
                        LOGGER.info("HIDDEN_UI_DIAG tooltip read failed: {}", e.getMessage());
                    }
                    LOGGER.info("HIDDEN_UI_SELECTED clicks={} buttonActive={} warnTooltip={} message='{}'",
                            uiClicksIssued, active, warns, mergeButton.getMessage().getString());
                    if (!active) {
                        throw new IllegalStateException("Merge button still inactive after selecting replays (message='"
                                + mergeButton.getMessage().getString() + "')");
                    }
                    if (warns) {
                        throw new IllegalStateException("Merge button shows disjoint-groups warning "
                                + "for a selection that should form one overlap component");
                    }
                    LOGGER.info("HIDDEN_UI_PASS button injected, selection chain activates merge");
                    requestScreenshot(minecraft);
                    uiPhase = 4;
                }
            }
            default -> { /* done — waiting for screenshot */ }
        }
    }

    private static Button findMergeButton(SelectReplayScreen screen) {
        for (var child : screen.children()) {
            if (child instanceof Button b && isMergeButton(b)) {
                return b;
            }
        }
        return null;
    }

    private static boolean isMergeButton(Button b) {
        Component msg = b.getMessage();
        return msg.getContents() instanceof TranslatableContents t
                && t.getKey().startsWith("multiview.button.merge_selected");
    }

    private static int replayListSize(SelectReplayScreen screen) {
        try {
            var field = SelectReplayScreen.class.getDeclaredField("list");
            field.setAccessible(true);
            Object list = field.get(screen);
            var children = (java.util.List<?>) list.getClass().getMethod("children").invoke(list);
            int rows = 0;
            for (Object c : children) {
                if (c != null && c.getClass().getSimpleName().equals("ReplayListEntry")) {
                    rows++;
                }
            }
            return rows;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not inspect ReplaySelectionList", e);
        }
    }

    /**
     * Click a replay row through the real MouseHandler entry points: onMove positions the
     * cursor (raw pixels — the handler scales to GUI coords) and onButton dispatches the
     * press/release through ScreenMouseEvents → MultiView's row-click handler.
     */
    private static void clickRow(Minecraft minecraft, SelectReplayScreen screen, int replayOrdinal) {
        try {
            var field = SelectReplayScreen.class.getDeclaredField("list");
            field.setAccessible(true);
            Object list = field.get(screen);
            Class<?> listClass = list.getClass();
            var children = (java.util.List<?>) listClass.getMethod("children").invoke(list);
            // children() mixes ReplayListEntry with folder/navigation rows — resolve the
            // list index of the Nth real replay row.
            int rowIndex = -1;
            int seen = 0;
            for (int i = 0; i < children.size(); i++) {
                Object c = children.get(i);
                if (c != null && c.getClass().getSimpleName().equals("ReplayListEntry") && seen++ == replayOrdinal) {
                    rowIndex = i;
                    break;
                }
            }
            if (rowIndex < 0) {
                throw new IllegalStateException("No ReplayListEntry #" + replayOrdinal + " among " + children.size() + " rows");
            }
            int rowTop = (int) listClass.getMethod("getRowTop", int.class).invoke(list, rowIndex);
            int rowBottom = (int) listClass.getMethod("getRowBottom", int.class).invoke(list, rowIndex);
            int rowLeft = (int) listClass.getMethod("getRowLeft").invoke(list);
            int rowWidth = (int) listClass.getMethod("getRowWidth").invoke(list);

            double scaledX = rowLeft + rowWidth / 2.0;
            double scaledY = (rowTop + rowBottom) / 2.0;
            var window = minecraft.getWindow();
            // MouseHandler.onMove takes raw pixels; onButton scales pos→GUI coords itself.
            double rawX = scaledX * window.getScreenWidth() / window.getGuiScaledWidth();
            double rawY = scaledY * window.getScreenHeight() / window.getGuiScaledHeight();
            long handle = window.handle();
            minecraft.mouseHandler.onMove(handle, rawX, rawY, 0.0, 0.0);
            minecraft.mouseHandler.onButton(handle, new MouseButtonInfo(SDLMouse.SDL_BUTTON_LEFT, 0), 1);
            minecraft.mouseHandler.onButton(handle, new MouseButtonInfo(SDLMouse.SDL_BUTTON_LEFT, 0), 0);
            LOGGER.info("HIDDEN_UI_CLICK row={} scaled=({},{}) raw=({},{})", rowIndex, scaledX, scaledY, rawX, rawY);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Row click failed", e);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static void requestScreenshot(Minecraft minecraft) {
        screenshotRequested = true;
        Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), pendingScreenshot::set);
    }

    private static void finishWithEvidence(Minecraft minecraft, NativeImage image, String scenario) {
        try (image) {
            var colors = new HashSet<Integer>();
            for (int x = 0; x < image.getWidth(); x += 8) {
                for (int y = 0; y < image.getHeight(); y += 8) {
                    colors.add(image.getPixel(x, y));
                }
            }
            if (colors.size() < 32) {
                if (screenshotRetries++ < 3) {
                    LOGGER.warn("Framebuffer variation {} too low — retrying evidence shot", colors.size());
                    screenshotRequested = false;
                    postResultTicks = 0;
                    cameraLifted = false; // lift again (further, in case we stayed buried)
                    return;
                }
                throw new IllegalStateException("Insufficient rendered framebuffer variation: " + colors.size());
            }
            image.writeToFile(minecraft.gameDirectory.toPath().resolve("hidden-" + scenario + ".png").toFile());
            LOGGER.info("HIDDEN_CLIENT_PASS scenario={} readyTicks={} framebuffer={}x{} colors={}",
                    scenario, readyTicks, image.getWidth(), image.getHeight(), colors.size());
        } catch (IOException exception) {
            throw new IllegalStateException("Screenshot write failed", exception);
        }
        finished = true;
        minecraft.stop();
    }

    public static void frame() {
        if (!enabled() || finished) {
            return;
        }
        var minecraft = Minecraft.getInstance();
        long flags = SDLVideo.SDL_GetWindowFlags(minecraft.getWindow().handle());
        if ((flags & SDLVideo.SDL_WINDOW_HIDDEN) == 0
            || (flags & (SDLVideo.SDL_WINDOW_INPUT_FOCUS | SDLVideo.SDL_WINDOW_MOUSE_FOCUS)) != 0) {
            throw new IllegalStateException("Hidden client visibility/focus contract violated");
        }
    }
}
