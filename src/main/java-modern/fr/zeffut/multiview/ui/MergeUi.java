package fr.zeffut.multiview.ui;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.screen.ReplaySummary;
import com.moulberry.flashback.screen.select_replay.ReplaySelectionEntry;
import com.moulberry.flashback.screen.select_replay.ReplaySelectionList;
import com.moulberry.flashback.screen.select_replay.SelectReplayScreen;
import fr.zeffut.multiview.MultiViewMod;
import fr.zeffut.multiview.merge.MergeOptions;
import fr.zeffut.multiview.merge.MergeOrchestrator;
import fr.zeffut.multiview.merge.OverlapValidator;
import fr.zeffut.multiview.merge.PacketIdProvider;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Merge-replay UI for Minecraft 26.1+.
 * <p>
 * Mirrors the 1.21.x implementation in {@code src/main/java-classic} but targets the
 * post-26.1 rendering pipeline:
 * <ul>
 *   <li>{@code GuiGraphics} → {@code GuiGraphicsExtractor}</li>
 *   <li>{@code Screen.render} → {@code Screen.extractRenderState}</li>
 *   <li>{@code ScreenEvents.afterRender} → {@code ScreenEvents.afterExtract}</li>
 *   <li>{@code drawCenteredString} → manual centering via {@code font.width()} + {@code text(...)}</li>
 *   <li>{@code Screens.getButtons} → reflection on {@code Screen.addRenderableWidget}</li>
 * </ul>
 * Only one of the two variants (classic or modern) is compiled at a time; build.gradle
 * picks the source root from the {@code no_intermediate} property.
 */
public final class MergeUi {

    public static final String UI_VARIANT = "modern";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "multiview-merge-ui");
        t.setDaemon(true);
        return t;
    });

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** Max gap (ms) between two clicks on the same row to count as a double-click (open). */
    private static final long DOUBLE_CLICK_MS = 250L;
    /** Translucent fill painted over a selected row (kept low-alpha so the name/time stay readable). */
    private static final int SELECT_FILL = 0x3328A0FF;
    /** Opaque 1px border drawn around a selected row. */
    private static final int SELECT_BORDER = 0xFF55AAFF;

    private MergeUi() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register(MergeUi::onAfterInit);
        MultiViewMod.LOGGER.info("[MultiView] MergeUi registered (multi-select row merge, MC 26.1+ variant).");
    }

    private static final class SelectionState {
        final Set<Path> checkedPaths = new LinkedHashSet<>();
        /** Real-world recording window {@code [startMs, endMs]} per selected replay (same-moment guard). */
        final Map<Path, long[]> recWindows = new HashMap<>();
        Button mergeButton = null;

        /** Last clicked row + time, for double-click (open) detection. */
        Path lastClickPath = null;
        long lastClickMs = 0L;
    }

    private static void onAfterInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof SelectReplayScreen srs)) return;

        SelectionState state = new SelectionState();

        // Layout via the shared (unit-tested) MergeUiLayout: top-right corner, above Flashback's
        // centred control row, width-responsive and clear of the centred title.
        int[] b = MergeUiLayout.mergeButtonBounds(scaledWidth);
        int mergeX = b[0], mergeY = b[1], mergeW = b[2], btnH = b[3];

        Button mergeBtn = Button.builder(
                        Component.translatable("multiview.button.merge_selected"),
                        btn -> startMerge(state, srs, client))
                .bounds(mergeX, mergeY, mergeW, btnH)
                .build();
        mergeBtn.active = false;
        state.mergeButton = mergeBtn;

        addWidgetToScreen(screen, mergeBtn);

        fr.zeffut.multiview.telemetry.Telemetry.capture(
                fr.zeffut.multiview.telemetry.EventNames.EVT_UI_OPENED);

        // Draw selection highlights during the extract-render-state phase (post-26.1 pipeline).
        ScreenEvents.afterExtract(screen).register((s, context, mouseX, mouseY, delta) ->
                renderSelection(state, srs, context));

        // Intercept row clicks to drive the multi-selection. The 26.1 callback receives a
        // MouseButtonEvent whose accessors are .x() / .y() / .button().
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) ->
                handleRowClick(state, srs, event.x(), event.y(), event.button()));

        ScreenEvents.remove(screen).register(s -> {
            state.checkedPaths.clear();
            state.recWindows.clear();
            fr.zeffut.multiview.telemetry.Telemetry.capture(
                    fr.zeffut.multiview.telemetry.EventNames.EVT_UI_CLOSED);
        });
    }

    /**
     * Adds a renderable widget to an arbitrary {@link Screen} instance.
     *
     * <p>{@code Screen.addRenderableWidget} is protected and its exact parameter type has
     * shifted across 26.1.x patches (sometimes {@code GuiEventListener}, sometimes the more
     * specific {@code AbstractWidget} / {@code Renderable}). Rather than pin to one name,
     * we iterate every declared method on {@code Screen} and pick the first that accepts
     * our {@link Button} via {@code isAssignableFrom}.
     */
    private static void addWidgetToScreen(Screen screen, Button button) {
        for (java.lang.reflect.Method m : Screen.class.getDeclaredMethods()) {
            if (!"addRenderableWidget".equals(m.getName())) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 1) continue;
            if (!params[0].isAssignableFrom(button.getClass())) continue;
            try {
                m.setAccessible(true);
                m.invoke(screen, button);
                return;
            } catch (Throwable t) {
                MultiViewMod.LOGGER.warn("[MultiView] addRenderableWidget invoke failed ({}): {}",
                        m, t.getClass().getSimpleName() + " " + t.getMessage());
                return;
            }
        }
        MultiViewMod.LOGGER.warn("[MultiView] Could not find any Screen.addRenderableWidget overload accepting {} — merge button disabled.",
                button.getClass().getName());
    }

    private static void renderSelection(SelectionState state, SelectReplayScreen srs,
                                        GuiGraphicsExtractor context) {
        if (state.checkedPaths.isEmpty()) return;
        ReplaySelectionList list = getSelectionList(srs);
        if (list == null) return;

        // When exactly one row is selected, Flashback's native selection draws it; skip ours so it
        // looks fully native. With two or more, the native selection is cleared and we draw all.
        ReplaySelectionEntry nativeSelected = list.getSelected();

        // Clip to the list viewport so highlights slide under the top/bottom fade overlays.
        context.enableScissor(list.getRowLeft(), list.getY(),
                list.getRowLeft() + list.getRowWidth(), list.getBottom());
        try {
            List<ReplaySelectionEntry> children = list.children();
            for (int i = 0; i < children.size(); i++) {
                ReplaySelectionEntry entry = children.get(i);
                if (entry == nativeSelected) continue;
                if (!(entry instanceof ReplaySelectionEntry.ReplayListEntry rle)) continue;

                ReplaySummary summary = getSummaryFromEntry(rle);
                if (summary == null || !state.checkedPaths.contains(summary.getPath())) continue;

                int rowTop = list.getRowTop(i);
                int rowBottom = list.getRowBottom(i);
                if (!MergeUiLayout.rowIntersectsViewport(rowTop, rowBottom, list.getY(), list.getBottom())) continue;

                drawHighlight(context, list.getRowLeft(), rowTop,
                        list.getRowLeft() + list.getRowWidth(), rowBottom);
            }
        } finally {
            context.disableScissor();
        }
    }

    /** Highlights a selected row: a translucent fill (text stays readable) plus a 1px border. */
    private static void drawHighlight(GuiGraphicsExtractor context, int x0, int y0, int x1, int y1) {
        context.fill(x0, y0, x1, y1, SELECT_FILL);
        context.fill(x0, y0, x1, y0 + 1, SELECT_BORDER);       // top
        context.fill(x0, y1 - 1, x1, y1, SELECT_BORDER);       // bottom
        context.fill(x0, y0, x0 + 1, y1, SELECT_BORDER);       // left
        context.fill(x1 - 1, y0, x1, y1, SELECT_BORDER);       // right
    }

    private static boolean handleRowClick(SelectionState state, SelectReplayScreen srs,
                                          double mouseX, double mouseY, int button) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return true;

        ReplaySelectionList list = getSelectionList(srs);
        if (list == null) return true;

        List<ReplaySelectionEntry> children = list.children();
        for (int i = 0; i < children.size(); i++) {
            ReplaySelectionEntry entry = children.get(i);
            if (!(entry instanceof ReplaySelectionEntry.ReplayListEntry rle)) continue;

            int rowTop = list.getRowTop(i);
            int rowBottom = list.getRowBottom(i);
            if (!MergeUiLayout.rowClicked(mouseX, mouseY, list.getRowLeft(), list.getRowWidth(),
                    rowTop, rowBottom, list.getY(), list.getBottom())) continue;

            ReplaySummary summary = getSummaryFromEntry(rle);
            if (summary == null) return true;
            Path path = summary.getPath();

            long now = System.currentTimeMillis();
            if (path.equals(state.lastClickPath) && now - state.lastClickMs <= DOUBLE_CLICK_MS) {
                // Double-click on the same row → open it (Flashback behaviour, preserved).
                state.lastClickPath = null;
                rle.openReplay();
                return false;
            }
            state.lastClickPath = path;
            state.lastClickMs = now;

            // Toggle this row in the merge selection (keep the recording-window map in sync).
            if (state.checkedPaths.remove(path)) {
                state.recWindows.remove(path);
            } else {
                state.checkedPaths.add(path);
                state.recWindows.put(path, recordingWindow(summary));
            }
            syncNativeSelection(state, srs, list);
            updateMergeButton(state);
            return false; // consume — we own row selection
        }
        return true;
    }

    /**
     * Mirrors the merge selection into Flashback's native single-selection: when exactly one row is
     * selected, select it natively so Open/Edit/Delete target it; otherwise clear it. Best-effort —
     * never throws into the click handler.
     */
    private static void syncNativeSelection(SelectionState state, SelectReplayScreen srs,
                                            ReplaySelectionList list) {
        try {
            if (state.checkedPaths.size() == 1) {
                Path only = state.checkedPaths.iterator().next();
                for (ReplaySelectionEntry entry : list.children()) {
                    if (!(entry instanceof ReplaySelectionEntry.ReplayListEntry rle)) continue;
                    ReplaySummary summary = getSummaryFromEntry(rle);
                    if (summary != null && only.equals(summary.getPath())) {
                        list.setSelected(entry);
                        srs.updateButtonStatus(summary);
                        return;
                    }
                }
            }
            list.setSelected(null);
            srs.updateButtonStatus(null);
        } catch (Throwable t) {
            MultiViewMod.LOGGER.debug("[MultiView] could not sync native selection: {}", t.getMessage());
        }
    }

    /**
     * Real-world recording window {@code [startMs, endMs]} for a replay: its last-modified time is
     * the recording end (the value Flashback shows in the row), and {@code totalTicks * 50ms} is
     * the duration. Used to reject merging replays that aren't from the same live moment.
     */
    private static long[] recordingWindow(ReplaySummary summary) {
        int ticks = 0;
        try {
            var meta = summary.getReplayMetadata();
            if (meta != null) ticks = meta.totalTicks;
        } catch (Throwable ignore) { /* metadata unavailable — duration treated as 0 */ }
        long durationMs = (long) Math.max(0, ticks) * 50L;
        Path path = summary.getPath();
        // Best source: the recording-end instant embedded in the replay content itself
        // (arcade_replay_meta.json → epoch_time_ms, written by ServerReplay). Immutable and
        // unaffected by file copies or renames.
        Long contentEnd = MergeUiLayout.contentRecordingEndMillis(path);
        if (contentEnd != null) return new long[]{ contentEnd - durationMs, contentEnd };
        // Next: the recording instant parsed from a Flashback-style file name (immutable).
        // The file's last-modified time is unreliable — a copy/move resets it, making unrelated
        // replays look simultaneous — so it's only a fallback for user-renamed replays.
        Long start = MergeUiLayout.replayStartMillis(path == null ? null : path.getFileName().toString());
        if (start != null) return new long[]{ start, start + durationMs };
        long end = summary.getLastModified();
        return new long[]{ end - durationMs, end };
    }

    /**
     * Cache of overlap-validation results, keyed by the immutable Set of selected paths.
     * Validation reads ~1200 ticks from each replay file, so we memoise to keep checkbox
     * toggles responsive when the user revisits a previously-validated selection.
     */
    private static final Map<Set<Path>, OverlapValidator.Result> VALIDATION_CACHE = new HashMap<>();

    private static void updateMergeButton(SelectionState state) {
        if (state.mergeButton == null) return;
        int n = state.checkedPaths.size();

        fr.zeffut.multiview.telemetry.Telemetry.capture(
                fr.zeffut.multiview.telemetry.EventNames.EVT_UI_FILE_SELECTED,
                java.util.Map.of("selected_count", n));

        if (n < 2) {
            state.mergeButton.active = false;
            state.mergeButton.setMessage(Component.translatable("multiview.button.merge_selected"));
            state.mergeButton.setTooltip(null);
            return;
        }

        Component countMsg = Component.translatable("multiview.button.merge_selected.count", n);

        // Empty/corrupt replay guard (cheap, no I/O): a 0-tick recording can't be a merge
        // source — this stays a hard block.
        boolean anyEmpty = state.checkedPaths.stream()
                .map(state.recWindows::get)
                .anyMatch(w -> w != null && w[1] <= w[0]);
        if (anyEmpty) {
            fr.zeffut.multiview.telemetry.Telemetry.capture(
                    fr.zeffut.multiview.telemetry.EventNames.EVT_OVERLAP_VALIDATION_FAILED,
                    java.util.Map.of("selected_count", n, "reason", "empty_replay"));
            state.mergeButton.active = false;
            state.mergeButton.setMessage(countMsg);
            state.mergeButton.setTooltip(Tooltip.create(
                    Component.translatable("multiview.button.merge_selected.empty")));
            return;
        }

        // Disjointness is a warning, not a block: chained recordings (players joining and
        // leaving mid-session) form one overlap-connected component and merge into a coherent
        // session tape; genuinely separate groups still merge correctly — the output just
        // contains spans where no source was live.
        long[][] windows = state.checkedPaths.stream()
                .map(state.recWindows::get)
                .filter(w -> w != null && w[1] > w[0])
                .toArray(long[][]::new);
        boolean wallClockDisjoint =
                windows.length >= 2 && MergeUiLayout.windowOverlapComponents(windows) > 1;
        if (wallClockDisjoint) {
            fr.zeffut.multiview.telemetry.Telemetry.capture(
                    fr.zeffut.multiview.telemetry.EventNames.EVT_OVERLAP_VALIDATION_FAILED,
                    java.util.Map.of("selected_count", n, "reason", "recording_time_disjoint"));
        }

        Set<Path> snapshot = Set.copyOf(state.checkedPaths);
        OverlapValidator.Result result = VALIDATION_CACHE.computeIfAbsent(snapshot, s ->
                OverlapValidator.validate(new ArrayList<>(s), PacketIdProvider.minecraftRuntime()));

        boolean gameTimeDisjoint = result.status() == OverlapValidator.Status.NO_OVERLAP;
        if (gameTimeDisjoint) {
            fr.zeffut.multiview.telemetry.Telemetry.capture(
                    fr.zeffut.multiview.telemetry.EventNames.EVT_OVERLAP_VALIDATION_FAILED,
                    java.util.Map.of("selected_count", n, "reason", "gametime_disjoint"));
        }

        // OK, UNKNOWN or disjoint — all proceed. UNKNOWN means we couldn't probe a SetTime
        // anchor; disjoint gets a warning tooltip explaining the frozen gaps.
        state.mergeButton.active = true;
        state.mergeButton.setMessage(countMsg);
        state.mergeButton.setTooltip(wallClockDisjoint || gameTimeDisjoint
                ? Tooltip.create(Component.translatable("multiview.button.merge_selected.gaps"))
                : null);
    }

    private static void startMerge(SelectionState state, SelectReplayScreen parentScreen,
                                   Minecraft client) {
        if (state.checkedPaths.size() < 2) return;
        List<Path> sourcePaths = new ArrayList<>(state.checkedPaths);

        String ts = LocalDateTime.now().format(TS_FMT);
        String outputName = "merged_" + ts;

        Path replayRoot = Flashback.getReplayFolder();
        Path destPath = replayRoot.resolve(outputName);

        MergeOptions options = new MergeOptions(sourcePaths, destPath, Map.of(), false);

        MergeProgressScreen progressScreen = new MergeProgressScreen(parentScreen);
        MinecraftScreenAccess.setScreen(client, progressScreen);

        state.checkedPaths.clear();
        if (state.mergeButton != null) {
            state.mergeButton.active = false;
            state.mergeButton.setMessage(Component.translatable("multiview.button.merge_selected"));
        }

        fr.zeffut.multiview.telemetry.Telemetry.capture(
                fr.zeffut.multiview.telemetry.EventNames.EVT_UI_MERGE_CLICKED,
                java.util.Map.of("trigger", "ui"));

        java.util.concurrent.Future<?> future = EXECUTOR.submit(() -> {
            try {
                MergeOrchestrator.run(options, phase ->
                        client.execute(() -> {
                            if (MinecraftScreenAccess.getScreen(client) == progressScreen) progressScreen.setPhase(phase);
                        }));
                client.execute(() -> {
                    if (MinecraftScreenAccess.getScreen(client) == progressScreen) progressScreen.onMergeSuccess();
                });
            } catch (Throwable t) {
                MultiViewMod.LOGGER.error("[MultiView] Merge failed", t);
                String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                client.execute(() -> {
                    if (MinecraftScreenAccess.getScreen(client) == progressScreen) progressScreen.onMergeError(msg);
                });
            }
        });
        progressScreen.attachMergeFuture(future);
    }

    static ReplaySelectionList getSelectionList(SelectReplayScreen screen) {
        try {
            java.lang.reflect.Field f = SelectReplayScreen.class.getDeclaredField("list");
            f.setAccessible(true);
            return (ReplaySelectionList) f.get(screen);
        } catch (NoSuchFieldException nsf) {
            MultiViewMod.LOGGER.warn("[MultiView] SelectReplayScreen.list field not found ({}). "
                    + "Flashback may have changed its internal layout — UI disabled.",
                    nsf.getMessage());
            return null;
        } catch (Exception e) {
            MultiViewMod.LOGGER.warn("[MultiView] Could not access ReplaySelectionList field: {}",
                    e.getMessage());
            return null;
        }
    }

    private static ReplaySummary getSummaryFromEntry(ReplaySelectionEntry.ReplayListEntry entry) {
        try {
            java.lang.reflect.Field f = ReplaySelectionEntry.ReplayListEntry.class.getDeclaredField("summary");
            f.setAccessible(true);
            return (ReplaySummary) f.get(entry);
        } catch (NoSuchFieldException nsf) {
            MultiViewMod.LOGGER.warn("[MultiView] ReplayListEntry.summary field not found ({}). "
                    + "Flashback may have changed its internal layout.", nsf.getMessage());
            return null;
        } catch (Exception e) {
            MultiViewMod.LOGGER.warn("[MultiView] Could not read summary field: {}", e.getMessage());
            return null;
        }
    }
}
