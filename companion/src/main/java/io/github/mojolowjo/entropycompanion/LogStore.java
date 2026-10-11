package io.github.mojolowjo.entropycompanion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Companion 0.4.0: the action log's files on the owner's PC, {@code config/entropy-companion/log/<yyyy-mm-dd>.jsonl}
 * (one event a line, a new file each day). Kept 30 days, at most 50 MB in all: the oldest days go first; when today
 * alone is over the cap, new lines are dropped (counted) rather than writing on. One worker thread calls this.
 */
public final class LogStore {
    public static final int KEEP_DAYS = 30;
    /** 0.5.0 (heavy log): 500 MB in all, 300 MB for one day (was 50 MB in all). */
    public static final long CAP_BYTES = 500L * 1024 * 1024, DAY_CAP_BYTES = 300L * 1024 * 1024;
    static final Pattern NAME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}\\.jsonl$");

    final Path dir;
    private final ZoneId zone;
    private final long cap, dayCap;
    private long total = -1, today = -1;
    private String lastCleanDay = "";
    long written, dropped, writeErrors;
    String lastError = "none";

    public LogStore(Path dir, ZoneId zone, long cap) {
        this(dir, zone, cap, cap);
    }

    public LogStore(Path dir, ZoneId zone, long cap, long dayCap) {
        this.dir = dir;
        this.zone = zone;
        this.cap = cap;
        this.dayCap = Math.min(cap, dayCap);
    }

    String day(long now) {
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString();
    }

    /** The day files, oldest first. */
    List<Path> files() {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> NAME.matcher(p.getFileName().toString()).matches()).sorted().forEach(out::add);
        } catch (IOException e) {
            lastError = "list: " + e.getClass().getSimpleName();
        }
        return out;
    }

    long totalBytes() {
        long t = 0;
        for (Path p : files()) {
            try {
                t += Files.size(p);
            } catch (IOException ignored) {}
        }
        return t;
    }

    /** Deletes days older than KEEP_DAYS, then the oldest (never today) while over the cap. */
    void clean(long now) { clean(now, 0); }

    /** The same, making room for {@code need} more bytes. */
    void clean(long now, long need) {
        String today = day(now);
        LocalDate oldest = LocalDate.parse(today).minusDays(KEEP_DAYS - 1);
        List<Path> fs = files();
        for (Path p : new ArrayList<>(fs)) {
            String n = p.getFileName().toString();
            if (LocalDate.parse(n.substring(0, 10)).isBefore(oldest) && delete(p)) fs.remove(p);
        }
        total = totalBytes();
        for (Path p : fs) {
            if (total + need <= cap) break;
            if (p.getFileName().toString().startsWith(today)) continue;
            long sz = size(p);
            if (delete(p)) total -= sz;
        }
        lastCleanDay = today;
        this.today = size(dir.resolve(today + ".jsonl"));
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private boolean delete(Path p) {
        try {
            Files.deleteIfExists(p);
            return true;
        } catch (IOException e) {
            lastError = "delete " + p.getFileName() + ": " + e.getClass().getSimpleName();
            return false;
        }
    }

    /** Appends the lines to today's file. */
    void append(List<String> lines, long now) {
        if (lines.isEmpty()) return;
        if (!day(now).equals(lastCleanDay) || total < 0) clean(now);
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (String l : lines) {
            long add = l.length() + 1L;
            if (today + b.length() + add > dayCap) {           // today alone is full: drop (counted)
                dropped += lines.size() - n;
                break;
            }
            if (total + b.length() + add > cap) {
                clean(now, b.length() + add);
                if (total + b.length() + add > cap) {
                    dropped += lines.size() - n;
                    break;
                }
            }
            b.append(l).append('\n');
            n++;
        }
        if (n == 0) return;
        try {
            Files.createDirectories(dir);
            byte[] bytes = b.toString().getBytes(StandardCharsets.UTF_8);
            Files.write(dir.resolve(day(now) + ".jsonl"), bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            total += bytes.length;
            today += bytes.length;
            written += n;
        } catch (IOException e) {
            writeErrors++;
            dropped += n;
            lastError = "write: " + e.getClass().getSimpleName();
        }
    }

    /** Lines in today's file (for /bot log status). */
    long linesToday(long now) {
        Path f = dir.resolve(day(now) + ".jsonl");
        if (!Files.exists(f)) return 0;
        try (Stream<String> s = Files.lines(f, StandardCharsets.UTF_8)) {
            return s.count();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }
}
