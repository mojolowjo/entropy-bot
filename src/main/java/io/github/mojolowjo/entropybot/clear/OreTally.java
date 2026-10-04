package io.github.mojolowjo.entropybot.clear;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * B7d D1: the bridge's oreTally: ore block id -> mined this session by every collecting clear (each clear adds its own
 * {@link ClearJob#oreTally} when it ends). "mine ... until N ores" keeps a copy at its start and counts {@link #since}.
 */
public final class OreTally {
    private OreTally() {}

    private static final Map<String, Integer> TALLY = new LinkedHashMap<>();

    public static synchronized Map<String, Integer> copy() {
        return new LinkedHashMap<>(TALLY);
    }

    public static synchronized void add(Map<String, Integer> mined) {
        mined.forEach((k, v) -> TALLY.merge(k, v, Integer::sum));
    }

    /** talliedSince: ores matching {@code match} mined since {@code start} (a {@link #copy()}). */
    public static int since(Map<String, Integer> start, Predicate<String> match) {
        int n = 0;
        for (Map.Entry<String, Integer> e : copy().entrySet()) {
            if (match.test(e.getKey())) n += e.getValue() - start.getOrDefault(e.getKey(), 0);
        }
        return n;
    }
}
