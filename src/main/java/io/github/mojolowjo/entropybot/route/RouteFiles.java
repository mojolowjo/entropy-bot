package io.github.mojolowjo.entropybot.route;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The tile files: {@code <routesDir>/<dimFolder>/t.<tx>.<tz>.bin}, one per 128x128 blocks (8x8 chunk columns, all
 * y), gzip, written atomically (temp file, then move). Layout after the {@link RouteFileHeader}: box count, then per
 * box key, quality, build time, walk hash, stale flag, door count (0 = an empty box, nothing more), doors, and the
 * crossing matrix as uint16 quarter-ticks. A truncated, damaged or other-format file is ignored and counted; load
 * never throws.
 */
public final class RouteFiles {
    private RouteFiles() {
    }

    /** What a load found. {@code ignored} = nothing usable (with {@code note} saying why). */
    public record LoadResult(RouteFileHeader header, List<SectionRecord> records, Set<SectionKey> stale,
                             boolean ignored, boolean settingsChanged, boolean areasChanged, String note) {
    }

    public static Path tilePath(Path routesDir, String dimFolder, int tx, int tz) {
        return routesDir.resolve(dimFolder).resolve("t." + tx + "." + tz + ".bin");
    }

    /** Writes one tile's boxes (all of the same dim and tile) atomically. */
    public static void save(Path file, RouteFileHeader header, Collection<SectionRecord> records,
                            Set<SectionKey> stale) throws IOException {
        throw new UnsupportedOperationException("stage B");
    }

    /**
     * Reads one tile for {@code dim}, comparing with the {@code expected} header (the current versions and hashes).
     * Never throws: a bad file comes back {@code ignored} and is counted in {@code counters}.
     */
    public static LoadResult load(Path file, int dim, RouteFileHeader expected, RouteCounters counters, RouteLog log) {
        throw new UnsupportedOperationException("stage B");
    }

    /** Loads a tile into the store (settings changed = all its boxes stale). Returns the result. */
    public static LoadResult loadInto(RouteStore store, Path file, int dim, RouteFileHeader expected,
                                      RouteCounters counters, RouteLog log) {
        throw new UnsupportedOperationException("stage B");
    }
}
