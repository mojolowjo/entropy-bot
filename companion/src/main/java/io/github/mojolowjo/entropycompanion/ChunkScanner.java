package io.github.mojolowjo.entropycompanion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.zip.GZIPOutputStream;

/**
 * Companion 0.3.0 (chunks-0.23.5): the scan step and the post body, as plain Java (JUnit on a fake world). Each client
 * tick {@link #step} scans the due chunks of {@link ChunkScanQueue} until {@link #BUDGET_NS} (1 ms) is used (at least
 * one), each with {@link ChunkColumns#scan} (the bot's surface rule, copied), and offers the v2 JSON plus
 * {@code "src":"companion"} to the {@link ChunkBatcher}. Only blocks are read: nothing about other players.
 */
public final class ChunkScanner {
    public static final long BUDGET_NS = 1_000_000;
    public static final int MAX_PER_TICK = 8;

    /** The owner's loaded world, as the scan sees it (the game side reads the ClientLevel). */
    public interface World {
        /** The dimension id, e.g. minecraft:overworld. */
        String dim();

        boolean loaded(int cx, int cz);

        int minY();

        ChunkColumns.Source source();
    }

    private ChunkScanner() {}

    /** One tick's scanning. Returns the number scanned. Never throws (a failing chunk is counted by errs and skipped). */
    public static int step(ChunkScanQueue queue, World world, ChunkBatcher batcher, long now, LongSupplier nanos,
                           ChunkColumns.ErrorSink errs) {
        List<Long> next = queue.take(now, MAX_PER_TICK);
        long t0 = nanos.getAsLong();
        int done = 0;
        String dim = world.dim();
        for (int i = 0; i < next.size(); i++) {
            long k = next.get(i);
            if (i > 0 && nanos.getAsLong() - t0 > BUDGET_NS) {
                queue.again(k, now);                                 // over budget: next tick
                continue;
            }
            int cx = ChunkScanQueue.cx(k), cz = ChunkScanQueue.cz(k);
            try {
                if (!world.loaded(cx, cz)) continue;
                ChunkColumns cols = ChunkColumns.scan(world.source(), cx, cz, world.minY(), errs);
                batcher.offer(dim + " " + cx + " " + cz, cols.toJson(dim, cx, cz, now), hash(cols));
                done++;
            } catch (RuntimeException e) {
                if (errs != null) errs.error(cx << 4, cz << 4, e);
            }
        }
        return done;
    }

    /** The columns' hash (not the time): an unchanged chunk hashes the same. */
    public static long hash(ChunkColumns c) {
        long h = 1125899906842597L;
        for (int[] a : new int[][] {c.g, c.f, c.c, c.l, c.u, c.g2, c.f2, c.u2})
            for (int v : a) h = 31 * h + v;
        return h;
    }

    /** The post body: {"chunks":[...]}. */
    public static String body(List<ChunkBatcher.Item> batch) {
        StringBuilder sb = new StringBuilder(batch.size() * 6200 + 16).append("{\"chunks\":[");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(batch.get(i).json());
        }
        return sb.append("]}").toString();
    }

    public static byte[] gzip(String text) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(text.length() / 4 + 64);
            try (GZIPOutputStream gz = new GZIPOutputStream(bo)) {
                gz.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return bo.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);              // a byte array stream doesn't fail
        }
    }

    /** What an HTTP answer means for the batch: null = taken, else the reason (the key never appears in it). */
    public static String verdict(int status) {
        if (status >= 200 && status < 300) return null;
        if (status == 403) return "the dashboard refused the chunks (403): check the key";
        if (status == 404) return "the dashboard has no /api/chunks (restart it after the update)";
        if (status == 413) return "the dashboard said the post was too big (413)";
        return "the dashboard answered HTTP " + status;
    }
}
