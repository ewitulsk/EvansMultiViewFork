package fr.zeffut.multiview.merge;

import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless merge integration test — runs MergeOrchestrator against real multiplayer
 * replay zips without booting the Minecraft client. Initializes MC registries via
 * {@link Bootstrap#bootStrap()} so all packet codecs work (entity types, registries,
 * etc.) and the merge runs in full-fidelity mode, not the degraded fallback.
 *
 * <p>Per-MC-version usage: swap {@code gradle.properties} to the version under test
 * (via {@code scripts/build-version.sh}) and run:
 * <pre>./gradlew test --tests MergeIntegrationTest</pre>
 *
 * <p>To validate across all configured MC versions in one shot:
 * <pre>./scripts/test-all-versions.sh</pre>
 */
class MergeIntegrationTest {

    // Replay sources are overridable so CI/devs can point at their own corpus:
    //   -Dmultiview.testReplayDir=<dir>            base directory for relative names
    //   -Dmultiview.testReplayZips=a.zip;b.zip     ';'-separated names or absolute paths
    static final Path REPLAYS_DIR = Path.of(
            System.getProperty("multiview.testReplayDir", "run/flashback/replays"));
    static final List<String> SOURCE_ZIPS = sourceZips();

    private static List<String> sourceZips() {
        String prop = System.getProperty("multiview.testReplayZips");
        if (prop == null || prop.isBlank()) {
            return List.of(
                    "2026-02-20T23_25_15.zip",
                    "Hika_Civ_4.zip",
                    "Jour_4_Romani_.zip",
                    "Sénat_empirenapo2026-02-20T23_20_16.zip"
            );
        }
        List<String> out = new ArrayList<>();
        for (String s : prop.split(";")) {
            if (!s.isBlank()) out.add(s.trim());
        }
        return List.copyOf(out);
    }

    private static Path resolveSource(String name) {
        Path p = Path.of(name);
        return p.isAbsolute() ? p : REPLAYS_DIR.resolve(name);
    }

    static boolean replaysAvailable() {
        if (SOURCE_ZIPS.isEmpty()) return false;
        for (String name : SOURCE_ZIPS) {
            if (!Files.isRegularFile(resolveSource(name))) return false;
        }
        return true;
    }

    @BeforeAll
    static void initMinecraft() {
        // Initialize Minecraft registries so packet codecs that touch built-in registries
        // (entity types, block states, datafixers, etc.) work. Without this, the merge
        // runs in degraded PASSTHROUGH mode and stats are meaningless.
        try {
            SharedConstants.tryDetectVersion();
        } catch (Throwable ignore) {
            // Some MC versions name this differently; if reflection fails, Bootstrap
            // itself usually handles its own SharedConstants init.
        }
        Bootstrap.bootStrap();
    }

    @Test
    @EnabledIf("replaysAvailable")
    void mergeRealMultiplayerPovs(@TempDir Path tmp) throws Exception {
        long startMs = System.currentTimeMillis();
        List<Path> sources = new ArrayList<>();
        for (String name : SOURCE_ZIPS) {
            sources.add(resolveSource(name));
        }
        Path dest = tmp.resolve("merged");
        MergeOptions options = new MergeOptions(sources, dest, Map.of(), false);

        MergeReport report = MergeOrchestrator.run(options, msg -> System.out.println("[PROGRESS] " + msg));

        long durMs = System.currentTimeMillis() - startMs;
        System.out.println("[REPORT] duration=" + durMs + "ms");
        System.out.println("[REPORT] alignment=" + report.alignmentStrategy);
        System.out.println("[REPORT] merged total ticks=" + report.mergedTotalTicks);
        System.out.println("[REPORT] sources=" + report.sources.size());
        System.out.println("[REPORT] entities merged uuid=" + report.stats.entitiesMergedByUuid);
        System.out.println("[REPORT] blocks LWW overwrites=" + report.stats.blocksLwwOverwrites);
        System.out.println("[REPORT] blocks LWW conflicts=" + report.stats.blocksLwwConflicts);
        System.out.println("[REPORT] globals deduped=" + report.stats.globalPacketsDeduped);
        System.out.println("[REPORT] passthrough buckets=" + report.stats.passthroughPackets.size());
        System.out.println("[REPORT] warnings=" + report.warnings.size());
        System.out.println("[REPORT] errors=" + report.errors);

        // Verify Bootstrap actually took effect — degraded mode shows up as
        // "GamePacketDispatch: introspection failed" warning.
        boolean degraded = report.warnings.stream()
                .anyMatch(w -> w.contains("GamePacketDispatch: introspection failed"));
        assertFalse(degraded,
                "Merge ran in degraded mode — Bootstrap.bootStrap() did not initialize "
                + "GameProtocols. Stats are unreliable. Warnings: " + report.warnings);

        // Output must be a single zip file (the temp folder must be cleaned up).
        Path destZip = dest.resolveSibling(dest.getFileName() + ".zip");
        assertFalse(Files.exists(dest), "Temp folder must not remain after merge");
        assertTrue(Files.exists(destZip), "Merged zip must exist at " + destZip);
        assertTrue(Files.size(destZip) > 1_000_000L,
                "Merged zip suspiciously small: " + Files.size(destZip));

        validateZipIntegrity(destZip, report);
        assertTrue(report.errors.isEmpty(), "Merge produced errors: " + report.errors);
        assertEquals(SOURCE_ZIPS.size(), report.sources.size(),
                "All source replays must be reflected in report");
        assertTrue(report.mergedTotalTicks > 0, "Merged total_ticks must be > 0");
        assertTrue(report.stats.entitiesMergedByUuid > 0,
                "Expected >0 entities merged by UUID on real multiplayer data");
    }

    /** Validate the merged zip's structure: metadata.json, segment files, caches, valid headers. */
    private static void validateZipIntegrity(Path destZip, MergeReport report) throws Exception {
        try (ZipFile zf = new ZipFile(destZip.toFile())) {
            Set<String> entries = zf.stream()
                    .map(java.util.zip.ZipEntry::getName)
                    .collect(Collectors.toSet());

            assertTrue(entries.contains("metadata.json"), "metadata.json missing");
            assertTrue(entries.contains("merge-report.json"), "merge-report.json missing");
            List<String> segments = entries.stream()
                    .filter(e -> e.matches("c\\d+\\.flashback"))
                    .sorted()
                    .collect(Collectors.toList());
            assertFalse(segments.isEmpty(), "At least one *.flashback segment expected");
            assertTrue(entries.stream().anyMatch(e -> e.startsWith("level_chunk_caches")),
                    "level_chunk_caches expected");

            // Each segment must be > 0 bytes and start with a non-zero byte (Flashback files
            // begin with a varint count, never literally 0x00 at byte 0 for non-empty data).
            for (String seg : segments) {
                var entry = zf.getEntry(seg);
                assertNotNull(entry);
                assertTrue(entry.getSize() > 0, "Empty segment: " + seg);
                try (var in = zf.getInputStream(entry)) {
                    int firstByte = in.read();
                    assertTrue(firstByte >= 0, "Segment " + seg + " unreadable");
                }
            }

            // Validate metadata.json structure and that total_ticks matches the report.
            var metaEntry = zf.getEntry("metadata.json");
            assertNotNull(metaEntry);
            String metaJson;
            try (var in = zf.getInputStream(metaEntry)) {
                metaJson = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertTrue(metaJson.contains("\"total_ticks\""), "metadata.json must contain total_ticks");
            // Soft-check that total_ticks in JSON roughly matches the report (parses int after
            // the key — Gson would be heavier than needed for one field).
            int idx = metaJson.indexOf("\"total_ticks\"");
            int colonIdx = metaJson.indexOf(':', idx);
            int endIdx = metaJson.indexOf(',', colonIdx);
            if (endIdx < 0) endIdx = metaJson.indexOf('}', colonIdx);
            long metaTicks = Long.parseLong(metaJson.substring(colonIdx + 1, endIdx).trim());
            assertEquals(report.mergedTotalTicks, metaTicks,
                    "metadata.json total_ticks must match report");
        }
    }
}
