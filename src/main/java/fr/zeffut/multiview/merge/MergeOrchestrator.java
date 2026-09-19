package fr.zeffut.multiview.merge;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import fr.zeffut.multiview.format.Action;
import fr.zeffut.multiview.format.ActionType;
import fr.zeffut.multiview.format.FlashbackByteBuf;
import fr.zeffut.multiview.format.FlashbackMetadata;
import fr.zeffut.multiview.format.FlashbackReader;
import fr.zeffut.multiview.format.FlashbackReplay;
import fr.zeffut.multiview.format.PacketEntry;
import fr.zeffut.multiview.format.SegmentReader;
import fr.zeffut.multiview.format.SegmentWriter;
import fr.zeffut.multiview.format.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Orchestre le merge de N replays en 1 sortie. Streaming k-way merge.
 *
 * <p>Invocation typique (depuis MergeCommand) :
 * <pre>MergeOrchestrator.run(options, progressCallback);</pre>
 *
 * @implNote Non thread-safe. Use a fresh instance per merge.
 */
public final class MergeOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(MergeOrchestrator.class);

    private MergeOrchestrator() {}

    /**
     * @param options paramètres de merge (sources, destination, overrides)
     * @param progress callback(phaseName) pour feedback UI
     * @return MergeReport final
     */
    /** Maximum total uncompressed size when extracting source zips (zip-bomb guard). */
    private static final long MAX_EXTRACTED_SOURCE_BYTES = 5L * 1024L * 1024L * 1024L; // 5 GB

    /** Maximum number of zip entries per source (zip-bomb guard — inode / FD exhaustion). */
    private static final int MAX_ZIP_ENTRIES_PER_SOURCE = 65_536;

    /**
     * Optional parent directory for source-extraction temp dirs, from
     * {@code -Dmultiview.tmpDir=<dir>}. Returns {@code null} to use the JVM default
     * ({@code java.io.tmpdir}).
     */
    private static Path tempDirParent() {
        String prop = System.getProperty("multiview.tmpDir");
        if (prop == null || prop.isBlank()) return null;
        try {
            Path p = Path.of(prop);
            Files.createDirectories(p);
            return p;
        } catch (Throwable t) {
            LOG.warn("MergeOrchestrator: multiview.tmpDir={} unusable ({}), "
                    + "falling back to java.io.tmpdir", prop, t.getMessage());
            return null;
        }
    }

    public static MergeReport run(MergeOptions options, Consumer<String> progress)
            throws IOException {
        // Validate inputs early — at least 2 sources required for a merge to be meaningful.
        if (options.sources() == null || options.sources().size() < 2) {
            throw new IllegalArgumentException(
                    "Merge requires at least 2 sources, got "
                            + (options.sources() == null ? 0 : options.sources().size()));
        }

        MergeReport report = new MergeReport();

        // destTmp declared before try so the catch block can clean it up
        Path destTmp = null;
        // Partial output written under .part suffix; renamed atomically only on success.
        Path destPart = null;
        List<Path> tempExtractDirs = new ArrayList<>();

        try {
            // Check destination before opening sources.
            // Existing zip is NOT deleted up-front: we write to a .part file and atomic-move
            // at the end, so a failed merge cannot destroy a previously-good zip.
            Path destFinal = options.destination();
            Path destZipCheck = destFinal.resolveSibling(destFinal.getFileName() + ".zip");
            if (Files.exists(destZipCheck) && !options.force()) {
                throw new IOException("Destination already exists: " + destZipCheck
                        + ". Use --force (not yet exposed via CLI).");
            }

            // 1. Open sources — extract zips to temp folders since FlashbackReader reads folders
            progress.accept("multiview.merge_progress.phase.opening");
            List<FlashbackReplay> replays = new ArrayList<>();
            for (Path src : options.sources()) {
                Path sourceToOpen = src;
                if (Files.isRegularFile(src) && src.getFileName().toString().endsWith(".zip")) {
                    // Extract to java.io.tmpdir by default; -Dmultiview.tmpDir=<dir>
                    // redirects extraction — large corpora can exceed a small system
                    // volume's free space while the replay folder lives elsewhere.
                    Path tempParent = tempDirParent();
                    Path tempDir = tempParent != null
                            ? Files.createTempDirectory(tempParent, "multiview-source-")
                            : Files.createTempDirectory("multiview-source-");
                    tempExtractDirs.add(tempDir);
                    long totalExtracted = 0L;
                    int entryCount = 0;
                    try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                            Files.newInputStream(src))) {
                        java.util.zip.ZipEntry entry;
                        Path tempDirNorm = tempDir.toAbsolutePath().normalize();
                        while ((entry = zis.getNextEntry()) != null) {
                            if (++entryCount > MAX_ZIP_ENTRIES_PER_SOURCE) {
                                throw new IOException("Source zip entry count exceeds limit ("
                                        + MAX_ZIP_ENTRIES_PER_SOURCE + "): " + src);
                            }
                            // Zip-slip guard: ensure the resolved entry stays inside tempDir.
                            Path out = tempDir.resolve(entry.getName()).toAbsolutePath().normalize();
                            if (!out.startsWith(tempDirNorm)) {
                                throw new IOException(
                                        "Refusing to extract zip entry outside of temp dir (zip-slip): "
                                                + entry.getName());
                            }
                            if (entry.isDirectory()) {
                                Files.createDirectories(out);
                            } else {
                                Path parent = out.getParent();
                                if (parent != null) Files.createDirectories(parent);
                                try (OutputStream os = Files.newOutputStream(out,
                                        java.nio.file.StandardOpenOption.CREATE,
                                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                                    byte[] buf = new byte[64 * 1024];
                                    int n;
                                    while ((n = zis.read(buf)) > 0) {
                                        totalExtracted += n;
                                        if (totalExtracted > MAX_EXTRACTED_SOURCE_BYTES) {
                                            throw new IOException(
                                                    "Source zip uncompressed size exceeds limit ("
                                                            + MAX_EXTRACTED_SOURCE_BYTES
                                                            + " bytes): " + src);
                                        }
                                        os.write(buf, 0, n);
                                    }
                                }
                            }
                        }
                    }
                    sourceToOpen = tempDir;
                }
                FlashbackReplay opened = FlashbackReader.open(sourceToOpen);
                if (opened.metadata().totalTicks() <= 0) {
                    throw new IOException("Source has invalid totalTicks ("
                            + opened.metadata().totalTicks() + "): " + src
                            + " — refusing to merge an empty/corrupt replay.");
                }
                replays.add(opened);
            }

            // 1b. All sources must share one protocol version. Packets are merged at the
            // byte level — mixing replays recorded under different MC protocol versions
            // (e.g. 26.2 vs 26.3) would splice incompatible wire formats into one stream
            // and produce a silently corrupt merged replay.
            int mergeProtocol = replays.get(0).metadata().protocolVersion();
            for (FlashbackReplay r : replays) {
                int pv = r.metadata().protocolVersion();
                if (pv != mergeProtocol) {
                    throw new IOException("Refusing to merge replays recorded under different "
                            + "protocol versions: " + replays.get(0).folder().getFileName()
                            + " is protocol " + mergeProtocol + " but "
                            + r.folder().getFileName() + " is protocol " + pv
                            + " (" + r.metadata().versionString() + ").");
                }
            }

            // 2. Find anchors + align
            progress.accept("multiview.merge_progress.phase.aligning");
            PacketIdProvider idProvider = PacketIdProvider.minecraftRuntime();
            List<TimelineAligner.Source> alignSources = new ArrayList<>();
            for (int i = 0; i < replays.size(); i++) {
                FlashbackReplay r = replays.get(i);
                Optional<TimelineAligner.SetTimeAnchor> anchor;
                try {
                    anchor = TimelineAligner.findSetTimeAnchor(r, idProvider);
                } catch (Throwable t) {
                    anchor = Optional.empty();
                    report.warn("Source " + r.folder().getFileName()
                            + " : SetTime detection failed (" + t.getClass().getSimpleName()
                            + "), fallback metadata.name");
                }
                alignSources.add(new TimelineAligner.Source(
                        r.folder().getFileName().toString(),
                        anchor,
                        r.metadata().name(),
                        r.metadata().totalTicks()));
            }
            TimelineAligner.AlignmentResult alignment = TimelineAligner.alignAll(
                    alignSources, options.tickOverrides());
            report.alignmentStrategy = alignment.strategy();

            for (int i = 0; i < replays.size(); i++) {
                MergeReport.SourceInfo si = new MergeReport.SourceInfo();
                si.folder = replays.get(i).folder().getFileName().toString();
                si.absolutePath = options.sources().get(i).toAbsolutePath().toString();
                si.uuid = replays.get(i).metadata().uuid();
                si.totalTicks = replays.get(i).metadata().totalTicks();
                si.tickOffset = alignment.tickOffsets()[i];
                report.sources.add(si);
            }
            report.mergedTotalTicks = alignment.mergedTotalTicks();

            // 3. Primary source: the one that STARTS FIRST (smallest tickOffset).
            // Rationale: primary provides the initial snapshot at tick 0 of the merged
            // timeline. If primary had a non-zero tickOffset, the client would render
            // primary's snapshot but see only secondary packets until primary "joined" —
            // causing visual glitches (stuck camera, wrong chunks) during the early ticks.
            // Ties broken by largest totalTicks.
            int primaryIdx = 0;
            int primaryOffset = alignment.tickOffsets()[0];
            int primaryLen = replays.get(0).metadata().totalTicks();
            for (int i = 1; i < replays.size(); i++) {
                int offset = alignment.tickOffsets()[i];
                int len = replays.get(i).metadata().totalTicks();
                if (offset < primaryOffset
                        || (offset == primaryOffset && len > primaryLen)) {
                    primaryIdx = i;
                    primaryOffset = offset;
                    primaryLen = len;
                }
            }

            // 4. Build context + mergers
            MergeContext ctx = new MergeContext(replays, alignment.tickOffsets(),
                    alignment.mergedStartTick(), primaryIdx, report);
            SourcePovTracker povTracker = new SourcePovTracker(replays.size());
            IdRemapper idRemapper = new IdRemapper();
            EntityMerger entityMerger = new EntityMerger(povTracker, idRemapper);
            EntityPacketRewriter entityRewriter = new EntityPacketRewriter(
                    entityMerger, idRemapper, povTracker, replays.size());
            WorldStateMerger worldMerger = new WorldStateMerger();
            WorldPacketRewriter worldRewriter = new WorldPacketRewriter(worldMerger);
            // Multi-world support: scope each source's block updates by its initial world
            // name so blocks at the same (x, y, z) in different multi-world plugins or
            // dimensions do not LWW-collide cross-source.
            for (int i = 0; i < replays.size(); i++) {
                worldRewriter.setSourceDimension(i, replays.get(i).metadata().worldName());
            }
            GlobalDeduper globalDeduper = new GlobalDeduper();
            PacketClassifier classifier = new PacketClassifier(
                    GamePacketDispatch.buildOrFallback(report));
            SecondaryPlayerSynthesizer secondarySynth = new SecondaryPlayerSynthesizer(idRemapper);

            // 5. Atomic staging
            destTmp = destFinal.resolveSibling("." + destFinal.getFileName() + ".tmp");
            Files.createDirectories(destTmp);

            // 6. Copy caches from ALL sources into merged level_chunk_caches/, renumbering files
            // sequentially. Primary's files stay at their original indices (0, 1, …).
            // Secondary sources' files follow, offset by the number of files from prior sources.
            progress.accept("multiview.merge_progress.phase.caches");
            Path destCacheDir = destTmp.resolve("level_chunk_caches");
            Files.createDirectories(destCacheDir);
            int[] fileOffset = new int[replays.size()];
            int nextGlobalFileIdx = 0;

            // Copy order: primary first, then others in sourceIdx order (preserves primary indices)
            List<Integer> copyOrder = new ArrayList<>();
            copyOrder.add(primaryIdx);
            for (int i = 0; i < replays.size(); i++) {
                if (i != primaryIdx) copyOrder.add(i);
            }

            for (int i : copyOrder) {
                fileOffset[i] = nextGlobalFileIdx;
                Path srcCacheDir = replays.get(i).folder().resolve("level_chunk_caches");
                if (!Files.isDirectory(srcCacheDir)) continue;
                List<Path> cacheFiles;
                try (var entries = Files.list(srcCacheDir)) {
                    cacheFiles = entries
                            .filter(p -> {
                                try { Integer.parseInt(p.getFileName().toString()); return true; }
                                catch (NumberFormatException e) { return false; }
                            })
                            .sorted(Comparator.comparingInt(p -> Integer.parseInt(p.getFileName().toString())))
                            .collect(Collectors.toList());
                }
                for (Path file : cacheFiles) {
                    int localFileIdx = Integer.parseInt(file.getFileName().toString());
                    int globalFileIdx = fileOffset[i] + localFileIdx;
                    Files.copy(file, destCacheDir.resolve(Integer.toString(globalFileIdx)));
                    nextGlobalFileIdx = Math.max(nextGlobalFileIdx, globalFileIdx + 1);
                }
            }
            report.stats.chunkCachesConcatenated = nextGlobalFileIdx;

            // 7. Stream merge
            progress.accept("multiview.merge_progress.phase.streaming");
            Map<String, Integer> segmentDurations = streamMerge(
                    ctx, classifier, worldMerger, worldRewriter, entityMerger, entityRewriter,
                    idRemapper, globalDeduper, povTracker, secondarySynth,
                    fileOffset, destTmp, progress);

            // Step A: Write merged metadata.json
            progress.accept("multiview.merge_progress.phase.metadata");
            FlashbackMetadata primaryMeta = replays.get(primaryIdx).metadata();
            FlashbackMetadata mergedMeta = buildMergedMetadata(
                    primaryMeta, replays, alignment.mergedTotalTicks(),
                    destFinal.getFileName().toString(), segmentDurations,
                    alignment.tickOffsets());
            try (var w = Files.newBufferedWriter(destTmp.resolve("metadata.json"))) {
                mergedMeta.toJson(w);
            }

            // Step B: Copy icon.png from primary source
            Path iconSrc = replays.get(primaryIdx).folder().resolve("icon.png");
            if (Files.exists(iconSrc)) {
                Files.copy(iconSrc, destTmp.resolve("icon.png"));
            }

            // Step C: Write merge-report.json (stamped with timestamp + producer version).
            report.mergedAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                    .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            // Read the mod version from fabric.mod.json on the classpath if available;
            // fall back to "unknown" so the field is always present.
            try (java.io.InputStream is = MergeOrchestrator.class.getResourceAsStream("/fabric.mod.json")) {
                if (is != null) {
                    String json = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                    if (m.find()) report.multiviewVersion = m.group(1);
                }
            } catch (Throwable ignore) {}
            if (report.multiviewVersion == null) report.multiviewVersion = "unknown";
            try (var w = Files.newBufferedWriter(destTmp.resolve("merge-report.json"))) {
                new GsonBuilder().setPrettyPrinting().create().toJson(report, w);
            }

            // Step D: Package .tmp/ → destFinal.zip via a .part file then atomic move.
            // Rationale: writing directly to destZip would leave a corrupt/truncated zip on crash.
            // Writing to a .part file first preserves any pre-existing destZip until the new
            // archive is fully fsync'd and renamed atomically.
            Path destZip = destFinal.resolveSibling(destFinal.getFileName() + ".zip");
            destPart = destFinal.resolveSibling(destFinal.getFileName() + ".zip.part");
            // Stale .part from a previous failed run — safe to remove (we own the .part suffix).
            Files.deleteIfExists(destPart);

            progress.accept("multiview.merge_progress.phase.packaging");
            zipDirectory(destTmp, destPart);

            // Re-check just before the move to close the TOCTOU window between the
            // initial existence check (line ~89) and here: if another process created
            // destZip in the meantime and --force is not set, refuse rather than
            // silently overwrite via REPLACE_EXISTING.
            if (Files.exists(destZip) && !options.force()) {
                throw new IOException("Destination appeared during merge: " + destZip
                        + ". Refusing to overwrite without --force.");
            }
            // Atomic rename. If destZip exists, replace it (--force already validated above).
            try {
                Files.move(destPart, destZip,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException atomicNotSupported) {
                // Some filesystems (e.g. cross-device) don't support ATOMIC_MOVE+REPLACE_EXISTING.
                // Fall back to non-atomic replace: still safer than the previous "delete-first" path.
                Files.move(destPart, destZip,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            destPart = null;

            // Zip succeeded — clean up tmp dir
            deleteRecursively(destTmp);
            destTmp = null; // don't delete on catch

            // Clean up any zip-extracted source temp dirs
            for (Path td : tempExtractDirs) {
                try { deleteRecursively(td); } catch (IOException ignore) {}
            }

            progress.accept("multiview.merge_progress.phase.done");
            return report;

        } catch (IOException | RuntimeException e) {
            report.error(e.getClass().getSimpleName() + ": " + e.getMessage());
            if (destTmp != null && Files.exists(destTmp)) {
                deleteRecursively(destTmp);
            }
            // Rollback only our partial .part file. The pre-existing destZip (if any) is
            // intentionally preserved — atomic-move semantics mean it was never touched.
            if (destPart != null) {
                try { Files.deleteIfExists(destPart); } catch (IOException ignore) {}
            }
            // Clean up zip-extracted source temp dirs
            for (Path td : tempExtractDirs) {
                try { deleteRecursively(td); } catch (IOException ignore) {}
            }
            throw e;
        }
    }

    /** Deletes a directory tree recursively, ignoring individual deletion errors. */
    private static void deleteRecursively(Path path) throws IOException {
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignore) {}
                    });
        }
    }

    /**
     * Packages the contents of {@code srcDir} into a zip archive at {@code destZip}.
     * <p>
     * Compression rules:
     * <ul>
     *   <li>{@code c0.flashback} (and any {@code *.flashback}) — STORED (already dense binary)</li>
     *   <li>Everything else — DEFLATED</li>
     * </ul>
     * Internal paths are relative to {@code srcDir} (entries at zip root).
     * Empty directories are added as entries ending with {@code /}.
     * Files are streamed to avoid loading large binaries in memory.
     */
    private static void zipDirectory(Path srcDir, Path destZip) throws IOException {
        byte[] buf = new byte[65536];
        try (OutputStream fos = Files.newOutputStream(destZip);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            try (var walk = Files.walk(srcDir)) {
                // Sort for deterministic order; directories first via natural path order
                var paths = walk.sorted().toList();
                for (Path p : paths) {
                    if (p.equals(srcDir)) continue; // skip root itself

                    String entryName = srcDir.relativize(p).toString().replace('\\', '/');
                    if (Files.isDirectory(p)) {
                        // Add directory entry (must end with '/')
                        ZipEntry dirEntry = new ZipEntry(entryName + "/");
                        zos.putNextEntry(dirEntry);
                        zos.closeEntry();
                    } else {
                        boolean stored = entryName.endsWith(".flashback");
                        ZipEntry entry = new ZipEntry(entryName);
                        if (stored) {
                            // STORED requires size + crc set upfront
                            long size = Files.size(p);
                            entry.setMethod(ZipEntry.STORED);
                            entry.setSize(size);
                            entry.setCompressedSize(size);
                            // Compute CRC32 in a first pass
                            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                            try (var in = Files.newInputStream(p)) {
                                int n;
                                while ((n = in.read(buf)) != -1) crc.update(buf, 0, n);
                            }
                            entry.setCrc(crc.getValue());
                            zos.putNextEntry(entry);
                            try (var in = Files.newInputStream(p)) {
                                int n;
                                while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
                            }
                        } else {
                            entry.setMethod(ZipEntry.DEFLATED);
                            zos.putNextEntry(entry);
                            try (var in = Files.newInputStream(p)) {
                                int n;
                                while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
                            }
                        }
                        zos.closeEntry();
                    }
                }
            }
        }
    }

    /**
     * Builds a merged {@link FlashbackMetadata} by constructing a JSON object and
     * deserializing it via {@link FlashbackMetadata#fromJson(java.io.Reader)}.
     * <p>
     * Fields sourced from primary replay metadata; chunks aggregated from the provided
     * segment-duration map (insertion-ordered); markers and customNamespacesForRegistries
     * start empty.
     *
     * @param segmentDurations ordered map of segment filename → duration in ticks
     */
    private static FlashbackMetadata buildMergedMetadata(
            FlashbackMetadata primary,
            List<FlashbackReplay> replays,
            int mergedTotalTicks,
            String destName,
            Map<String, Integer> segmentDurations,
            int[] tickOffsets) {

        Map<String, Object> chunksMap = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : segmentDurations.entrySet()) {
            Map<String, Object> segMap = new LinkedHashMap<>();
            segMap.put("duration", e.getValue());
            segMap.put("forcePlaySnapshot", false);
            chunksMap.put(e.getKey(), segMap);
        }

        // Aggregate markers from all sources, offsetting each by the source's tickOffset
        // so they land on the merged timeline. Tick collisions are resolved by appending
        // a small disambiguation (the first one wins per tick, later ones shifted +1).
        JsonObject markersJson = new JsonObject();
        java.util.Set<Integer> usedTicks = new java.util.HashSet<>();
        for (int i = 0; i < replays.size(); i++) {
            int offset = tickOffsets[i];
            for (var me : replays.get(i).metadata().markers().entrySet()) {
                int tick = me.getKey() + offset;
                while (usedTicks.contains(tick)) tick++; // disambiguate collisions
                usedTicks.add(tick);
                JsonObject markerObj = new JsonObject();
                markerObj.addProperty("colour", me.getValue().colour());
                markerObj.addProperty("description", me.getValue().description());
                markersJson.add(Integer.toString(tick), markerObj);
            }
        }

        // Build JSON representation using Gson's JsonObject to avoid reflection
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder()
                .setPrettyPrinting().disableHtmlEscaping().create();

        JsonObject obj = new JsonObject();
        obj.addProperty("uuid", UUID.randomUUID().toString());
        obj.addProperty("name", destName);
        obj.addProperty("version_string", primary.versionString());
        obj.addProperty("world_name", primary.worldName());
        obj.addProperty("data_version", primary.dataVersion());
        obj.addProperty("protocol_version", primary.protocolVersion());
        obj.addProperty("bobby_world_name", primary.bobbyWorldName());
        obj.addProperty("total_ticks", mergedTotalTicks);
        obj.add("markers", markersJson);
        obj.add("customNamespacesForRegistries", new JsonObject()); // required by Flashback to list the replay
        obj.add("chunks", gson.toJsonTree(chunksMap));

        String json = gson.toJson(obj);
        return FlashbackMetadata.fromJson(new StringReader(json));
    }

    /**
     * Reads the PLAYER_INFO_UPDATE actions bitmask byte (just after the VarInt packetId)
     * and tests bit 0 — ADD_PLAYER. Returns false on any decode error so the caller emits
     * the packet rather than dropping by mistake.
     */
    private static boolean hasAddPlayerBit(byte[] payload) {
        if (payload == null || payload.length < 2) return false;
        try {
            // Skip the leading VarInt packetId (1–5 bytes)
            int idx = 0;
            for (int i = 0; i < 5 && idx < payload.length; i++) {
                if ((payload[idx++] & 0x80) == 0) break;
            }
            if (idx >= payload.length) return false;
            int actionsBitmask = payload[idx] & 0xFF;
            return (actionsBitmask & 0x01) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Number of ticks per output segment (~5 min of gameplay at 20 ticks/s). */
    private static final int SEGMENT_TICKS = 6000;

    /**
     * Decodes a SYSTEM_CHAT payload via the MC codec and returns the visible Component content
     * as a flat string (or {@code null} on decode failure). Used to dedup logically-identical
     * "X joined the game" broadcasts whose raw bytes differ.
     */
    private static String decodeSystemChatText(byte[] payload) {
        try {
            io.netty.buffer.ByteBuf raw = io.netty.buffer.Unpooled.wrappedBuffer(payload);
            VarInts.readVarInt(raw); // skip packetId
            net.minecraft.network.RegistryFriendlyByteBuf rbuf = new net.minecraft.network.RegistryFriendlyByteBuf(
                    raw, net.minecraft.core.RegistryAccess.EMPTY);
            net.minecraft.network.protocol.game.ClientboundSystemChatPacket pkt =
                    net.minecraft.network.protocol.game.ClientboundSystemChatPacket.STREAM_CODEC.decode(rbuf);
            return pkt.content().getString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Window (in ticks) for SystemChat content-hash dedup. Identical SystemChat text within
     * this window is considered a re-broadcast and dropped. 200 ticks = 10s, wide enough to
     * absorb cross-source clock skew but small enough to allow legitimate repeat chats
     * (a player typing the same word twice will be 10s+ apart).
     */
    private static final int CHAT_DEDUP_WINDOW = 200;

    /** CRC32C content hash for dedup comparisons (used only where collisions are tolerable). */
    private static long contentHash(byte[] payload) {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(payload, 0, payload.length);
        return crc.getValue();
    }

    /**
     * 128-bit chunk content hash for dedup. CRC32C (32 bits) has a birthday-paradox collision
     * probability around 1 in 256k chunks — long replays can easily exceed that. We use a
     * 128-bit prefix of SHA-256 instead: collision probability over 1M chunks ≈ 10⁻²⁰.
     * MessageDigest is held in a ThreadLocal: instantiation is non-trivial and we hash
     * once per chunk packet (potentially hundreds of thousands per merge).
     */
    private static final ThreadLocal<java.security.MessageDigest> SHA256_TL =
            ThreadLocal.withInitial(() -> {
                try {
                    return java.security.MessageDigest.getInstance("SHA-256");
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new IllegalStateException("SHA-256 unavailable", e);
                }
            });

    private static ChunkHashKey chunkContentHash(byte[] payload) {
        java.security.MessageDigest md = SHA256_TL.get();
        md.reset();
        byte[] d = md.digest(payload);
        {
            long hi = ((long) (d[0] & 0xFF) << 56) | ((long) (d[1] & 0xFF) << 48)
                    | ((long) (d[2] & 0xFF) << 40) | ((long) (d[3] & 0xFF) << 32)
                    | ((long) (d[4] & 0xFF) << 24) | ((long) (d[5] & 0xFF) << 16)
                    | ((long) (d[6] & 0xFF) << 8)  | ((long) (d[7] & 0xFF));
            long lo = ((long) (d[8]  & 0xFF) << 56) | ((long) (d[9]  & 0xFF) << 48)
                    | ((long) (d[10] & 0xFF) << 40) | ((long) (d[11] & 0xFF) << 32)
                    | ((long) (d[12] & 0xFF) << 24) | ((long) (d[13] & 0xFF) << 16)
                    | ((long) (d[14] & 0xFF) << 8)  | ((long) (d[15] & 0xFF));
            return new ChunkHashKey(hi, lo);
        }
    }

    private record ChunkHashKey(long hi, long lo) {}

    /**
     * Performs the streaming k-way merge and writes cN.flashback segment files to destTmp.
     *
     * @return ordered map of segment filename → duration in ticks (insertion order = c0, c1, …)
     */
    private static Map<String, Integer> streamMerge(
                                    MergeContext ctx, PacketClassifier classifier,
                                    WorldStateMerger worldMerger, WorldPacketRewriter worldRewriter,
                                    EntityMerger entityMerger,
                                    EntityPacketRewriter entityRewriter, IdRemapper idRemapper,
                                    GlobalDeduper globalDeduper,
                                    SourcePovTracker povTracker,
                                    SecondaryPlayerSynthesizer secondarySynth,
                                    int[] fileOffset, Path destTmp,
                                    Consumer<String> progress) throws IOException {

        // Segment durations: insertion-ordered, populated as each segment is closed.
        Map<String, Integer> segmentDurations = new LinkedHashMap<>();

        // 1. Extract registry from primary source's first segment → main stream registry
        List<String> mainRegistry = extractRegistryFromFirstSegment(
                ctx.sources.get(ctx.primarySourceIdx));

        // PLAYER_INFO_UPDATE deduper — created early so the snapshot copy below can
        // pre-register UUIDs seen in primary's snapshot section (otherwise primary's
        // live PIU(ADD_PLAYER) for the same player produces a second "joined" line).
        PlayerInfoUpdateDeduper playerInfoDeduper = new PlayerInfoUpdateDeduper();
        int idPlayerInfoUpdate;
        try {
            idPlayerInfoUpdate = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_PLAYER_INFO_UPDATE);
        } catch (Throwable t) {
            idPlayerInfoUpdate = -1;
        }
        final int snapshotPiuId = idPlayerInfoUpdate;

        // Helper to open a new segment writer with the proper snapshot content.
        // c0 gets the full primary snapshot; subsequent segments get an empty snapshot
        // (sequential playback from c0 snapshot is sufficient; random-seek to mid-segment
        // is a known limitation).
        // Held via a single-element array so the outer-most try/finally below can close it
        // even if reassigned later in the streaming loop.
        SegmentWriter[] writerHolder = { new SegmentWriter("c0.flashback", mainRegistry) };
        SegmentWriter currentWriter = writerHolder[0];
        try {

        // Copy snapshot section of primary source's first segment into our snapshot.
        // Flashback requires init packets (dimension setup, registries, local player,
        // initial chunks, etc.) to be present in the snapshot; without them the world
        // opens empty.
        int snapshotActionsCopied = 0;
        int snapshotPiuSeenCount = 0;
        List<Path> primarySegments = ctx.sources.get(ctx.primarySourceIdx).segmentPaths();
        if (!primarySegments.isEmpty()) {
            Path firstSeg = primarySegments.get(0);
            // Memory-map the first segment so we don't load potentially hundreds of MB into the
            // heap just to read the snapshot section at its head.
            try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(firstSeg,
                    java.nio.file.StandardOpenOption.READ)) {
                java.nio.ByteBuffer mapped = ch.map(
                        java.nio.channels.FileChannel.MapMode.READ_ONLY, 0L, ch.size());
                FlashbackByteBuf firstSegBuf = new FlashbackByteBuf(
                        io.netty.buffer.Unpooled.wrappedBuffer(mapped));
                SegmentReader snapshotReader = new SegmentReader(
                        firstSeg.getFileName().toString(), firstSegBuf);
                int srcGamePacketOrd = snapshotReader.registry().indexOf(ActionType.GAME_PACKET);
                while (snapshotReader.hasNext() && snapshotReader.isPeekInSnapshot()) {
                    SegmentReader.RawAction raw = snapshotReader.nextRaw();
                    byte[] snapshotPayload = raw.payload();
                    if (raw.ordinal() == srcGamePacketOrd) {
                        snapshotPayload = worldRewriter.rewriteSnapshot(
                                ctx.primarySourceIdx, ctx.toAbsTick(ctx.primarySourceIdx, 0), snapshotPayload);
                        if (snapshotPayload == null) {
                            continue;
                        }
                    }
                    currentWriter.writeSnapshotAction(raw.ordinal(), snapshotPayload);
                    // Pre-register PIU(ADD_PLAYER) UUIDs from the snapshot so the live
                    // stream's first PIU for each player is detected as a duplicate and
                    // dropped — otherwise each player appears twice in chat (once from
                    // snapshot, once from the live broadcast at primary's join tick).
                    if (raw.ordinal() == srcGamePacketOrd && snapshotPayload.length > 0) {
                        int pidIn = PacketClassifier.readPacketId(snapshotPayload);
                        if (snapshotPiuId >= 0 && pidIn == snapshotPiuId) {
                            playerInfoDeduper.shouldEmitInfoUpdate(snapshotPayload);
                            snapshotPiuSeenCount++;
                        }
                    }
                    snapshotActionsCopied++;
                }
            }
        }
        progress.accept(String.format(
                "Transferred %d snapshot actions from primary source's first segment "
                + "(%d PIU pre-registered, dropped=%d)",
                snapshotActionsCopied,
                snapshotPiuSeenCount,
                playerInfoDeduper.duplicateAddDropped()));
        currentWriter.endSnapshot();

        // Open streaming file for c0.flashback — avoids accumulating the entire live stream
        // in a single in-memory ByteBuf (which would hit Netty's Integer.MAX_VALUE array limit
        // for long replays). Live actions are written directly to disk from this point on.
        int currentSegmentIdx = 0;
        int currentSegmentStart = 0;
        currentWriter.openStreamingFile(destTmp.resolve("c0.flashback"));

        int nextTickOrdinal    = mainRegistry.indexOf(ActionType.NEXT_TICK);
        int gamePacketOrdinal  = mainRegistry.indexOf(ActionType.GAME_PACKET);
        int cacheRefOrdinal    = mainRegistry.indexOf(ActionType.CACHE_CHUNK);

        // -------------------------------------------------------------------------
        // 0.2.2: secondary POV always-visible synthesis state
        // -------------------------------------------------------------------------
        // For each secondary source, we emit one PlayerInfoUpdate(ADD_PLAYER) + AddEntity
        // on the first CreateLocalPlayer we observe. Subsequent accurate_player_position
        // payloads from that source are rewritten to target the fake entity ID.
        // `secondarySpawned[sourceIdx]` : spawn packets already emitted
        // `secondaryFakeEntityId[sourceIdx]` : global entity ID allocated for the fake player
        // The real local entity ID is discovered from the first accurate_player_position
        // payload of each secondary and registered with the IdRemapper so subsequent
        // entity-tracking packets referencing this ID are remapped automatically.
        boolean[] secondarySpawned = new boolean[ctx.sources.size()];
        int[] secondaryFakeEntityId = new int[ctx.sources.size()];
        java.util.Arrays.fill(secondaryFakeEntityId, -1);

        // Numeric ID of PLAYER_POSITION packet, for translating secondary EGO teleports
        int idPlayerPosition = -1;
        int idForgetLevelChunk = -1;
        int idRemoveEntities = -1;
        int idLevelChunkWithLight = -1;
        int idPlayerInfoRemove = -1;
        int idMoveEntityPos = -1;
        int idMoveEntityRot = -1;
        int idMoveEntityPosRot = -1;
        int idTeleportEntity = -1;
        int idEntityPositionSync = -1;
        int idRotateHead = -1;
        int idSystemChat = -1;
        int idDisguisedChat = -1;
        int idPlayerChat = -1;
        try {
            idSystemChat = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_SYSTEM_CHAT);
            try { idDisguisedChat = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_DISGUISED_CHAT); } catch (Throwable ignore) {}
            try { idPlayerChat = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_PLAYER_CHAT); } catch (Throwable ignore) {}
            idPlayerPosition = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_PLAYER_POSITION);
            idForgetLevelChunk = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_FORGET_LEVEL_CHUNK);
            idRemoveEntities = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_REMOVE_ENTITIES);
            idLevelChunkWithLight = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT);
            idPlayerInfoRemove = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_PLAYER_INFO_REMOVE);
            idMoveEntityPos = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS);
            idMoveEntityRot = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_ROT);
            idMoveEntityPosRot = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_MOVE_ENTITY_POS_ROT);
            idTeleportEntity = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_TELEPORT_ENTITY);
            idEntityPositionSync = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_ENTITY_POSITION_SYNC);
            idRotateHead = GamePacketDispatch.findId(GamePacketTypes.CLIENTBOUND_ROTATE_HEAD);
        } catch (Throwable t) {
            LOG.warn("MergeOrchestrator: could not resolve special packet ids: "
                    + t.getClass().getSimpleName());
        }
        // For packets that safely expose their chunk coordinates, resolve the complete
        // region state by freshness. Opaque/malformed payloads continue through the
        // legacy content-hash deduplication path below.
        ChunkFreshnessPolicy chunkFreshness = new ChunkFreshnessPolicy();
        java.util.Set<ChunkHashKey> emittedChunkHashes = new java.util.HashSet<>();


        // 2. One cursor per source, priority-queue ordered by (tickAbs, sourceIdx)
        record SourceCursor(int sourceIdx, Iterator<PacketEntry> it, PacketEntry head) {}

        List<Stream<PacketEntry>> streams = new ArrayList<>();
        PriorityQueue<SourceCursor> pq = new PriorityQueue<>(
                Comparator.comparingInt((SourceCursor c) ->
                        ctx.toAbsTick(c.sourceIdx(), c.head().tick()))
                        .thenComparingInt(SourceCursor::sourceIdx));

        try {
            for (int i = 0; i < ctx.sources.size(); i++) {
                FlashbackReplay replay = ctx.sources.get(i);
                Stream<PacketEntry> stream = FlashbackReader.stream(replay);
                streams.add(stream);
                Iterator<PacketEntry> it = stream.iterator();
                if (it.hasNext()) {
                    pq.add(new SourceCursor(i, it, it.next()));
                }
            }

            int tickAbsEmitted = 0;
            long processed = 0;
            int chatDroppedSecondary = 0;
            int chatEmittedPrimary = 0;
            int chatDroppedPrimaryDup = 0;
            int chatNullTextCount = 0;
            int chatJoinedSamplesLogged = 0;
            // Visible-text dedup: each unique chat string emitted at most once. Catches the
            // "same chat, different bytes" pattern (Component components with dynamic fields like
            // signatures / timestamps that defeat byte-level dedup).
            // Bounded LRU (FIFO eviction) so long sessions cannot exhaust the heap.
            final int chatDedupMax = 10_000;
            java.util.Set<String> chatRecentChatText = java.util.Collections.newSetFromMap(
                    new java.util.LinkedHashMap<String, Boolean>(chatDedupMax + 1, 0.75f, false) {
                        @Override
                        protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
                            return size() > chatDedupMax;
                        }
                    });
            int lastPurge = 0;
            boolean warnedOutOfOrder = false;

            // ETA + cancellation support: capture the wall-clock start and the
            // total tick count so the progress callback can include an estimated
            // remaining time. The main streaming loop also checks the thread's
            // interrupted flag so a cooperative cancel from the UI (cancelling
            // the executor's Future with mayInterrupt=true) aborts the merge
            // cleanly; existing finally blocks then clean tmp files + writers.
            final long mergeStartNanos = System.nanoTime();
            final int mergedTotalTicksForEta = Math.max(1, ctx.report.mergedTotalTicks);

            while (!pq.isEmpty()) {
                if (Thread.interrupted()) {
                    throw new IOException("Merge cancelled by user.");
                }
                SourceCursor cur = pq.poll();
                int tickAbs = ctx.toAbsTick(cur.sourceIdx(), cur.head().tick());

                // Skip snapshot actions — they were already written into our snapshot section
                // above (for the primary source) or should not be duplicated in the live stream.
                // Do this BEFORE intercalating NextTick actions: snapshot entries have tick==baseTick
                // (typically 0) which would otherwise trigger spurious NextTick intercalation.
                if (cur.head().inSnapshot()) {
                    // Advance cursor and continue without emitting
                    if (cur.it().hasNext()) {
                        pq.add(new SourceCursor(cur.sourceIdx(), cur.it(), cur.it().next()));
                    }
                    processed++;
                    continue;
                }

                // Check for out-of-order packets (clock desync between sources or bad offset)
                if (tickAbs < tickAbsEmitted) {
                    if (!warnedOutOfOrder) {
                        ctx.report.warn(String.format(
                                "Out-of-order packet at tick %d (already emitted tick %d) from source %d",
                                tickAbs, tickAbsEmitted, cur.sourceIdx()));
                        warnedOutOfOrder = true;
                    }
                    ctx.report.stats.outOfOrderPackets++;
                }

                // Sanity bound: a packet whose absolute tick is wildly beyond the planned merged
                // duration would force `while (tickAbsEmitted < tickAbs)` to intercalate millions
                // of NextTicks (and roll over thousands of segments). Cap with a generous slack and
                // abort with an actionable message if the bound is exceeded.
                int sanityBound = Math.max(2 * SEGMENT_TICKS,
                        ctx.report.mergedTotalTicks + (ctx.report.mergedTotalTicks / 10) + SEGMENT_TICKS);
                if (tickAbs > sanityBound) {
                    throw new IOException(String.format(
                            "Packet tick %d exceeds sanity bound %d (mergedTotalTicks=%d) — "
                                    + "likely bad alignment offset on source %d",
                            tickAbs, sanityBound, ctx.report.mergedTotalTicks, cur.sourceIdx()));
                }

                // Segment boundary check: if we've accumulated SEGMENT_TICKS ticks in the current
                // segment, close it and open the next one before intercalating more NextTicks.
                // tickAbsEmitted tracks global ticks; segment duration = tickAbsEmitted - currentSegmentStart.
                // We check against tickAbs (the incoming absolute tick) so we roll over before
                // emitting NextTicks that would belong to the new segment.
                if (tickAbsEmitted - currentSegmentStart >= SEGMENT_TICKS) {
                    int segDuration = tickAbsEmitted - currentSegmentStart;
                    String segName = "c" + currentSegmentIdx + ".flashback";
                    currentWriter.finishStreaming();
                    segmentDurations.put(segName, segDuration);

                    // Open next segment; seed snapshot from primary source's segment
                    // covering this tick boundary so Flashback seek doesn't show a void world.
                    currentSegmentIdx++;
                    currentSegmentStart = tickAbsEmitted;
                    String nextSegName = "c" + currentSegmentIdx + ".flashback";
                    currentWriter = new SegmentWriter(nextSegName, mainRegistry);
                    writerHolder[0] = currentWriter;
                    copyPrimarySnapshotForTick(ctx.sources.get(ctx.primarySourceIdx),
                            currentWriter, currentSegmentStart, mainRegistry, ctx.report,
                            snapshotPiuId, worldRewriter, ctx.primarySourceIdx);
                    currentWriter.endSnapshot();
                    currentWriter.openStreamingFile(destTmp.resolve(nextSegName));
                }

                // Intercalate NextTick actions up to tickAbs
                while (tickAbsEmitted < tickAbs) {
                    tickAbsEmitted++;
                    if (nextTickOrdinal >= 0) {
                        currentWriter.writeLiveAction(nextTickOrdinal, new byte[0]);
                    }
                    // Check segment boundary mid-intercalation as well: if we just crossed
                    // a SEGMENT_TICKS boundary while filling in missing ticks, roll over.
                    if (tickAbsEmitted - currentSegmentStart >= SEGMENT_TICKS && tickAbsEmitted < tickAbs) {
                        int segDuration = tickAbsEmitted - currentSegmentStart;
                        String segName = "c" + currentSegmentIdx + ".flashback";
                        currentWriter.finishStreaming();
                        segmentDurations.put(segName, segDuration);

                        currentSegmentIdx++;
                        currentSegmentStart = tickAbsEmitted;
                        String nextSegName = "c" + currentSegmentIdx + ".flashback";
                        currentWriter = new SegmentWriter(nextSegName, mainRegistry);
                        writerHolder[0] = currentWriter;
                        copyPrimarySnapshotForTick(ctx.sources.get(ctx.primarySourceIdx),
                                currentWriter, currentSegmentStart, mainRegistry, ctx.report,
                                snapshotPiuId, worldRewriter, ctx.primarySourceIdx);
                        currentWriter.endSnapshot();
                        currentWriter.openStreamingFile(destTmp.resolve(nextSegName));
                    }
                }

                Category cat = classifier.classify(cur.head().action());
                switch (cat) {
                    case TICK -> {
                        // NextTick already intercalated above; nothing more to emit
                    }
                    case CONFIG -> {
                        // Emit once across all sources — dedup by content hash (packetTypeId=-1)
                        byte[] payload = ActionType.encode(cur.head().action());
                        if (globalDeduper.shouldEmit(-1, tickAbs, payload)) {
                            writeActionToMain(currentWriter, mainRegistry, cur.head().action(), ctx.report);
                        }
                    }
                    case LOCAL_PLAYER -> {
                        // Flashback has a SINGLE local-player slot. Primary's CreateLocalPlayer
                        // and accurate_player_position_optional are emitted as-is.
                        //
                        // 0.2.2: secondary POVs are made visible as regular player entities:
                        //   1. On first CreateLocalPlayer from a secondary source, synthesize
                        //      PlayerInfoUpdate(ADD_PLAYER) + AddEntity(PLAYER type) packets
                        //      so the client knows about this player and has an entity to
                        //      position.
                        //   2. For subsequent accurate_player_position_optional actions from
                        //      that source, rewrite the encoded entityId to target the fake
                        //      global ID — Flashback's AccurateEntityPositionHandler will then
                        //      apply the position/angle to the fake entity every tick.
                        if (cur.head().action() instanceof Action.CreatePlayer cp) {
                            entityRewriter.recordLocalPlayerUuid(cur.sourceIdx(), cp.bytes());
                            if (cur.sourceIdx() == ctx.primarySourceIdx) {
                                writeActionToMain(currentWriter, mainRegistry,
                                        cur.head().action(), ctx.report);
                            } else if (!secondarySpawned[cur.sourceIdx()]
                                    && secondarySynth.isAvailable()
                                    && gamePacketOrdinal >= 0) {
                                // Synthesize PlayerInfoUpdate(ADD_PLAYER) + AddEntity(PLAYER)
                                UUID uuid = SecondaryPlayerSynthesizer.extractUuid(cp.bytes());
                                com.mojang.authlib.GameProfile profile =
                                        SecondaryPlayerSynthesizer.extractGameProfile(cp.bytes());
                                byte[] infoPayload = secondarySynth.synthesizePlayerInfoUpdate(profile);
                                byte[] addEntityPayload = secondarySynth.synthesizeAddEntity(
                                        cur.sourceIdx(), uuid != null ? uuid : profile.id());
                                int fakeId = secondarySynth.getFakeEntityId(cur.sourceIdx());
                                if (infoPayload != null && addEntityPayload != null && fakeId >= 0) {
                                    currentWriter.writeLiveAction(gamePacketOrdinal, infoPayload);
                                    currentWriter.writeLiveAction(gamePacketOrdinal, addEntityPayload);
                                    secondarySpawned[cur.sourceIdx()] = true;
                                    secondaryFakeEntityId[cur.sourceIdx()] = fakeId;
                                } else {
                                    LOG.warn("MergeOrchestrator: secondary player synthesis "
                                            + "failed for source " + cur.sourceIdx()
                                            + " — player will remain invisible.");
                                }
                            }
                        } else if (cur.head().action() instanceof Action.Unknown u
                                && ("flashback:action/accurate_player_position_optional".equals(u.id())
                                    || "flashback:action/accurate_player_position".equals(u.id()))) {
                            if (cur.sourceIdx() == ctx.primarySourceIdx) {
                                writeActionToMain(currentWriter, mainRegistry,
                                        cur.head().action(), ctx.report);
                            } else if (secondarySpawned[cur.sourceIdx()]
                                    && secondaryFakeEntityId[cur.sourceIdx()] >= 0) {
                                // Register the real local entity ID ↔ fake global ID mapping
                                // on first sight so subsequent entity packets from this source
                                // that reference the secondary's local-player ID get remapped
                                // through the standard IdRemapper path.
                                int localEid = SecondaryPlayerSynthesizer
                                        .readAccuratePositionEntityId(u.payload());
                                if (localEid >= 0
                                        && !idRemapper.contains(cur.sourceIdx(), localEid)) {
                                    idRemapper.assignExisting(cur.sourceIdx(), localEid,
                                            secondaryFakeEntityId[cur.sourceIdx()]);
                                }
                                byte[] rewritten = SecondaryPlayerSynthesizer
                                        .rewriteAccuratePositionEntityId(
                                                u.payload(),
                                                secondaryFakeEntityId[cur.sourceIdx()]);
                                writeActionToMain(currentWriter, mainRegistry,
                                        new Action.Unknown(u.id(), rewritten), ctx.report);
                            }
                            // else: secondary player not yet spawned (CreateLocalPlayer not seen)
                            // — drop silently, the client has no entity to position yet.
                        } else {
                            // Other LOCAL_PLAYER actions (future-proofing): primary only.
                            if (cur.sourceIdx() == ctx.primarySourceIdx) {
                                writeActionToMain(currentWriter, mainRegistry,
                                        cur.head().action(), ctx.report);
                            }
                        }
                    }
                    case WORLD -> {
                        // Filters applied:
                        //   1. FORGET_LEVEL_CHUNK from secondary: drop (primary-only).
                        //   2. LEVEL_CHUNK_WITH_LIGHT: when its coordinate header is intact,
                        //      choose one complete state per (dimension, chunk x, chunk z) using
                        //      tick freshness. Payloads that cannot safely expose a coordinate
                        //      retain the legacy content-hash deduplication behaviour.
                        //   3. BLOCK_UPDATE / SECTION_BLOCKS_UPDATE: LWW arbitration via
                        //      WorldPacketRewriter (Phase 4.E re-enabled, 0.2.4). Stale
                        //      updates (from a slower POV) are dropped; the rewriter may
                        //      also re-encode SECTION_BLOCKS_UPDATE with only surviving
                        //      entries when a batch is partially stale.
                        if (cur.head().action() instanceof Action.GamePacket gp) {
                            int pid = PacketClassifier.readPacketId(gp.bytes());
                            if (cur.sourceIdx() != ctx.primarySourceIdx
                                    && idForgetLevelChunk >= 0
                                    && pid == idForgetLevelChunk) {
                                // drop secondary unload
                            } else if (idLevelChunkWithLight >= 0
                                    && pid == idLevelChunkWithLight) {
                                String dimension = ctx.sources.get(cur.sourceIdx()).metadata().worldName();
                                if (dimension == null || dimension.isBlank()) {
                                    dimension = "minecraft:overworld";
                                }
                                java.util.Optional<Boolean> freshnessDecision = chunkFreshness.tryShouldEmit(
                                        dimension, tickAbs, cur.sourceIdx(), gp.bytes());
                                boolean shouldEmit = freshnessDecision.orElseGet(() ->
                                        emittedChunkHashes.add(chunkContentHash(gp.bytes())));
                                if (shouldEmit) {
                                    writeActionToMain(currentWriter, mainRegistry,
                                            cur.head().action(), ctx.report);
                                }
                                // Equal/stale coordinate packets and legacy hash duplicates drop.
                            } else {
                                // Phase 4.E (0.2.4): LWW rewrite for BLOCK_UPDATE /
                                // SECTION_BLOCKS_UPDATE. Passthrough for all other WORLD
                                // packets (light, block entity, block event, etc.).
                                byte[] rewritten = worldRewriter.rewrite(
                                        cur.sourceIdx(), tickAbs, gp.bytes());
                                if (rewritten != null && gamePacketOrdinal >= 0) {
                                    currentWriter.writeLiveAction(gamePacketOrdinal, rewritten);
                                }
                                // rewritten == null → stale block update, drop silently
                            }
                        } else {
                            writeActionToMain(currentWriter, mainRegistry, cur.head().action(), ctx.report);
                        }
                    }
                    case ENTITY -> {
                        // Phase 4.D (re-enabled): decode entity packets, remap source-local
                        // entity IDs to merged global IDs via EntityMerger/IdRemapper.
                        //
                        // REMOVE_ENTITIES from secondary sources is still dropped (primary-only)
                        // — another POV may still see the entity. With N POVs, any source that
                        // temporarily loses sight of a mob/player triggers a despawn that the
                        // merged stream would propagate, making entities flicker in and out.
                        // This filter runs BEFORE the rewrite so we do not waste work on
                        // packets we will drop anyway.
                        if (cur.head().action() instanceof Action.GamePacket gp) {
                            int pid = PacketClassifier.readPacketId(gp.bytes());
                            if (cur.sourceIdx() != ctx.primarySourceIdx
                                    && idRemoveEntities >= 0
                                    && pid == idRemoveEntities) {
                                // drop secondary despawns
                            } else {
                                byte[] rewritten = entityRewriter.rewrite(
                                        cur.sourceIdx(), tickAbs, gp.bytes());
                                // Cross-source dedup of *movement* packets: after entity-ID
                                // remap, multiple sources observing the same entity move at
                                // the same tick produce byte-identical payloads. The server
                                // emits one tick-bound packet to all clients; each source
                                // records it. Dropping duplicates here is the main lever to
                                // smooth out the freeze bursts identified by MergeInspector.
                                // Only the move/rotation/teleport packets are eligible —
                                // SET_ENTITY_DATA, ENTITY_EVENT, etc. are state updates and
                                // are left alone (their semantics are not safe for content
                                // hashing). Status: the safer subset is enabled below.
                                boolean isMovementPacket =
                                        (idMoveEntityPos >= 0 && pid == idMoveEntityPos)
                                     || (idMoveEntityRot >= 0 && pid == idMoveEntityRot)
                                     || (idMoveEntityPosRot >= 0 && pid == idMoveEntityPosRot)
                                     || (idTeleportEntity >= 0 && pid == idTeleportEntity)
                                     || (idEntityPositionSync >= 0 && pid == idEntityPositionSync)
                                     || (idRotateHead >= 0 && pid == idRotateHead);
                                if (isMovementPacket
                                        && !globalDeduper.shouldEmit(pid, tickAbs, rewritten)) {
                                    ctx.report.stats.globalPacketsDeduped++;
                                } else if (gamePacketOrdinal >= 0) {
                                    currentWriter.writeLiveAction(gamePacketOrdinal, rewritten);
                                }
                            }
                        } else if (cur.head().action() instanceof Action.MoveEntities me) {
                            // MoveEntities: update POV tracker AND remap every entity id
                            // before re-emitting. Without the remap, secondary sources'
                            // local ids would collide with the primary's globals and
                            // produce frozen mobs with default rotations.
                            byte[] rewritten = entityRewriter.processMoveEntities(
                                    cur.sourceIdx(), tickAbs, me.bytes());
                            Action.MoveEntities updated =
                                    (rewritten == me.bytes()) ? me : new Action.MoveEntities(rewritten);
                            writeActionToMain(currentWriter, mainRegistry, updated, ctx.report);
                        } else {
                            writeActionToMain(currentWriter, mainRegistry, cur.head().action(), ctx.report);
                        }
                    }
                    case PASSTHROUGH -> {
                        writeActionToMain(currentWriter, mainRegistry, cur.head().action(), ctx.report);
                    }
                    case EGO -> {
                        // Primary only: routes HUD packets to main stream (Flashback handles natively).
                        // Secondary EGOs are generally dropped (they'd corrupt primary's HUD state).
                        //
                        // Exception (0.2.2): secondary's PLAYER_POSITION is translated to a
                        // TeleportEntity packet targeting the secondary's fake player entity,
                        // so large teleports / respawns show up on the fake entity.
                        // Regular movement is already covered by the per-tick rewrite of
                        // accurate_player_position_optional above.
                        if (cur.sourceIdx() == ctx.primarySourceIdx) {
                            writeActionToMain(currentWriter, mainRegistry, cur.head().action(), ctx.report);
                        } else if (secondarySpawned[cur.sourceIdx()]
                                && secondaryFakeEntityId[cur.sourceIdx()] >= 0
                                && idPlayerPosition >= 0
                                && cur.head().action() instanceof Action.GamePacket gp
                                && PacketClassifier.readPacketId(gp.bytes()) == idPlayerPosition) {
                            byte[] teleport = tryTranslatePlayerPositionToTeleport(
                                    gp.bytes(), secondaryFakeEntityId[cur.sourceIdx()],
                                    secondarySynth);
                            if (teleport != null && gamePacketOrdinal >= 0) {
                                currentWriter.writeLiveAction(gamePacketOrdinal, teleport);
                            }
                            // else: drop silently — per-tick accurate_player_position_optional
                            // will sync the position on the next tick.
                        }
                        // else: drop secondary EGO.
                    }
                    case GLOBAL -> {
                        if (cur.head().action() instanceof Action.GamePacket gp) {
                            int pid = PacketClassifier.readPacketId(gp.bytes());
                            // Stage 1: drop any secondary's PIU outright (covers cross-source
                            // dups, server broadcasts the same events to every client).
                            // Stage 2: even within primary's stream, use the UUID-level
                            // deduper to catch the snapshot/live double-emission pattern
                            // (each player appears in the c0 snapshot AND in a live PIU at
                            // primary's join time).
                            boolean playerInfoDrop = false;
                            if (snapshotPiuId >= 0 && pid == snapshotPiuId) {
                                if (cur.sourceIdx() != ctx.primarySourceIdx) {
                                    playerInfoDrop = true;
                                } else if (!playerInfoDeduper.shouldEmitInfoUpdate(gp.bytes())) {
                                    playerInfoDrop = true;
                                }
                            } else if (idPlayerInfoRemove >= 0 && pid == idPlayerInfoRemove) {
                                if (cur.sourceIdx() != ctx.primarySourceIdx) {
                                    playerInfoDrop = true;
                                } else {
                                    playerInfoDeduper.shouldEmitInfoRemove(gp.bytes());
                                }
                            } else if (idSystemChat >= 0 && pid == idSystemChat) {
                                // Drop ALL secondary system chat. For primary's own stream,
                                // also dedup by content hash + sliding tick window — clock
                                // skew or message reconstruction can produce two SystemChat
                                // packets with identical visible text at slightly different
                                // absTicks, and the strict (pid, tickAbs, hash) GlobalDeduper
                                // misses them.
                                if (cur.sourceIdx() != ctx.primarySourceIdx) {
                                    playerInfoDrop = true;
                                    chatDroppedSecondary++;
                                } else {
                                    String chatText = decodeSystemChatText(gp.bytes());
                                    if (chatText != null && chatRecentChatText.contains(chatText)) {
                                        playerInfoDrop = true;
                                        chatDroppedPrimaryDup++;
                                    } else {
                                        if (chatText != null) chatRecentChatText.add(chatText);
                                        else chatNullTextCount++;
                                        chatEmittedPrimary++;
                                        if (chatText != null && chatText.contains("joined the game")
                                                && chatJoinedSamplesLogged < 12) {
                                            chatJoinedSamplesLogged++;
                                            LOG.debug("[chat] emit tick={} text='{}'",
                                                    tickAbs, chatText);
                                        }
                                    }
                                }
                            } else if (cur.sourceIdx() != ctx.primarySourceIdx
                                    && ((idDisguisedChat >= 0 && pid == idDisguisedChat)
                                        || (idPlayerChat >= 0 && pid == idPlayerChat))) {
                                playerInfoDrop = true;
                            }
                            if (playerInfoDrop) {
                                ctx.report.stats.globalPacketsDeduped++;
                            } else if (globalDeduper.shouldEmit(pid, tickAbs, gp.bytes())) {
                                if (gamePacketOrdinal >= 0) {
                                    currentWriter.writeLiveAction(gamePacketOrdinal, gp.bytes());
                                }
                            } else {
                                ctx.report.stats.globalPacketsDeduped++;
                            }
                        }
                        // Non-GamePacket GLOBAL (unlikely but safe to passthrough)
                        else {
                            writeActionToMain(currentWriter, mainRegistry, cur.head().action(), ctx.report);
                        }
                    }
                    case CACHE_REF -> {
                        // Remap the cache index from source-local space to merged global space.
                        // fileOffset[sourceIdx] holds the number of cache files before this source's
                        // files in the merged level_chunk_caches/ directory.
                        if (cur.head().action() instanceof Action.CacheChunkRef ref
                                && cacheRefOrdinal >= 0) {
                            int localIdx     = ref.cacheIndex();
                            int localFileIdx = localIdx / 10000;
                            int localEntry   = localIdx % 10000;
                            int globalFileIdx = fileOffset[cur.sourceIdx()] + localFileIdx;
                            int globalIdx     = globalFileIdx * 10000 + localEntry;
                            byte[] newPayload = writeVarIntToBytes(globalIdx);
                            currentWriter.writeLiveAction(cacheRefOrdinal, newPayload);
                        }
                    }
                }

                // Periodic purge
                if (tickAbs - lastPurge > 100) {
                    entityMerger.purge(tickAbs);
                    globalDeduper.purgeOlderThan(tickAbs - 200);
                    lastPurge = tickAbs;
                }

                // Advance cursor
                if (cur.it().hasNext()) {
                    pq.add(new SourceCursor(cur.sourceIdx(), cur.it(), cur.it().next()));
                }

                processed++;
                if (processed % 10_000 == 0) {
                    long elapsedNanos = System.nanoTime() - mergeStartNanos;
                    String etaStr = "";
                    if (tickAbs > 0 && elapsedNanos > 0) {
                        long etaNanos = (long) ((double) elapsedNanos
                                * (mergedTotalTicksForEta - tickAbs) / tickAbs);
                        if (etaNanos > 0) {
                            long etaSec = etaNanos / 1_000_000_000L;
                            if (etaSec >= 60) {
                                etaStr = String.format(" — ETA %dm %ds", etaSec / 60, etaSec % 60);
                            } else {
                                etaStr = String.format(" — ETA %ds", etaSec);
                            }
                        }
                    }
                    progress.accept(String.format("Streaming merge... tick %d / %d%s",
                            tickAbs, ctx.report.mergedTotalTicks, etaStr));
                }
            }

            // Close the last (or only) segment
            String lastSegName = "c" + currentSegmentIdx + ".flashback";
            int lastSegDuration = tickAbsEmitted - currentSegmentStart;
            currentWriter.finishStreaming();
            segmentDurations.put(lastSegName, lastSegDuration);

            // Update entity merger stats
            ctx.report.stats.entitiesMergedByUuid       = entityMerger.uuidMergedCount();
            ctx.report.stats.entitiesMergedByHeuristic  = entityMerger.heuristicMergedCount();
            ctx.report.stats.entitiesAmbiguousMerged    = entityMerger.ambiguousMergedCount();
            // Update block LWW stats
            ctx.report.stats.blocksLwwConflicts   = worldMerger.lwwConflicts();
            ctx.report.stats.blocksLwwOverwrites  = worldMerger.lwwOverwrites();
            progress.accept(String.format(
                    "ChatDiag: primary emitted=%d, primary-dup dropped=%d, secondary dropped=%d, PIU dropped=%d",
                    chatEmittedPrimary, chatDroppedPrimaryDup, chatDroppedSecondary,
                    playerInfoDeduper.duplicateAddDropped()));
        } finally {
            // 3. Close the current SegmentWriter (releases FileChannel + Netty buffers).
            // Calling close() is a no-op if finishStreaming() already ran; otherwise it
            // ensures we don't leak a file handle on the error path.
            if (currentWriter != null) {
                try { currentWriter.close(); } catch (Exception ignore) {}
            }
            // 4. Close all source streams — guaranteed on any exception path
            for (Stream<PacketEntry> s : streams) {
                try {
                    s.close();
                } catch (Exception ignore) {
                }
            }
        }
        } finally {
            // Outermost safety net: close the active SegmentWriter even if an exception
            // is thrown during the snapshot-copy phase (before the inner try/finally).
            SegmentWriter w = writerHolder[0];
            if (w != null) {
                try { w.close(); } catch (Exception ignore) {}
            }
        }

        return segmentDurations;
    }

    /**
     * Copies snapshot actions from the primary source segment whose cumulative tick range
     * contains {@code absTick} into {@code dest}.
     *
     * <p>Primary source segments are matched by iterating {@code primary.metadata().chunks()}
     * and accumulating their durations. The first segment whose range [segStart, segStart+duration)
     * contains {@code absTick} is opened. If {@code absTick} falls beyond all primary segments
     * (primary ended before this point), we use the last primary segment as a best-effort
     * approximation.
     *
     * <p>Registry mismatch between the primary segment and {@code destRegistry} is handled by
     * mapping each action's ordinal through the primary segment's own registry to obtain the
     * type-id string, then looking it up in {@code destRegistry}. Actions with no mapping are
     * silently dropped (their count is tracked in the report).
     *
     * @param primary       the primary source replay
     * @param dest          the new output segment writer (snapshot not yet closed)
     * @param absTick       the absolute merged tick at which the new segment starts
     * @param destRegistry  the merged output registry
     * @param report        merge report for tracking dropped snapshot actions
     */
    private static void copyPrimarySnapshotForTick(
            FlashbackReplay primary,
            SegmentWriter dest,
            int absTick,
            List<String> destRegistry,
            MergeReport report,
            int skipPidPlayerInfoUpdate,
            WorldPacketRewriter worldRewriter,
            int primarySourceIdx) {

        List<Path> segments = primary.segmentPaths();
        if (segments.isEmpty()) return;

        // Walk primary's chunks metadata to find which segment covers absTick.
        // metadata().chunks() returns an insertion-ordered map of segmentName → ChunkInfo.
        int segStart = 0;
        int targetSegmentIndex = segments.size() - 1; // fallback: last segment
        int chunkIdx = 0;
        for (Map.Entry<String, FlashbackMetadata.ChunkInfo> entry
                : primary.metadata().chunks().entrySet()) {
            int duration = entry.getValue().duration();
            if (absTick < segStart + duration) {
                // absTick falls within this segment's range
                targetSegmentIndex = chunkIdx;
                break;
            }
            segStart += duration;
            chunkIdx++;
            // If chunkIdx exceeds segments.size(), the loop exits and we use the last segment
            if (chunkIdx >= segments.size()) {
                targetSegmentIndex = segments.size() - 1;
                break;
            }
        }

        Path segPath = segments.get(targetSegmentIndex);
        int copiedCount = 0;
        int droppedCount = 0;
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                segPath, java.nio.file.StandardOpenOption.READ)) {
            // Memory-map the segment instead of fully materialising it on heap; primary
            // segments can be several hundred MB and we only walk the snapshot prefix.
            // Mirrors the mmap pattern used for the first-segment copy on cold start.
            long size = ch.size();
            if (size <= 0 || size > Integer.MAX_VALUE) {
                report.warn("copyPrimarySnapshotForTick: skip — segment size out of range ("
                        + size + ") for " + segPath);
                return;
            }
            java.nio.ByteBuffer mapped = ch.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, size);
            FlashbackByteBuf segBuf = new FlashbackByteBuf(
                    io.netty.buffer.Unpooled.wrappedBuffer(mapped));
            SegmentReader reader = new SegmentReader(segPath.getFileName().toString(), segBuf);
            List<String> primaryRegistry = reader.registry();

            while (reader.hasNext() && reader.isPeekInSnapshot()) {
                SegmentReader.RawAction raw = reader.nextRaw();
                // Map ordinal from primary segment's registry to dest registry via type-id string
                String typeId = (raw.ordinal() >= 0 && raw.ordinal() < primaryRegistry.size())
                        ? primaryRegistry.get(raw.ordinal())
                        : null;
                if (typeId == null) {
                    droppedCount++;
                    continue;
                }
                int destOrdinal = destRegistry.indexOf(typeId);
                if (destOrdinal < 0) {
                    // Type not present in merged registry — drop silently
                    droppedCount++;
                    continue;
                }
                // Skip PIU(ADD_PLAYER) in mid-segment snapshots — Flashback embeds the
                // current player list in each cN snapshot so seek lands on a fully
                // populated tab list. But during continuous playback, MC's tab list is
                // already populated from c0 and persists across segment boundaries;
                // re-emitting the bulk PIU here makes MC re-fire "joined the game" chat
                // lines for every player at every segment boundary.
                if (skipPidPlayerInfoUpdate >= 0
                        && ActionType.GAME_PACKET.equals(typeId)
                        && raw.payload().length > 0) {
                    int pidIn = PacketClassifier.readPacketId(raw.payload());
                    if (pidIn == skipPidPlayerInfoUpdate && hasAddPlayerBit(raw.payload())) {
                        droppedCount++;
                        continue;
                    }
                }
                byte[] snapshotPayload = raw.payload();
                if (ActionType.GAME_PACKET.equals(typeId)) {
                    snapshotPayload = worldRewriter.rewriteSnapshot(
                            primarySourceIdx, absTick, snapshotPayload);
                    if (snapshotPayload == null) {
                        droppedCount++;
                        continue;
                    }
                }
                dest.writeSnapshotAction(destOrdinal, snapshotPayload);
                copiedCount++;
            }
        } catch (IOException e) {
            report.warn("copyPrimarySnapshotForTick: failed to read segment "
                    + segPath.getFileName() + " for absTick=" + absTick
                    + " (" + e.getMessage() + "). Snapshot will be empty for this segment.");
            return;
        }

        if (droppedCount > 0) {
            report.warn("copyPrimarySnapshotForTick: dropped " + droppedCount
                    + " snapshot actions (registry mismatch) for absTick=" + absTick
                    + " (segment " + segPath.getFileName() + ", copied=" + copiedCount + ")");
        }
        // Capture into final locals for use in lambda (compiler requires effectively-final)
        final int finalCopied = copiedCount;
        final int finalDropped = droppedCount;
        final int finalSegIdx = targetSegmentIndex;
        if (LOG.isDebugEnabled()) {
            LOG.debug("copyPrimarySnapshotForTick: absTick={} → segment[{}]={} copied={} dropped={}",
                    absTick, finalSegIdx, segPath.getFileName(), finalCopied, finalDropped);
        }
    }

    /**
     * Writes an Action to the main SegmentWriter by resolving its type id in the registry.
     * If the action's type id is absent from the registry, the action is skipped and a
     * warning counter is incremented.
     *
     * <p>Uses {@link ActionType#idOf(Action)} to obtain the canonical type string and
     * {@link ActionType#encode(Action)} to obtain the payload bytes.
     */
    private static void writeActionToMain(SegmentWriter writer, List<String> registry,
                                          Action action, MergeReport report) throws IOException {
        String typeId = ActionType.idOf(action);
        int ordinal = registry.indexOf(typeId);
        if (ordinal < 0) {
            // Unknown action type id — not present in the primary source's registry.
            // This can happen for action types introduced by other sources or mods.
            // Track in passthroughPackets map and skip.
            report.stats.passthroughPackets.merge(typeId, 1, Integer::sum);
            return;
        }
        byte[] payload = ActionType.encode(action);
        writer.writeLiveAction(ordinal, payload);
    }

    /**
     * Attempts to translate a {@code ClientboundPlayerPositionPacket} payload into a
     * {@code TeleportEntity} (ClientboundEntityPositionSyncPacket) payload targeting
     * {@code fakeEntityId}.
     *
     * <p>PLAYER_POSITION uses relative-position flags that describe which components
     * are relative to the current position vs. absolute.  In the merge pipeline we
     * do not have the client's previous position, so any packet with relative flags
     * cannot be safely converted — we fall through and let the per-tick
     * {@code accurate_player_position_optional} sync handle the next frame.
     *
     * <p>Returns {@code null} on decode failure, fallback mode, or if relative flags
     * are set — callers should silently drop the packet in that case.
     */
    private static byte[] tryTranslatePlayerPositionToTeleport(
            byte[] playerPositionPayload, int fakeEntityId,
            SecondaryPlayerSynthesizer synth) {
        if (!synth.isAvailable() || fakeEntityId < 0) return null;
        try {
            io.netty.buffer.ByteBuf raw =
                    io.netty.buffer.Unpooled.wrappedBuffer(playerPositionPayload);
            VarInts.readVarInt(raw); // skip packetId
            FriendlyByteBuf pbuf = new FriendlyByteBuf(raw);
            ClientboundPlayerPositionPacket decoded = ClientboundPlayerPositionPacket.STREAM_CODEC.decode(pbuf);
            // Skip if any relative flag is set — absolute position unknown.
            if (!decoded.relatives().isEmpty()) return null;
            net.minecraft.world.entity.PositionMoveRotation pos = decoded.change();
            net.minecraft.world.phys.Vec3 v = pos.position();
            return synth.synthesizeTeleport(fakeEntityId,
                    v.x, v.y, v.z,
                    pos.yRot(), pos.xRot());
        } catch (Throwable t) {
            // Decode failure is non-fatal; caller will drop.
            return null;
        }
    }

    /**
     * Encodes {@code value} as a standard Minecraft VarInt (little-endian 7-bit groups).
     */
    private static byte[] writeVarIntToBytes(int value) {
        ByteBuf tmp = Unpooled.buffer(5);
        try {
            VarInts.writeVarInt(tmp, value);
            byte[] out = new byte[tmp.readableBytes()];
            tmp.readBytes(out);
            return out;
        } finally {
            tmp.release();
        }
    }

    /**
     * Reads the registry from the first segment of {@code replay}.
     * Returns the registry list as declared in the segment header.
     *
     * <p>If the replay has no segments (empty metadata.chunks), returns a minimal
     * static list containing the known action type ids.
     */
    private static List<String> extractRegistryFromFirstSegment(FlashbackReplay replay)
            throws IOException {
        List<Path> segments = replay.segmentPaths();
        if (segments.isEmpty()) {
            // Fallback: minimal registry for Phase 3 — covers all known action types
            return List.of(
                    ActionType.NEXT_TICK,
                    ActionType.CONFIGURATION,
                    ActionType.GAME_PACKET,
                    ActionType.CREATE_PLAYER,
                    ActionType.MOVE_ENTITIES,
                    ActionType.CACHE_CHUNK,
                    ActionType.VOICE_CHAT,
                    ActionType.ENCODED_VOICE_CHAT
            );
        }
        Path firstSegment = segments.get(0);
        byte[] bytes = Files.readAllBytes(firstSegment);
        FlashbackByteBuf buf = new FlashbackByteBuf(Unpooled.wrappedBuffer(bytes));
        SegmentReader reader = new SegmentReader(firstSegment.getFileName().toString(), buf);
        return reader.registry();
    }
}
