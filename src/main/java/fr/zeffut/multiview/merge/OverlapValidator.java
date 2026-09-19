package fr.zeffut.multiview.merge;

import fr.zeffut.multiview.MultiViewMod;
import fr.zeffut.multiview.format.FlashbackReader;
import fr.zeffut.multiview.format.FlashbackReplay;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pre-merge guard: verifies that the selected replays' recorded windows overlap on the
 * monotonic server gameTime axis.
 *
 * <p>Overlap is transitive: replays that chain through overlapping intermediaries
 * (players joining and leaving mid-session) form a single connected component and merge
 * into one coherent session tape. Only a split into multiple gameTime-overlap
 * components — genuinely separate recording groups — is flagged, and the UI treats that
 * as a warning (the merged output simply contains spans where no source was live)
 * rather than a hard block.
 *
 * <p>Pure logic — no Minecraft client API references — so it stays testable from
 * plain JVM tests and runs identically on both the classic and modern UI variants.
 */
public final class OverlapValidator {

    private OverlapValidator() {}

    /** Per-replay first-segment size cap when probing for the SetTime anchor (OOM guard). */
    private static final long MAX_PROBE_SEGMENT_BYTES = 256L * 1024L * 1024L; // 256 MB

    public enum Status {
        /** The selection forms a single overlap-connected component on the gameTime axis. */
        OK,
        /** The selection splits into multiple gameTime-overlap components (disjoint groups). */
        NO_OVERLAP,
        /** A replay is missing a {@code ClientboundSetTimePacket} anchor — overlap undetermined. */
        UNKNOWN
    }

    public record Result(Status status, List<Path> offendingPair) {
        public static Result ok() { return new Result(Status.OK, List.of()); }
        public static Result noOverlap(Path a, Path b) {
            return new Result(Status.NO_OVERLAP, List.of(a, b));
        }
        public static Result unknown() { return new Result(Status.UNKNOWN, List.of()); }
    }

    private record Interval(Path path, long start, long end) {}

    /**
     * Probes each replay for its gameTime anchor + duration, then checks whether the
     * intervals form a single overlap-connected component on the gameTime axis.
     *
     * @param selected     replay folders the user wants to merge
     * @param idProvider   runtime packet-id mapping (must be the same instance the merge
     *                     would use, so that {@code ClientboundSetTimePacket} resolves to
     *                     the right id for the running MC version)
     * @return {@link Result#ok()} when the selection is one connected component,
     *         {@link Result#noOverlap(Path, Path)} when it splits into disjoint groups
     *         (reporting a boundary pair),
     *         or {@link Result#unknown()} if any replay has no detectable SetTime anchor.
     */
    public static Result validate(List<Path> selected, PacketIdProvider idProvider) {
        if (selected == null || selected.size() < 2) return Result.ok();

        MultiViewMod.LOGGER.debug("[OverlapValidator] probing {} replays with setTimeId={}",
                selected.size(), idProvider.setTimePacketId());

        List<Interval> intervals = new ArrayList<>(selected.size());
        for (Path p : selected) {
            FileSystem zipFs = null;
            try {
                // Flashback stores replays as .zip files; FlashbackReader expects a folder.
                // Mount the zip as a NIO FileSystem so Files.isRegularFile / readAllBytes
                // work transparently on entries inside it.
                Path replayRoot;
                if (Files.isRegularFile(p) && p.toString().toLowerCase().endsWith(".zip")) {
                    zipFs = FileSystems.newFileSystem(p);
                    replayRoot = zipFs.getPath("/");
                } else {
                    replayRoot = p;
                }

                FlashbackReplay replay = FlashbackReader.open(replayRoot);
                // OOM guard: refuse to probe replays whose first segment is unreasonably
                // large. `findSetTimeAnchor` reads the segment fully into memory and we
                // only need the first ~1200 ticks of packet data; anything beyond
                // MAX_PROBE_SEGMENT_BYTES is either a recording artefact or a hostile file.
                if (!replay.segmentPaths().isEmpty()) {
                    long size = Files.size(replay.segmentPaths().get(0));
                    if (size > MAX_PROBE_SEGMENT_BYTES) {
                        MultiViewMod.LOGGER.warn("[OverlapValidator] {}: first segment is {} bytes (limit {}); returning UNKNOWN",
                                p.getFileName(), size, MAX_PROBE_SEGMENT_BYTES);
                        return Result.unknown();
                    }
                }
                Optional<TimelineAligner.SetTimeAnchor> anchor =
                        TimelineAligner.findSetTimeAnchor(replay, idProvider);
                if (anchor.isEmpty()) {
                    MultiViewMod.LOGGER.warn("[OverlapValidator] {}: no SetTime anchor in first 1200 ticks, falling back to UNKNOWN",
                            p.getFileName());
                    return Result.unknown();
                }

                long start = anchor.get().gameTime() - anchor.get().tickLocal();
                long end = start + replay.metadata().totalTicks();
                MultiViewMod.LOGGER.debug("[OverlapValidator] {}: interval=[{}, {}] ({} ticks)",
                        p.getFileName(), start, end, replay.metadata().totalTicks());
                intervals.add(new Interval(p, start, end));
            } catch (IOException e) {
                MultiViewMod.LOGGER.warn("[OverlapValidator] {}: IOException ({}), falling back to UNKNOWN",
                        p.getFileName(), e.getMessage());
                return Result.unknown();
            } finally {
                if (zipFs != null) {
                    try { zipFs.close(); } catch (IOException ignore) {}
                }
            }
        }

        // Connectivity check, not pairwise: replays that chain through overlapping
        // intermediaries (players joining/leaving mid-session) form a single component
        // and merge into one coherent session tape. Only a split into multiple
        // gameTime components — genuinely separate recording groups — is reported.
        // For interval sets a sorted sweep suffices: a new component starts whenever
        // an interval begins at or after the running max end (half-open intervals).
        intervals.sort(java.util.Comparator.comparingLong(Interval::start));
        long maxEnd = intervals.get(0).end();
        Interval maxEndInterval = intervals.get(0);
        for (int i = 1; i < intervals.size(); i++) {
            Interval in = intervals.get(i);
            if (in.start() >= maxEnd) {
                MultiViewMod.LOGGER.info("[OverlapValidator] selection splits into disjoint "
                        + "gameTime groups: {} vs {} (separate recording clusters — merge "
                        + "will contain unrecorded spans)",
                        maxEndInterval.path().getFileName(), in.path().getFileName());
                return Result.noOverlap(maxEndInterval.path(), in.path());
            }
            if (in.end() > maxEnd) {
                maxEnd = in.end();
                maxEndInterval = in;
            }
        }
        return Result.ok();
    }
}
