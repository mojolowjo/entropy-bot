package io.github.mojolowjo.entropybot.recorder;

import io.github.mojolowjo.entropybot.recorder.Recorder.Change;
import io.github.mojolowjo.entropybot.recorder.Recorder.Incident;
import io.github.mojolowjo.entropybot.recorder.Recorder.Slice;
import io.github.mojolowjo.entropybot.recorder.Recorder.TrailPoint;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The recorder's store (pure Java, no game classes): the recent changes and trail in memory, everything on disk under
 * {@code entropybot/recorder/}:
 * <ul>
 *   <li>{@code changes-YYYY-MM-DD.log}: one line per change, {@code ms dim x y z from to b} (ids without "minecraft:",
 *       b = 1 when the bot did it), local date of the change;</li>
 *   <li>{@code trail-YYYY-MM-DD.log}: {@code ms dim x y z [note]};</li>
 *   <li>{@code base/<dim>/<cx>.<cz>.gz}: the newest baseline of each chunk ({@link ChunkBase});</li>
 *   <li>{@code incidents/inc-<ms>.txt.gz}: one incident each (text, see {@link IncidentText});</li>
 *   <li>{@code settings.json}.</li>
 * </ul>
 * Adds are cheap and come from the game thread (memory ring + a bounded queue); {@link #flush} and {@link #maintain}
 * run on the recorder's background thread; the reads are thread-safe and may run anywhere.
 * Disk: day files and baselines older than {@code keep} go (by file time), incidents after max(3 x keep, 7 days) or
 * beyond {@link #MAX_INCIDENTS}; over the hard cap the oldest files go first (day files and baselines, then
 * incidents); while today's files alone are over the cap the changes and trail are kept in memory only.
 */
public final class RecStore {
    public static final long DEFAULT_CAP = 200L << 20;          // 200 MB, everything under recorder/
    public static final int MAX_INCIDENTS = 200;
    static final int MEM_CHANGES = 50_000, MEM_TRAIL = 50_000, PENDING = 50_000, BASE_CACHE = 24;
    static final int MAX_READ = 500_000;                         // lines a single read collects at most
    static final long SEEN_SLACK_MS = 5_000;

    private final Path root;
    private final LongSupplier clock;
    private final long cap;
    private volatile long keepMs = 48L * 3_600_000L;
    private final ZoneId zone = ZoneId.systemDefault();

    private final Object memLock = new Object();
    private final ArrayDeque<Change> mem = new ArrayDeque<>();
    private final ArrayDeque<TrailPoint> memTrail = new ArrayDeque<>();
    private long memChangesSince, memTrailSince;
    private final ArrayBlockingQueue<Object> pending = new ArrayBlockingQueue<>(PENDING);
    private final AtomicLong dropped = new AtomicLong(), written = new AtomicLong();
    private volatile long diskBytes = -1;
    private volatile boolean capped;
    private volatile String lastError;
    private volatile int baseFiles = -1, incidentsOnDisk = -1;

    /** dim|cx|cz -> {covered since (ms), last seen loaded (ms)}: when the recorder knew the chunk exactly. */
    private final Map<String, long[]> coverage = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, ChunkBase> baseCache = new LinkedHashMap<>(32, 0.75f, true);
    private final Map<String, String> incidentReasons = new ConcurrentHashMap<>();

    public RecStore(Path root, LongSupplier clock, long cap) {
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
        this.cap = cap;
        long now = clock.getAsLong();
        memChangesSince = now;
        memTrailSince = now;
    }

    public Path root() { return root; }

    public long cap() { return cap; }

    public void keepHours(int h) { keepMs = Math.max(1, h) * 3_600_000L; }

    public long diskBytes() { return diskBytes; }

    public boolean capped() { return capped; }

    public long dropped() { return dropped.get(); }

    public String lastError() { return lastError; }

    // ---- adds (game thread) ----

    public void addChange(Change c) {
        synchronized (memLock) {
            mem.addLast(c);
            if (mem.size() > MEM_CHANGES) {
                memChangesSince = mem.removeFirst().atMs() + 1;
                while (!mem.isEmpty() && mem.peekFirst().atMs() < memChangesSince) mem.removeFirst();
            }
        }
        if (!capped && !pending.offer(c)) dropped.incrementAndGet();
    }

    public void addTrail(TrailPoint p) {
        synchronized (memLock) {
            memTrail.addLast(p);
            if (memTrail.size() > MEM_TRAIL) {
                memTrailSince = memTrail.removeFirst().atMs() + 1;
                while (!memTrail.isEmpty() && memTrail.peekFirst().atMs() < memTrailSince) memTrail.removeFirst();
            }
        }
        if (!capped && !pending.offer(p)) dropped.incrementAndGet();
    }

    // ---- lines ----

    static String shortId(String id) { return id == null ? "?" : id.startsWith("minecraft:") ? id.substring(10) : id; }

    static String longId(String id) { return id.equals("?") ? null : id.indexOf(':') < 0 && !id.startsWith("[") ? "minecraft:" + id : id; }

    static String changeLine(Change c) {
        return c.atMs() + " " + c.dim() + " " + c.x() + " " + c.y() + " " + c.z() + " " + shortId(c.from()) + " " + shortId(c.to()) + " " + (c.byBot() ? 1 : 0);
    }

    static Change parseChange(String line) {
        String[] f = line.split(" ");
        if (f.length != 8) return null;
        try {
            return new Change(Long.parseLong(f[0]), f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]),
                    longId(f[5]), longId(f[6]), f[7].equals("1"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String trailLine(TrailPoint p) {
        String s = p.atMs() + " " + p.dim() + " " + p.x() + " " + p.y() + " " + p.z();
        return p.note() == null || p.note().isEmpty() ? s : s + " " + p.note().replace('\n', ' ').replace('\r', ' ');
    }

    static TrailPoint parseTrail(String line) {
        String[] f = line.split(" ", 6);
        if (f.length < 5) return null;
        try {
            return new TrailPoint(Long.parseLong(f[0]), f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]),
                    f.length == 6 ? f[5] : null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    String day(long ms) { return LocalDate.ofInstant(Instant.ofEpochMilli(ms), zone).toString(); }

    // ---- background: write and keep the disk in bounds ----

    /** Appends what is queued to the day files. Background thread (or a test). Returns the lines written. */
    public int flush() {
        List<Object> batch = new ArrayList<>();
        pending.drainTo(batch);
        if (batch.isEmpty()) return 0;
        TreeMap<String, StringBuilder> files = new TreeMap<>();
        for (Object o : batch) {
            if (o instanceof Change c) files.computeIfAbsent("changes-" + day(c.atMs()) + ".log", k -> new StringBuilder()).append(changeLine(c)).append('\n');
            else if (o instanceof TrailPoint p) files.computeIfAbsent("trail-" + day(p.atMs()) + ".log", k -> new StringBuilder()).append(trailLine(p)).append('\n');
        }
        try {
            Files.createDirectories(root);
            for (Map.Entry<String, StringBuilder> e : files.entrySet()) {
                Files.write(root.resolve(e.getKey()), e.getValue().toString().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            written.addAndGet(batch.size());
        } catch (IOException e) {
            lastError = "write: " + e.getMessage();
        }
        return batch.size();
    }

    /** Deletes what is past keep, holds the hard cap, counts the disk use. Background thread (or a test). */
    public void maintain() {
        long now = clock.getAsLong();
        long keep = keepMs, incKeep = Math.max(3 * keep, 7L * 24 * 3_600_000L);
        try {
            if (!Files.isDirectory(root)) { diskBytes = 0; capped = false; return; }
            String today = day(now);
            List<FileInfo> all = files();
            List<FileInfo> incidents = new ArrayList<>();
            long total = 0;
            for (Iterator<FileInfo> it = all.iterator(); it.hasNext(); ) {
                FileInfo f = it.next();
                boolean inc = f.rel.startsWith("incidents/");
                boolean dayFile = f.rel.endsWith(".log") && !f.rel.contains("/");
                boolean base = f.rel.startsWith("base/");
                boolean old = inc ? f.mtime < now - incKeep : (dayFile || base) && f.mtime < now - keep;
                if (old && !(dayFile && f.rel.contains(today))) {
                    delete(f);
                    it.remove();
                    continue;
                }
                if (inc) incidents.add(f);
                total += f.size;
            }
            incidents.sort(Comparator.comparingLong(f -> f.mtime));
            while (incidents.size() > MAX_INCIDENTS) {
                FileInfo f = incidents.remove(0);
                delete(f);
                all.remove(f);
                total -= f.size;
            }
            if (total > cap) {
                List<FileInfo> order = new ArrayList<>();
                for (FileInfo f : all) {
                    boolean dayFile = f.rel.endsWith(".log") && !f.rel.contains("/");
                    if (f.rel.startsWith("base/") || (dayFile && !f.rel.contains(today))) order.add(f);
                }
                order.sort(Comparator.comparingLong(f -> f.mtime));
                order.addAll(incidents);       // already oldest first: incidents go last
                long target = cap * 9 / 10;
                for (FileInfo f : order) {
                    if (total <= target) break;
                    delete(f);
                    total -= f.size;
                }
            }
            capped = total > cap;
            diskBytes = total;
            int nb = 0, ni = 0;
            for (FileInfo f : files()) {
                if (f.rel.startsWith("base/") && f.rel.endsWith(".gz")) nb++;
                else if (f.rel.startsWith("incidents/")) ni++;
            }
            baseFiles = nb;
            incidentsOnDisk = ni;
        } catch (IOException | RuntimeException e) {
            lastError = "maintain: " + e.getMessage();
        }
    }

    private record FileInfo(Path path, String rel, long size, long mtime) {}

    private List<FileInfo> files() throws IOException {
        List<FileInfo> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Iterator<Path> it = s.iterator(); it.hasNext(); ) {
                Path p = it.next();
                if (!Files.isRegularFile(p)) continue;
                try {
                    out.add(new FileInfo(p, root.relativize(p).toString().replace('\\', '/'), Files.size(p), Files.getLastModifiedTime(p).toMillis()));
                } catch (IOException ignored) {}
            }
        }
        return out;
    }

    private void delete(FileInfo f) {
        try {
            Files.deleteIfExists(f.path);
            if (f.rel.startsWith("base/")) synchronized (baseCache) { baseCache.remove(f.rel); }
            if (f.rel.startsWith("incidents/")) incidentReasons.remove(f.path.getFileName().toString());
        } catch (IOException e) {
            lastError = "delete: " + e.getMessage();
        }
    }

    // ---- reads (any thread) ----

    private static boolean near(Change c, String dim, int x, int y, int z, int r) {
        return c.dim().equals(dim) && Math.abs(c.x() - x) <= r && Math.abs(c.y() - y) <= r && Math.abs(c.z() - z) <= r;
    }

    /** Changes within r blocks (cube) of x y z since sinceMs, newest first, at most max. */
    public List<Change> changes(String dim, int x, int y, int z, int r, long sinceMs, int max) {
        List<Change> out = new ArrayList<>();
        if (max <= 0) return out;
        long memSince;
        synchronized (memLock) {
            memSince = memChangesSince;
            for (Iterator<Change> it = mem.descendingIterator(); it.hasNext() && out.size() < max; ) {
                Change c = it.next();
                if (c.atMs() < sinceMs) break;
                if (near(c, dim, x, y, z, r)) out.add(c);
            }
        }
        if (out.size() < max && sinceMs < memSince) {
            List<Change> older = new ArrayList<>();
            readDays("changes-", sinceMs, memSince, line -> {
                Change c = parseChange(line);
                if (c != null && c.atMs() >= sinceMs && c.atMs() < memSince && near(c, dim, x, y, z, r)) older.add(c);
            });
            older.sort(Comparator.comparingLong(Change::atMs).reversed());
            for (Change c : older) {
                if (out.size() >= max) break;
                out.add(c);
            }
        }
        return out;
    }

    /** The changes inside the box with afterMs < atMs <= untilMs, oldest first. */
    public List<Change> changesIn(String dim, int x1, int y1, int z1, int x2, int y2, int z2, long afterMs, long untilMs) {
        List<Change> out = new ArrayList<>();
        long memSince;
        List<Change> recent = new ArrayList<>();
        synchronized (memLock) {
            memSince = memChangesSince;
            for (Change c : mem) {
                if (c.atMs() > afterMs && c.atMs() <= untilMs && inBox(c, dim, x1, y1, z1, x2, y2, z2)) recent.add(c);
            }
        }
        if (afterMs < memSince) {
            readDays("changes-", afterMs, Math.min(memSince, untilMs + 1), line -> {
                Change c = parseChange(line);
                if (c != null && c.atMs() > afterMs && c.atMs() <= untilMs && c.atMs() < memSince && inBox(c, dim, x1, y1, z1, x2, y2, z2)) out.add(c);
            });
            out.sort(Comparator.comparingLong(Change::atMs));
        }
        out.addAll(recent);
        return out;
    }

    private static boolean inBox(Change c, String dim, int x1, int y1, int z1, int x2, int y2, int z2) {
        return c.dim().equals(dim) && c.x() >= x1 && c.x() <= x2 && c.y() >= y1 && c.y() <= y2 && c.z() >= z1 && c.z() <= z2;
    }

    /** The trail since sinceMs, oldest first, at most max (the newest max when there are more). */
    public List<TrailPoint> trail(long sinceMs, int max) {
        ArrayDeque<TrailPoint> keep = new ArrayDeque<>();
        if (max <= 0) return List.of();
        long memSince;
        List<TrailPoint> recent = new ArrayList<>();
        synchronized (memLock) {
            memSince = memTrailSince;
            for (Iterator<TrailPoint> it = memTrail.descendingIterator(); it.hasNext() && recent.size() < max; ) {
                TrailPoint p = it.next();
                if (p.atMs() < sinceMs) break;
                recent.add(p);
            }
        }
        if (recent.size() < max && sinceMs < memSince) {
            List<TrailPoint> older = new ArrayList<>();
            readDays("trail-", sinceMs, memSince, line -> {
                TrailPoint p = parseTrail(line);
                if (p != null && p.atMs() >= sinceMs && p.atMs() < memSince) older.add(p);
            });
            older.sort(Comparator.comparingLong(TrailPoint::atMs));
            for (TrailPoint p : older) {
                keep.addLast(p);
                if (keep.size() > max - recent.size()) keep.removeFirst();
            }
        }
        List<TrailPoint> out = new ArrayList<>(keep);
        for (int i = recent.size() - 1; i >= 0; i--) out.add(recent.get(i));
        return out;
    }

    /** Calls each line of the day files (prefix-YYYY-MM-DD.log) whose day lies between the two times (local days). */
    private void readDays(String prefix, long fromMs, long toMs, java.util.function.Consumer<String> each) {
        if (!Files.isDirectory(root)) return;
        String from = fromMs <= 0 ? "0000-00-00" : day(fromMs), to = day(Math.max(fromMs, toMs));
        List<Path> days = new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (!n.startsWith(prefix) || !n.endsWith(".log")) return;
                String d = n.substring(prefix.length(), n.length() - 4);
                if (d.compareTo(from) >= 0 && d.compareTo(to) <= 0) days.add(p);
            });
        } catch (IOException e) {
            return;
        }
        days.sort(Comparator.comparing(p -> p.getFileName().toString()));
        int[] n = {0};
        for (Path p : days) {
            try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                for (String line; (line = r.readLine()) != null; ) {
                    if (++n[0] > MAX_READ) return;
                    each.accept(line);
                }
            } catch (IOException | RuntimeException ignored) {}
        }
    }

    // ---- baselines ----

    static String dimFolder(String dim) { return dim.replace(':', '_').replaceAll("[^A-Za-z0-9_.-]", "_"); }

    String baseRel(String dim, int cx, int cz) { return "base/" + dimFolder(dim) + "/" + cx + "." + cz + ".gz"; }

    static String key(String dim, int cx, int cz) { return dim + "|" + cx + "|" + cz; }

    /** Writes a baseline (replacing the chunk's older one). Background thread. */
    public void writeBase(ChunkBase b) {
        String rel = baseRel(b.dim, b.cx, b.cz);
        Path target = root.resolve(rel);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream out = Files.newOutputStream(tmp)) {
                b.write(out);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            synchronized (baseCache) {
                baseCache.put(rel, b);
                trimCache();
            }
        } catch (IOException e) {
            lastError = "baseline: " + e.getMessage();
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    private void trimCache() {
        while (baseCache.size() > BASE_CACHE) {
            Iterator<String> it = baseCache.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    /** The chunk's baseline, or null when there is none. */
    public ChunkBase base(String dim, int cx, int cz) {
        String rel = baseRel(dim, cx, cz);
        synchronized (baseCache) {
            ChunkBase b = baseCache.get(rel);
            if (b != null) return b;
        }
        Path p = root.resolve(rel);
        if (!Files.isRegularFile(p)) return null;
        try (InputStream in = Files.newInputStream(p)) {
            ChunkBase b = ChunkBase.read(in);
            synchronized (baseCache) {
                baseCache.put(rel, b);
                trimCache();
            }
            return b;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** How many chunk baselines were on disk at the last {@link #maintain} (-1 before the first). */
    public int baseCount() { return baseFiles; }

    /** How many incidents were on disk at the last {@link #maintain} (-1 before the first). */
    public int incidentsOnDisk() { return incidentsOnDisk; }

    /** The recorder knew the chunk exactly from sinceMs (its first baseline of this stay) until now. */
    public void covered(String dim, int cx, int cz, long sinceMs, long nowMs) {
        coverage.put(key(dim, cx, cz), new long[]{sinceMs, nowMs});
    }

    /** The chunk is still loaded and watched at nowMs. */
    public void seen(String dim, int cx, int cz, long nowMs) {
        long[] c = coverage.get(key(dim, cx, cz));
        if (c != null) c[1] = nowMs;
    }

    /** Coverage start of a chunk still watched, else -1 (for keeping it across a fresh baseline). */
    public long coveredSince(String dim, int cx, int cz) {
        long[] c = coverage.get(key(dim, cx, cz));
        return c == null ? -1 : c[0];
    }

    public void forgetCoverage(String dim, int cx, int cz) { coverage.remove(key(dim, cx, cz)); }

    /**
     * The box as it was at atMs: each chunk's baseline, moved forward (changes after it, up to atMs) or back (changes
     * between atMs and it, undone) with the changes heard. Null when nothing is known or the box is over 4096 cells.
     */
    public Slice blocksAt(String dim, int x1, int y1, int z1, int x2, int y2, int z2, long atMs) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), ay = Math.min(y1, y2), by = Math.max(y1, y2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        long dx = bx - ax + 1L, dy = by - ay + 1L, dz = bz - az + 1L;
        if (dx * dy * dz > 4096) return null;
        String[] ids = new String[(int) (dx * dy * dz)];
        Map<Long, ChunkBase> bases = new java.util.HashMap<>();
        boolean exact = true, noBase = false;
        long minBase = Long.MAX_VALUE, maxBase = Long.MIN_VALUE;
        boolean rewound = false;
        for (int cx = ax >> 4; cx <= bx >> 4; cx++) {
            for (int cz = az >> 4; cz <= bz >> 4; cz++) {
                ChunkBase b = base(dim, cx, cz);
                long[] cov = coverage.get(key(dim, cx, cz));
                if (cov == null || atMs < cov[0] || atMs > cov[1] + SEEN_SLACK_MS || b == null) exact = false;
                if (b == null) { noBase = true; continue; }
                bases.put(((long) cx << 32) ^ (cz & 0xffffffffL), b);
                minBase = Math.min(minBase, b.takenMs);
                maxBase = Math.max(maxBase, b.takenMs);
                if (atMs < b.takenMs) rewound = true;
            }
        }
        for (int y = ay; y <= by; y++) for (int z = az; z <= bz; z++) for (int x = ax; x <= bx; x++) {
            ChunkBase b = bases.get(((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL));
            if (b != null) ids[(int) (((y - ay) * dz + (z - az)) * dx + (x - ax))] = b.id(x, y, z);
        }
        long after = noBase ? -1 : Math.min(atMs, minBase), until = Math.max(atMs, maxBase == Long.MIN_VALUE ? atMs : maxBase);
        List<Change> cs = changesIn(dim, ax, ay, az, bx, by, bz, after, until);
        // forward: chunks whose baseline is older than atMs (or none), changes after the baseline up to atMs
        for (Change c : cs) {
            ChunkBase b = bases.get(((long) (c.x() >> 4) << 32) ^ ((c.z() >> 4) & 0xffffffffL));
            long t = b == null ? Long.MIN_VALUE : b.takenMs;
            if (atMs >= t && c.atMs() > t && c.atMs() <= atMs) ids[idx(c, ax, ay, az, dx, dz)] = bare(c.to());
        }
        // back: chunks whose baseline is newer than atMs, changes after atMs up to the baseline, undone newest first
        for (int i = cs.size() - 1; i >= 0; i--) {
            Change c = cs.get(i);
            ChunkBase b = bases.get(((long) (c.x() >> 4) << 32) ^ ((c.z() >> 4) & 0xffffffffL));
            if (b == null || atMs >= b.takenMs) continue;
            if (c.atMs() > atMs && c.atMs() <= b.takenMs) ids[idx(c, ax, ay, az, dx, dz)] = bare(c.from());
        }
        int unknown = 0;
        for (String s : ids) if (s == null) unknown++;
        if (unknown == ids.length) return null;
        String note;
        if (exact && unknown == 0) note = "exact";
        else {
            StringBuilder n = new StringBuilder();
            if (minBase != Long.MAX_VALUE) {
                n.append("from the baseline of ").append(stamp(minBase));
                if (maxBase != minBase) n.append(" .. ").append(stamp(maxBase));
                n.append(rewound ? ", rewound with the changes heard" : ", plus the changes heard after it");
                n.append("; anything changed while the bot was away is unknown");
            } else n.append("no baseline, only the changes heard");
            if (unknown > 0) n.append("; ").append(unknown).append(" cells unknown");
            note = n.toString();
        }
        return new Slice(ax, ay, az, bx, by, bz, ids, note);
    }

    private static int idx(Change c, int ax, int ay, int az, long dx, long dz) {
        return (int) (((c.y() - ay) * dz + (c.z() - az)) * dx + (c.x() - ax));
    }

    /** "minecraft:furnace[lit=true]" -> "minecraft:furnace". */
    static String bare(String id) {
        if (id == null) return null;
        int i = id.indexOf('[');
        return i < 0 ? id : id.substring(0, i);
    }

    String stamp(long ms) {
        String fmt = day(ms).equals(day(clock.getAsLong())) ? "HH:mm:ss" : "MM-dd HH:mm:ss";
        return DateTimeFormatter.ofPattern(fmt, Locale.ROOT).format(Instant.ofEpochMilli(ms).atZone(zone));
    }

    // ---- incidents ----

    /** Writes one incident (gzip text; its second line is "reason: ..."). Background thread. Returns its file name. */
    public String writeIncident(long atMs, String reason, String text) {
        Path dir = root.resolve("incidents");
        String name = "inc-" + atMs + ".txt.gz";
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(name);
            for (int i = 1; Files.exists(target) && i < 100; i++) {
                name = "inc-" + (atMs + i) + ".txt.gz";
                target = dir.resolve(name);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
                gz.write(text.getBytes(StandardCharsets.UTF_8));
            }
            Files.write(target, bytes.toByteArray());
            incidentReasons.put(name, reason == null ? "" : reason);
            return name;
        } catch (IOException e) {
            lastError = "incident: " + e.getMessage();
            return null;
        }
    }

    private List<Path> incidentFiles() {
        Path dir = root.resolve("incidents");
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().matches("^inc-\\d+\\.txt\\.gz$")).forEach(out::add);
        } catch (IOException e) {
            return out;
        }
        out.sort(Comparator.comparingLong(RecStore::incidentMs));
        return out;
    }

    private static long incidentMs(Path p) {
        String n = p.getFileName().toString();
        try {
            return Long.parseLong(n.substring(4, n.length() - 7));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The kept incidents, newest first, at most max (n = 1 is the oldest kept). */
    public List<Incident> incidents(int max) {
        List<Path> files = incidentFiles();
        List<Incident> out = new ArrayList<>();
        for (int i = files.size() - 1; i >= 0 && out.size() < max; i--) {
            Path p = files.get(i);
            String name = p.getFileName().toString();
            String reason = incidentReasons.computeIfAbsent(name, k -> readReason(p));
            out.add(new Incident(i + 1, incidentMs(p), reason, name));
        }
        return out;
    }

    public int incidentCount() { return incidentFiles().size(); }

    private static String readReason(Path p) {
        String t = readGz(p, 4096);
        if (t == null) return "";
        for (String line : t.split("\n")) if (line.startsWith("reason: ")) return line.substring(8);
        return "";
    }

    /** Incident n's text, or null when there is no incident n. */
    public String incident(int n) {
        List<Path> files = incidentFiles();
        if (n < 1 || n > files.size()) return null;
        return readGz(files.get(n - 1), 4 << 20);
    }

    private static String readGz(Path p, int maxBytes) {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(p))) {
            byte[] b = in.readNBytes(maxBytes);
            return new String(b, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    // ---- settings ----

    public String readSettings() {
        Path p = root.resolve("settings.json");
        try {
            return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    public void writeSettings(String json) {
        Path target = root.resolve("settings.json"), tmp = root.resolve("settings.json.tmp");
        try {
            Files.createDirectories(root);
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            lastError = "settings: " + e.getMessage();
        }
    }

    /** Today's counts from memory (changes, trail points), for the summary. */
    public long[] memCounts() {
        synchronized (memLock) {
            return new long[]{mem.size(), memTrail.size()};
        }
    }

    public long writtenLines() { return written.get(); }
}
