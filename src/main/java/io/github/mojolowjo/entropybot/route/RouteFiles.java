package io.github.mojolowjo.entropybot.route;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The tile files: {@code <routesDir>/<dimFolder>/t.<tx>.<tz>.bin}, one per 128x128 blocks (8x8 chunk columns, all
 * y), gzip, written atomically (temp file, then move). Layout after the {@link RouteFileHeader}: dim, tx, tz, box
 * count, then per box: key, quality, build time, walk hash, stale flag, door count (0 = an empty box, nothing more),
 * doors, and the crossing matrix as uint16 quarter-ticks; the magic again at the end (a cut file is caught even when
 * gzip ends cleanly). A truncated, damaged or other-format file is ignored and counted; load never throws.
 */
public final class RouteFiles {
    private RouteFiles() {
    }

    /** What a load found. {@code ignored} = nothing usable (with {@code note} saying why). */
    public record LoadResult(RouteFileHeader header, List<SectionRecord> records, Set<SectionKey> stale,
                             boolean ignored, boolean settingsChanged, boolean areasChanged, String note) {
    }

    private static final int MAX_BOXES = 8 * 8 * 64;

    public static Path tilePath(Path routesDir, String dimFolder, int tx, int tz) {
        return routesDir.resolve(dimFolder).resolve("t." + tx + "." + tz + ".bin");
    }

    /** Writes one tile's boxes (all of the same dim and tile) atomically. */
    public static void save(Path file, RouteFileHeader header, Collection<SectionRecord> records,
                            Set<SectionKey> stale) throws IOException {
        if (records.isEmpty()) throw new IllegalArgumentException("no boxes to save");
        SectionKey first = records.iterator().next().key();
        for (SectionRecord r : records)
            if (r.key().dim() != first.dim() || r.key().tileX() != first.tileX() || r.key().tileZ() != first.tileZ())
                throw new IllegalArgumentException(r.key() + " is not in the tile of " + first);
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream os = Files.newOutputStream(tmp);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(os)))) {
            out.writeInt(RouteFileHeader.MAGIC);
            out.writeInt(header.formatVersion());
            out.writeUTF(String.valueOf(header.modVersion()));
            out.writeUTF(String.valueOf(header.baritoneVersion()));
            out.writeLong(header.settingsHash());
            out.writeLong(header.areasHash());
            out.writeInt(first.dim());
            out.writeInt(first.tileX());
            out.writeInt(first.tileZ());
            out.writeInt(records.size());
            for (SectionRecord r : records) {
                out.writeInt(r.key().sx());
                out.writeInt(r.key().sy());
                out.writeInt(r.key().sz());
                out.writeByte(r.quality().ordinal());
                out.writeLong(r.builtAt());
                out.writeLong(r.walkHash());
                out.writeBoolean(stale != null && stale.contains(r.key()));
                out.writeShort(r.doorCount());
                for (Door d : r.doors()) {
                    out.writeByte(d.face());
                    for (long m : d.mask()) out.writeLong(m);
                    out.writeInt(d.repX());
                    out.writeInt(d.repY());
                    out.writeInt(d.repZ());
                    out.writeInt(d.minX());
                    out.writeInt(d.minY());
                    out.writeInt(d.minZ());
                    out.writeInt(d.maxX());
                    out.writeInt(d.maxY());
                    out.writeInt(d.maxZ());
                    out.writeByte((d.canLeave() ? 1 : 0) | (d.canEnter() ? 2 : 0));
                }
                for (char c : r.crossing()) out.writeChar(c);
            }
            out.writeInt(RouteFileHeader.MAGIC);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Reads one tile for {@code dim}, comparing with the {@code expected} header (the current versions and hashes).
     * Never throws: a bad file comes back {@code ignored} and is counted in {@code counters}.
     */
    public static LoadResult load(Path file, int dim, RouteFileHeader expected, RouteCounters counters, RouteLog log) {
        try (InputStream is = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(is)))) {
            if (in.readInt() != RouteFileHeader.MAGIC) return ignored(file, "not a route file", counters, log);
            int version = in.readInt();
            if (version != RouteFileHeader.FORMAT_VERSION)
                return ignored(file, "format version " + version + ", want " + RouteFileHeader.FORMAT_VERSION,
                        counters, log);
            RouteFileHeader h = new RouteFileHeader(version, in.readUTF(), in.readUTF(), in.readLong(), in.readLong());
            int fdim = in.readInt(), tx = in.readInt(), tz = in.readInt(), count = in.readInt();
            if (fdim != dim) return ignored(file, "dim " + fdim + ", want " + dim, counters, log);
            if (count < 0 || count > MAX_BOXES) return ignored(file, "box count " + count, counters, log);
            List<SectionRecord> recs = new ArrayList<>(count);
            Set<SectionKey> stale = new HashSet<>();
            SectionRecord.Quality[] qs = SectionRecord.Quality.values();
            for (int k = 0; k < count; k++) {
                SectionKey key = new SectionKey(dim, in.readInt(), in.readInt(), in.readInt());
                if (key.tileX() != tx || key.tileZ() != tz)
                    return ignored(file, key + " outside tile " + tx + "," + tz, counters, log);
                int q = in.readUnsignedByte();
                if (q >= qs.length) return ignored(file, "quality " + q, counters, log);
                long builtAt = in.readLong(), walkHash = in.readLong();
                boolean st = in.readBoolean();
                int n = in.readUnsignedShort();
                if (n > 6 * 256) return ignored(file, "door count " + n, counters, log);
                List<Door> doors = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    int face = in.readUnsignedByte();
                    if (face > 5) return ignored(file, "face " + face, counters, log);
                    long[] mask = {in.readLong(), in.readLong(), in.readLong(), in.readLong()};
                    int rx = in.readInt(), ry = in.readInt(), rz = in.readInt();
                    int ax = in.readInt(), ay = in.readInt(), az = in.readInt();
                    int bx = in.readInt(), by = in.readInt(), bz = in.readInt();
                    int fl = in.readUnsignedByte();
                    doors.add(new Door(face, mask, rx, ry, rz, ax, ay, az, bx, by, bz, (fl & 1) != 0, (fl & 2) != 0));
                }
                char[] cr = new char[n * n];
                for (int i = 0; i < cr.length; i++) cr[i] = in.readChar();
                recs.add(new SectionRecord(key, qs[q], builtAt, walkHash, doors, cr));
                if (st) stale.add(key);
            }
            if (in.readInt() != RouteFileHeader.MAGIC) return ignored(file, "no end mark", counters, log);
            boolean settingsChanged = expected != null && expected.settingsHash() != h.settingsHash();
            boolean areasChanged = expected != null && expected.areasHash() != h.areasHash();
            String note = "loaded " + recs.size() + " boxes"
                    + (settingsChanged ? ", Baritone settings changed: all stale" : "")
                    + (areasChanged ? ", areas changed" : "")
                    + (expected != null && !expected.modVersion().equals(h.modVersion())
                    ? ", written by mod " + h.modVersion() : "");
            return new LoadResult(h, recs, stale, false, settingsChanged, areasChanged, note);
        } catch (IOException | RuntimeException e) {
            return ignored(file, "unreadable (" + e + ")", counters, log);
        }
    }

    private static LoadResult ignored(Path file, String why, RouteCounters counters, RouteLog log) {
        String note = "route file " + file.getFileName() + " ignored: " + why;
        if (counters != null) counters.fileIgnored(note, log);
        else if (log != null) log.warn(note);
        return new LoadResult(null, List.of(), Set.of(), true, false, false, note);
    }

    /** Loads a tile into the store (settings changed = all its boxes stale). Returns the result. */
    public static LoadResult loadInto(RouteStore store, Path file, int dim, RouteFileHeader expected,
                                      RouteCounters counters, RouteLog log) {
        LoadResult res = load(file, dim, expected, counters, log);
        for (SectionRecord r : res.records()) {
            store.put(r);
            if (res.settingsChanged() || res.stale().contains(r.key())) store.markStale(r.key());
        }
        return res;
    }
}
