package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;

import java.util.HashMap;
import java.util.Map;

/**
 * BRAIN_PLAN 2.2 (B3): a want's boredom rises 0..1 over {@code boredomDays} in-game days (20 min each) since it was
 * last done (or first seen this session). Kept on the blackboard ({@link #FACT}); the brain calls {@link #done} on a
 * want's success. Pure.
 */
public final class Wants {
    private Wants() {}

    public static final String FACT = "aa.wantsDone";
    public static final long DAY_MS = 20 * 60_000;

    @SuppressWarnings("unchecked")
    static Map<String, Long> map(Blackboard bb) {
        if (bb.fact(FACT) instanceof Map<?, ?> m) return (Map<String, Long>) m;
        Map<String, Long> m = new HashMap<>();
        bb.fact(FACT, m);
        return m;
    }

    /** 0..1: the time since last done over {@code days} in-game days. */
    public static double boredom(Blackboard bb, String id, double days) {
        long now = bb.clock.now();
        long since = map(bb).computeIfAbsent(id, k -> now);
        return Math.max(0, Math.min(1, (now - since) / (days * DAY_MS)));
    }

    public static void done(Blackboard bb, String id, long now) { map(bb).put(id, now); }

    /** A want's score: weight x boredom, -1 while under 1 (not bored yet). */
    public static double score(Blackboard bb, String id, String key) {
        Personality p = Personality.of(bb);
        double v = p.get(key) * boredom(bb, id, p.get("boredomDays"));
        return v < 1 ? -1 : v;
    }
}
