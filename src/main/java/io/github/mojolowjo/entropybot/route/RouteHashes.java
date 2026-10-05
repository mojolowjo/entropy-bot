package io.github.mojolowjo.entropybot.route;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The 64-bit hashes of the file header (FNV-1a; stable across runs and JVMs, unlike {@code hashCode}). */
public final class RouteHashes {
    private RouteHashes() {
    }

    private static final long OFFSET = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    /**
     * Hash of the cost-relevant Baritone settings, name to value as text (R2 picks the list: e.g. allowSprint,
     * allowParkour, allowDiagonalDescend, allowDiagonalAscend, assumeWalkOnWater, walkOnWaterOnePenalty,
     * jumpPenalty, maxFallHeightNoWater, allowWaterBucketFall, avoidance, blocksToAvoid...). Order does not matter.
     */
    public static long settings(Map<String, String> settings) {
        long h = OFFSET;
        for (Map.Entry<String, String> e : new TreeMap<>(settings).entrySet()) {
            h = mix(h, e.getKey());
            h = mix(h, "=");
            h = mix(h, String.valueOf(e.getValue()));
            h = mix(h, ";");
        }
        return h;
    }

    /** Hash of the owner's areas, each as ints (e.g. {dim, x1, z1, x2, z2, y1, y2}); order matters. */
    public static long areas(List<int[]> areas) {
        long h = OFFSET;
        for (int[] a : areas) {
            for (int v : a) h = mixInt(h, v);
            h = mixInt(h, 0x5eed);
        }
        return h;
    }

    static long mix(long h, String s) {
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xff;
            h *= PRIME;
        }
        return h;
    }

    static long mixInt(long h, int v) {
        for (int i = 0; i < 4; i++) {
            h ^= (v >>> (i * 8)) & 0xff;
            h *= PRIME;
        }
        return h;
    }
}
