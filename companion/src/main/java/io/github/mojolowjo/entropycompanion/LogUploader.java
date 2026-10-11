package io.github.mojolowjo.entropycompanion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

/**
 * Companion 0.4.0: sends the action log's files to the dashboard ({@code POST /api/ownerlog}, gzip, NDJSON). The files
 * are the queue: a cursor ({@code .cursor}: file name and byte offset) says what was sent; a post that fails leaves it
 * where it is, so events written while the dashboard was away go out later, after a restart too (the dashboard drops
 * repeats by session and seq). Every 10 s while posts work; after 3 failures every 30 s ({@link Backoff}).
 * One worker thread calls {@link #step}.
 */
public final class LogUploader {
    public static final long INTERVAL_MS = 10_000;
    /** 0.5.0: up to 16 MB of lines read a post, gzipped to at most GZIP_MAX (halved until it fits); was 512 KB / 2000 lines. */
    public static final int BATCH_BYTES = 16 * 1024 * 1024, BATCH_LINES = 50_000, GZIP_MAX = 2 * 1024 * 1024;

    /** The post: the gzipped body; returns the HTTP status (throws when unreachable). */
    public interface Poster {
        int post(byte[] gzippedNdjson) throws IOException;
    }

    private final LogStore store;
    private final Path cursorFile;
    private final Backoff backoff = new Backoff();
    private final Consumer<String> notice;
    private long nextAt;
    String cursorName = "";
    long cursorOffset;
    private boolean cursorLoaded;
    long posts, failed, sentLines, lastOkAt, lastPostBytes;
    String lastError = "none";

    public LogUploader(LogStore store, Consumer<String> notice) {
        this.store = store;
        this.cursorFile = store.dir.resolve(".cursor");
        this.notice = notice;
    }

    boolean failing() { return backoff.failing(); }

    private void loadCursor() {
        cursorLoaded = true;
        try {
            if (Files.exists(cursorFile)) {
                String[] w = Files.readString(cursorFile, StandardCharsets.UTF_8).trim().split("\\s+");
                if (w.length == 2 && LogStore.NAME.matcher(w[0]).matches()) {
                    cursorName = w[0];
                    cursorOffset = Long.parseLong(w[1]);
                }
            }
        } catch (IOException | RuntimeException e) {
            lastError = "cursor: " + e.getClass().getSimpleName();
        }
    }

    private void saveCursor() {
        try {
            Files.createDirectories(store.dir);
            Path tmp = cursorFile.resolveSibling(".cursor.tmp");
            Files.writeString(tmp, cursorName + " " + cursorOffset + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, cursorFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            lastError = "cursor: " + e.getClass().getSimpleName();
        }
    }

    /** The next unsent lines (complete lines only) and the cursor after them, or null when everything is sent. */
    record Batch(String name, long start, long end, byte[] ndjson, int lines) {}

    Batch next() throws IOException {
        if (!cursorLoaded) loadCursor();
        List<Path> files = store.files();
        for (Path f : files) {
            String n = f.getFileName().toString();
            if (n.compareTo(cursorName) < 0) continue;
            long start = n.equals(cursorName) ? cursorOffset : 0;
            long size = Files.size(f);
            if (start > size) start = 0;                 // the file was replaced: send it again (dupes are dropped)
            if (start >= size) continue;
            byte[] buf = new byte[(int) Math.min(BATCH_BYTES, size - start)];
            try (RandomAccessFile r = new RandomAccessFile(f.toFile(), "r")) {
                r.seek(start);
                r.readFully(buf);
            }
            int end = -1, lines = 0;
            for (int i = 0; i < buf.length && lines < BATCH_LINES; i++) {
                if (buf[i] == '\n') {
                    end = i + 1;
                    lines++;
                }
            }
            if (end < 0) {
                if (buf.length == BATCH_BYTES) {        // one line over the batch size: skip it
                    cursorName = n;
                    cursorOffset = start + buf.length;
                    saveCursor();
                }
                continue;                               // a half-written last line: wait for its end
            }
            byte[] body = java.util.Arrays.copyOf(buf, end);
            return new Batch(n, start, start + end, body, lines);
        }
        return null;
    }

    /** Called every couple of seconds; posts when due. */
    void step(long now, boolean active, Poster poster) {
        if (!active || now < nextAt) return;
        nextAt = now + backoff.delayMs(INTERVAL_MS);
        Batch b;
        try {
            b = next();
        } catch (IOException e) {
            lastError = "read: " + e.getClass().getSimpleName();
            return;
        }
        if (b == null) return;
        String err;
        try {
            byte[] gz = gzip(b.ndjson());
            while (gz.length > GZIP_MAX && b.lines() > 1) {      // too big packed: send the first half of the lines
                b = half(b);
                gz = gzip(b.ndjson());
            }
            lastPostBytes = gz.length;
            int status = poster.post(gz);
            err = status == 200 ? null : status == 403 ? "the dashboard refused the key (HTTP 403)" : "HTTP " + status;
        } catch (IOException | RuntimeException e) {
            err = "the dashboard is unreachable (" + e.getClass().getSimpleName() + ")";
        }
        posts++;
        if (err == null) {
            cursorName = b.name();
            cursorOffset = b.end();
            saveCursor();
            sentLines += b.lines();
            lastOkAt = now;
            boolean was = backoff.failing();
            backoff.success();
            if (was) notice.accept("action log: dashboard reachable again, sending what was kept");
            nextAt = now + INTERVAL_MS;
        } else {
            failed++;
            lastError = err;
            if (backoff.failure()) notice.accept("action log: " + err + " - keeping events on this PC, sending later");
            nextAt = now + backoff.delayMs(INTERVAL_MS);
        }
    }

    /** Bytes not sent yet. */
    long queuedBytes() {
        if (!cursorLoaded) loadCursor();
        long q = 0;
        for (Path f : store.files()) {
            String n = f.getFileName().toString();
            if (n.compareTo(cursorName) < 0) continue;
            try {
                q += Files.size(f) - (n.equals(cursorName) ? Math.min(cursorOffset, Files.size(f)) : 0);
            } catch (IOException ignored) {}
        }
        return q;
    }

    /** The batch cut after half its lines (the cursor then moves only that far). */
    static Batch half(Batch b) {
        int want = b.lines() / 2, seen = 0;
        byte[] d = b.ndjson();
        for (int i = 0; i < d.length; i++) {
            if (d[i] == '\n' && ++seen == want) {
                return new Batch(b.name(), b.start(), b.start() + i + 1, java.util.Arrays.copyOf(d, i + 1), want);
            }
        }
        return b;
    }

    static byte[] gzip(byte[] b) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length / 4 + 64);
        try (GZIPOutputStream g = new GZIPOutputStream(out)) {
            g.write(b);
        }
        return out.toByteArray();
    }
}
