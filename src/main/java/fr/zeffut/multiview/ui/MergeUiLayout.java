package fr.zeffut.multiview.ui;

/**
 * Pure layout math for the merge UI, deliberately free of any Minecraft rendering type so it can
 * be unit-tested without a game client. Both the {@code java-classic} and {@code java-modern}
 * {@code MergeUi} variants call into this, so the tested logic IS the rendered logic.
 */
public final class MergeUiLayout {
    private MergeUiLayout() {}

    /** Outer margin from the screen edge (px, GUI scale). */
    public static final int MARGIN = 4;
    /** Merge button height (px). */
    public static final int BUTTON_H = 20;
    /** Merge button max width (px). */
    public static final int BUTTON_MAX_W = 130;
    /** Merge button min width on narrow windows (px). */
    public static final int BUTTON_MIN_W = 72;
    /**
     * Top of Flashback's centred control row (search box + sort) in SelectReplayScreen
     * (confirmed from Flashback 0.39.4 bytecode). The merge button's bottom must stay at or
     * above this so it never overlaps that row at any window size.
     */
    public static final int CONTROL_ROW_TOP = 22;
    /** Horizontal clearance kept right of screen centre so the button never hits the centred title. */
    public static final int TITLE_HALF_CLEARANCE = 60;

    /**
     * Merge button bounds for a given GUI-scaled screen width: top-right corner, sitting above
     * Flashback's control row, width-responsive (shrinks on narrow windows) and clear of the
     * centred title.
     *
     * @return {@code [x, y, width, height]}
     */
    public static int[] mergeButtonBounds(int scaledWidth) {
        int y = Math.max(1, CONTROL_ROW_TOP - BUTTON_H);          // bottom = y + H ≤ CONTROL_ROW_TOP
        int avail = scaledWidth - MARGIN - (scaledWidth / 2 + TITLE_HALF_CLEARANCE);
        int w = Math.max(BUTTON_MIN_W, Math.min(BUTTON_MAX_W, avail));
        int x = scaledWidth - w - MARGIN;
        return new int[] { x, y, w, BUTTON_H };
    }

    /**
     * True if any part of the row falls within the list's vertical viewport — i.e. its checkbox is
     * worth drawing. Partially-visible checkboxes are clipped by a scissor to the viewport so they
     * slide under the top/bottom fade overlays rather than popping in/out at the edge.
     */
    public static boolean rowIntersectsViewport(int rowTop, int rowBottom, int listTop, int listBottom) {
        return rowBottom > listTop && rowTop < listBottom;
    }

    /**
     * True if a click at {@code (mouseX, mouseY)} lands on the visible part of a replay row: inside
     * the row rectangle AND inside the list viewport, so a click on the sliver of a half-scrolled
     * row hidden under an overlay doesn't toggle it.
     */
    public static boolean rowClicked(double mouseX, double mouseY, int rowLeft, int rowWidth,
                                     int rowTop, int rowBottom, int listTop, int listBottom) {
        return mouseX >= rowLeft && mouseX <= rowLeft + rowWidth
                && mouseY >= rowTop && mouseY <= rowBottom
                && mouseY >= listTop && mouseY <= listBottom;
    }

    /**
     * True if every pair of recording windows overlaps in time — i.e. the replays were recorded
     * during the same real-world moment. Each window is {@code [startMs, endMs]}. With fewer than
     * two windows there is nothing to contradict, so the result is {@code true}. Half-open
     * intervals: two windows that merely touch at an endpoint count as disjoint.
     */
    public static boolean allWindowsOverlap(long[][] windows) {
        for (int i = 0; i < windows.length; i++) {
            for (int j = i + 1; j < windows.length; j++) {
                long[] a = windows[i], b = windows[j];
                if (a[1] <= b[0] || b[1] <= a[0]) return false;
            }
        }
        return true;
    }

    /**
     * Counts the overlap-connected components among the recording windows. Two windows are linked
     * when their intervals share at least one instant (half-open: touching endpoints don't link).
     * A single component means every replay is reachable through a chain of overlapping
     * recordings — enough for a coherent session-tape merge even when two particular replays
     * never coexisted (A: 20:00–21:00, B: 20:30–22:00, C: 21:30–23:00). Multiple components means
     * disjoint groups (e.g. two separate sessions); the merge engine still handles that, but the
     * output will contain spans where no source was live. For interval sets, connectivity is a
     * simple sweep: sorted by start, a new component begins whenever a window starts at or after
     * the running max end.
     */
    public static int windowOverlapComponents(long[][] windows) {
        if (windows == null || windows.length < 2) return windows == null ? 0 : windows.length;
        long[][] sorted = windows.clone();
        java.util.Arrays.sort(sorted, java.util.Comparator.comparingLong(w -> w[0]));
        int components = 1;
        long maxEnd = sorted[0][1];
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i][0] >= maxEnd) components++;
            maxEnd = Math.max(maxEnd, sorted[i][1]);
        }
        return components;
    }

    /**
     * Recording-end epoch-millis embedded in the replay itself. Arcade-writer replays
     * (ServerReplay) carry {@code arcade_replay_meta.json} with an {@code epoch_time_ms} field —
     * the wall-clock instant the recording stopped, immune to file copies/renames. Works on both
     * {@code .zip} files and extracted replay folders. Returns {@code null} when absent or
     * unreadable so callers can fall back to the file name / mtime.
     */
    public static Long contentRecordingEndMillis(java.nio.file.Path replayPath) {
        if (replayPath == null) return null;
        try {
            if (java.nio.file.Files.isDirectory(replayPath)) {
                java.nio.file.Path meta = replayPath.resolve("arcade_replay_meta.json");
                if (!java.nio.file.Files.isRegularFile(meta)) return null;
                return parseEpochTimeMs(java.nio.file.Files.readString(meta));
            }
            if (java.nio.file.Files.isRegularFile(replayPath)
                    && replayPath.getFileName().toString().toLowerCase().endsWith(".zip")) {
                try (java.nio.file.FileSystem fs = java.nio.file.FileSystems.newFileSystem(replayPath)) {
                    java.nio.file.Path meta = fs.getPath("/arcade_replay_meta.json");
                    if (!java.nio.file.Files.isRegularFile(meta)) return null;
                    return parseEpochTimeMs(java.nio.file.Files.readString(meta));
                }
            }
        } catch (Throwable ignore) {
            return null;
        }
        return null;
    }

    /** Extracts the {@code "epoch_time_ms"} value — accepts both string and bare-number JSON. */
    private static Long parseEpochTimeMs(String json) {
        if (json == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"epoch_time_ms\"\\s*:\\s*\"?(\\d+)\"?")
                .matcher(json);
        if (!m.find()) return null;
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses the recording-start epoch-millis from a default Flashback replay file name like
     * {@code "2026-04-29T18_29_59.zip"} (local time). This is the true recording instant and is
     * immutable — unlike the file's last-modified time, which a copy/move resets. Returns
     * {@code null} when the name doesn't match that pattern (e.g. a user-renamed replay), so the
     * caller can fall back to the file timestamp.
     */
    public static Long replayStartMillis(String fileName) {
        if (fileName == null) return null;
        String n = fileName;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        try {
            java.time.LocalDateTime dt = java.time.LocalDateTime.parse(n,
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH_mm_ss"));
            return dt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }
}
