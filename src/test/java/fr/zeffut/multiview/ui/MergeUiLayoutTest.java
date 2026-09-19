package fr.zeffut.multiview.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic checks for the two merge-UI graphical bugs:
 *  1. the merge button overlapping Flashback's control row / running off-screen, and
 *  2. checkboxes bleeding over the list's top/bottom overlays.
 *
 * These assert the invariants across a wide range of window sizes and scroll positions; the
 * pre-fix code (button at y=4 height 20 → bottom 24 > 22; row-based checkbox cull) fails them.
 */
class MergeUiLayoutTest {

    // Representative GUI-scaled widths from tiny (high GUI scale, small window) to 4K.
    private static final int[] WIDTHS = {200, 256, 320, 360, 427, 480, 640, 854, 1024, 1280, 1920, 2560, 3840};

    @Test
    void buttonNeverOverlapsFlashbackControlRow() {
        for (int w : WIDTHS) {
            int[] b = MergeUiLayout.mergeButtonBounds(w);
            int bottom = b[1] + b[3];
            assertTrue(bottom <= MergeUiLayout.CONTROL_ROW_TOP,
                    "width=" + w + ": button bottom " + bottom + " must stay ≤ control row top "
                            + MergeUiLayout.CONTROL_ROW_TOP);
            assertTrue(b[1] >= 0, "width=" + w + ": button y must be on-screen");
        }
    }

    @Test
    void buttonStaysOnScreen() {
        for (int w : WIDTHS) {
            int[] b = MergeUiLayout.mergeButtonBounds(w);
            int x = b[0], width = b[2];
            assertTrue(x >= 0, "width=" + w + ": button x " + x + " off the left edge");
            assertTrue(x + width <= w, "width=" + w + ": button right " + (x + width) + " past screen " + w);
        }
    }

    @Test
    void buttonKeptInRightHalfClearOfCentredTitle() {
        // For any realistic width the button's left edge stays right of centre, so it can't
        // collide with the centred screen title.
        for (int w : WIDTHS) {
            if (w < 152) continue; // below this the min-width floor wins; not a real window size
            int[] b = MergeUiLayout.mergeButtonBounds(w);
            assertTrue(b[0] >= w / 2,
                    "width=" + w + ": button left " + b[0] + " crosses centre " + (w / 2));
        }
    }

    @Test
    void buttonWidthIsResponsiveAndClamped() {
        for (int w : WIDTHS) {
            int width = MergeUiLayout.mergeButtonBounds(w)[2];
            assertTrue(width >= MergeUiLayout.BUTTON_MIN_W && width <= MergeUiLayout.BUTTON_MAX_W,
                    "width=" + w + ": button width " + width + " out of ["
                            + MergeUiLayout.BUTTON_MIN_W + ", " + MergeUiLayout.BUTTON_MAX_W + "]");
        }
        // Wide window → full width; narrow window → shrunk.
        assertEquals(MergeUiLayout.BUTTON_MAX_W, MergeUiLayout.mergeButtonBounds(3840)[2]);
        assertTrue(MergeUiLayout.mergeButtonBounds(340)[2] < MergeUiLayout.BUTTON_MAX_W,
                "narrow window should shrink the button");
    }

    @Test
    void rowDrawnWhenAnyPartIntersectsViewport() {
        int top = 40, bottom = 200;
        // Fully inside, and straddling each edge → drawn (scissor clips the overflow).
        assertTrue(MergeUiLayout.rowIntersectsViewport(100, 120, top, bottom));
        assertTrue(MergeUiLayout.rowIntersectsViewport(top - 8, top + 8, top, bottom), "straddling top");
        assertTrue(MergeUiLayout.rowIntersectsViewport(bottom - 8, bottom + 8, top, bottom), "straddling bottom");
        // Fully above / below, and flush against the edge → skipped.
        assertFalse(MergeUiLayout.rowIntersectsViewport(0, top, top, bottom), "row ends exactly at top");
        assertFalse(MergeUiLayout.rowIntersectsViewport(bottom, bottom + 20, top, bottom), "row starts exactly at bottom");
        assertFalse(MergeUiLayout.rowIntersectsViewport(0, 10, top, bottom));
        assertFalse(MergeUiLayout.rowIntersectsViewport(bottom + 50, bottom + 70, top, bottom));
    }

    @Test
    void rowClickedRequiresRowRectAndViewport() {
        int top = 40, bottom = 200;           // list viewport
        int rowLeft = 30, rowWidth = 220;     // a row spanning x 30..250
        int rowTop = 100, rowBottom = 118;    // fully inside the viewport
        // Inside the row rect and viewport → clicked.
        assertTrue(MergeUiLayout.rowClicked(120, 109, rowLeft, rowWidth, rowTop, rowBottom, top, bottom));
        // Left of / right of the row → not clicked.
        assertFalse(MergeUiLayout.rowClicked(20, 109, rowLeft, rowWidth, rowTop, rowBottom, top, bottom));
        assertFalse(MergeUiLayout.rowClicked(260, 109, rowLeft, rowWidth, rowTop, rowBottom, top, bottom));
        // Above / below the row → not clicked.
        assertFalse(MergeUiLayout.rowClicked(120, 90, rowLeft, rowWidth, rowTop, rowBottom, top, bottom));
        assertFalse(MergeUiLayout.rowClicked(120, 130, rowLeft, rowWidth, rowTop, rowBottom, top, bottom));
        // A row straddling the bottom edge: a click on its part below the viewport is rejected.
        int sTop = 195, sBottom = 213;        // straddles bottom=200
        assertTrue(MergeUiLayout.rowClicked(120, 198, rowLeft, rowWidth, sTop, sBottom, top, bottom), "visible part");
        assertFalse(MergeUiLayout.rowClicked(120, 205, rowLeft, rowWidth, sTop, sBottom, top, bottom), "below viewport");
    }

    @Test
    void allWindowsOverlapDetectsDifferentMoments() {
        // Same live session: overlapping recording windows → mergeable.
        assertTrue(MergeUiLayout.allWindowsOverlap(new long[][]{
                {1000, 5000}, {2000, 6000}, {1500, 4000}}));
        // One replay from a different day → disjoint → not mergeable.
        assertFalse(MergeUiLayout.allWindowsOverlap(new long[][]{
                {1000, 5000}, {2000, 6000}, {90000, 95000}}));
        // Two windows that merely touch at an endpoint count as disjoint.
        assertFalse(MergeUiLayout.allWindowsOverlap(new long[][]{{1000, 5000}, {5000, 9000}}));
        // Nested window still overlaps.
        assertTrue(MergeUiLayout.allWindowsOverlap(new long[][]{{1000, 9000}, {3000, 4000}}));
        // Fewer than two windows: nothing to contradict.
        assertTrue(MergeUiLayout.allWindowsOverlap(new long[][]{{1000, 5000}}));
        assertTrue(MergeUiLayout.allWindowsOverlap(new long[][]{}));
    }

    @Test
    void replayStartMillisParsesFlashbackNames() {
        Long a = MergeUiLayout.replayStartMillis("2026-04-29T18_29_59.zip");
        Long b = MergeUiLayout.replayStartMillis("2026-03-22T19_44_53.zip");
        assertNotNull(a);
        assertNotNull(b);
        assertTrue(a > b, "a later filename must parse to a larger instant");
        // Same instant regardless of extension presence.
        assertEquals(a, MergeUiLayout.replayStartMillis("2026-04-29T18_29_59"));
        // Non-default / renamed / null names → null (caller falls back to file time).
        assertNull(MergeUiLayout.replayStartMillis("J5 - SCENE 23_24 - Fight.zip"));
        assertNull(MergeUiLayout.replayStartMillis("merged_20260610_120000.zip"));
        assertNull(MergeUiLayout.replayStartMillis(null));
    }

    @Test
    void windowOverlapComponentsCountsChains() {
        // Chain: A∩B and B∩C overlap, A∩C disjoint → one component (session tape).
        assertEquals(1, MergeUiLayout.windowOverlapComponents(new long[][]{
                {1000, 5000}, {4000, 9000}, {8000, 12000}}));
        // Two separate groups → two components.
        assertEquals(2, MergeUiLayout.windowOverlapComponents(new long[][]{
                {1000, 5000}, {2000, 6000}, {90000, 95000}, {91000, 96000}}));
        // Touching endpoints don't link (half-open intervals).
        assertEquals(2, MergeUiLayout.windowOverlapComponents(new long[][]{
                {1000, 5000}, {5000, 9000}}));
        // Nested windows stay one component.
        assertEquals(1, MergeUiLayout.windowOverlapComponents(new long[][]{
                {1000, 9000}, {3000, 4000}, {5000, 6000}}));
        // Degenerate inputs.
        assertEquals(1, MergeUiLayout.windowOverlapComponents(new long[][]{{1000, 5000}}));
        assertEquals(0, MergeUiLayout.windowOverlapComponents(new long[][]{}));
        assertEquals(0, MergeUiLayout.windowOverlapComponents(null));
        // Pairwise-vs-component distinction: this set is NOT all-pairs-overlapping but
        // IS one component — the regression the session-tape workflow needs.
        long[][] chained = {{1000, 3000}, {2500, 6000}, {5500, 8000}};
        assertFalse(MergeUiLayout.allWindowsOverlap(chained));
        assertEquals(1, MergeUiLayout.windowOverlapComponents(chained));
    }

    @Test
    void contentRecordingEndMillisReadsArcadeMeta(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws Exception {
        // Zip-packaged replay (ServerReplay layout).
        java.nio.file.Path zip = tmp.resolve("2026-09-18--21-54-59.zip");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                java.nio.file.Files.newOutputStream(zip))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("arcade_replay_meta.json"));
            zos.write("{\"name\":\"X\",\"epoch_time_ms\":\"1789786903894\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("metadata.json"));
            zos.write("{\"total_ticks\":8080}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        assertEquals(1789786903894L, MergeUiLayout.contentRecordingEndMillis(zip));

        // Extracted-folder replay.
        java.nio.file.Path dir = tmp.resolve("extracted");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("arcade_replay_meta.json"),
                "{\"epoch_time_ms\": 1234567890000}");
        assertEquals(1234567890000L, MergeUiLayout.contentRecordingEndMillis(dir));

        // No arcade meta → null (caller falls back to filename / mtime).
        java.nio.file.Path dirNoMeta = tmp.resolve("plain");
        java.nio.file.Files.createDirectories(dirNoMeta);
        assertNull(MergeUiLayout.contentRecordingEndMillis(dirNoMeta));
        assertNull(MergeUiLayout.contentRecordingEndMillis(null));
        assertNull(MergeUiLayout.contentRecordingEndMillis(tmp.resolve("missing.zip")));

        // Malformed value → null.
        java.nio.file.Path dirBad = tmp.resolve("bad");
        java.nio.file.Files.createDirectories(dirBad);
        java.nio.file.Files.writeString(dirBad.resolve("arcade_replay_meta.json"), "{\"epoch_time_ms\":\"abc\"}");
        assertNull(MergeUiLayout.contentRecordingEndMillis(dirBad));
    }
}
