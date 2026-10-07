package io.github.mojolowjo.entropybot.routing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * chunks-0.23.5: the companion's surface chunks, as the route map sees them. The dashboard writes them into the bot's
 * own surface folder ({@code entropybot\surface\minecraft_overworld\<cx>.<cz>.json}, with {@code "src":"companion"});
 * {@link #poll} (RouteRuntime's io thread, every {@link #POLL_MS}) reads the files whose time changed, keeps the
 * companion's ones in memory ({@link #chunk}, any thread) and queues each new or newer chunk as an arrival, which the game
 * thread hands to {@link RouteScheduler#surfaceArrived} (the idle queue). The bot's own files are skipped: its boxes
 * come from the live level. A file that went away, or turned into the bot's own, is forgotten. At most
 * {@link #MAX_CHUNKS} chunks are held; past that the oldest scans are dropped.
 *
 * <p>Plain Java (JUnit on a temp folder). Errors: an unreadable or malformed file is counted and skipped (read again when
 * its time changes); a failing folder listing is counted and logged by the caller once; nothing throws out of poll.
 */
public final class SurfaceInbox {
    public static final long POLL_MS = 5000;
    public static final int MAX_CHUNKS = 4096;

    /** A chunk that arrived or got newer data. */
    public record Arrival(int cx, int cz, long t) {
    }

    private final Path dir;
    private final Map<Long, SurfaceChunk> chunks = new ConcurrentHashMap<>();
    private final Map<Path, Long> seen = new HashMap<>();        // poll thread only
    private final ConcurrentLinkedQueue<Arrival> arrivals = new ConcurrentLinkedQueue<>();
    public final AtomicLong reads = new AtomicLong(), bad = new AtomicLong(), arrived = new AtomicLong(), dropped = new AtomicLong(),
            listErrors = new AtomicLong();
    private volatile long lastPollMs;
    private volatile String lastError;

    public SurfaceInbox(Path dir) {
        this.dir = dir;
    }

    public static long key(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    /** The companion's chunk at cx cz, or null. Any thread. */
    public SurfaceChunk chunk(int cx, int cz) { return chunks.get(key(cx, cz)); }

    public int size() { return chunks.size(); }

    public long lastPollMs() { return lastPollMs; }

    public String lastError() { return lastError; }

    /** The next arrival, or null. Game thread. */
    public Arrival nextArrival() { return arrivals.poll(); }

    public int pendingArrivals() { return arrivals.size(); }

    /** One look at the folder. One thread at a time (the io thread). Never throws. */
    public void poll(long nowMs) {
        lastPollMs = nowMs;
        try {
            if (!Files.isDirectory(dir)) {
                if (!chunks.isEmpty()) chunks.clear();
                seen.clear();
                return;
            }
            Set<Path> present = new HashSet<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
                for (Path p : ds) {
                    present.add(p);
                    long mtime;
                    try {
                        mtime = Files.getLastModifiedTime(p).toMillis();
                    } catch (IOException e) {
                        continue;                                   // replaced just now: next poll
                    }
                    Long before = seen.get(p);
                    if (before != null && before == mtime) continue;
                    seen.put(p, mtime);
                    read(p);
                }
            }
            for (Path p : new ArrayList<>(seen.keySet())) {
                if (present.contains(p)) continue;
                seen.remove(p);
                int[] c = coords(p);
                if (c != null) chunks.remove(key(c[0], c[1]));
            }
            trim();
        } catch (Throwable t) {
            listErrors.incrementAndGet();
            lastError = "list " + dir.getFileName() + ": " + t;
        }
    }

    private void read(Path p) {
        int[] c = coords(p);
        if (c == null) return;
        String text;
        try {
            text = Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            bad.incrementAndGet();
            lastError = "read " + p.getFileName() + ": " + e;
            seen.remove(p);                                         // try again next poll
            return;
        }
        reads.incrementAndGet();
        SurfaceChunk s = SurfaceChunk.parse(text);
        long k = key(c[0], c[1]);
        if (s == null || s.cx() != c[0] || s.cz() != c[1]) {
            bad.incrementAndGet();
            chunks.remove(k);
            return;
        }
        if (!s.companion()) {                                       // the bot's own: its boxes come from the level
            chunks.remove(k);
            return;
        }
        SurfaceChunk old = chunks.get(k);
        if (old != null && old.t() >= s.t()) return;                // newer wins
        chunks.put(k, s);
        arrived.incrementAndGet();
        arrivals.add(new Arrival(s.cx(), s.cz(), s.t()));
    }

    private void trim() {
        int over = chunks.size() - MAX_CHUNKS;
        if (over <= 0) return;
        List<SurfaceChunk> all = new ArrayList<>(chunks.values());
        all.sort((a, b) -> Long.compare(a.t(), b.t()));
        for (int i = 0; i < over; i++) {
            chunks.remove(key(all.get(i).cx(), all.get(i).cz()));
            dropped.incrementAndGet();
        }
    }

    static int[] coords(Path p) {
        String n = p.getFileName().toString();
        if (!n.endsWith(".json")) return null;
        String[] parts = n.substring(0, n.length() - 5).split("\\.");
        if (parts.length != 2) return null;
        try {
            return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String line(long nowMs) {
        return "companion chunks " + chunks.size() + " held (" + arrived.get() + " arrived, " + reads.get() + " files read, "
                + bad.get() + " bad, " + dropped.get() + " dropped over " + MAX_CHUNKS + ", " + arrivals.size() + " queued)"
                + ", last look " + (lastPollMs == 0 ? "never" : (nowMs - lastPollMs) / 1000 + " s ago")
                + (lastError == null ? "" : ", last error " + lastError);
    }
}
