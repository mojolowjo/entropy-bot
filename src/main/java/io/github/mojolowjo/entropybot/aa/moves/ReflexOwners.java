package io.github.mojolowjo.entropybot.aa.moves;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 0.39.s6 review: who owns Baritone's breaking/placing for a reflex now, one token per car with its last heartbeat
 * (ms). One car's restore never ends another's ownership; a car dropped without stop()/fresh() stops beating, its
 * ownership goes stale after {@code moves.ownerStaleS} (Tuning) and the SafetyNet then puts the settings back. Pure
 * (JUnit: ReflexOwnerTest). Loader notes: none.
 */
public final class ReflexOwners {
    private static final Map<Object, Long> OWNERS = new ConcurrentHashMap<>();

    private ReflexOwners() { }

    public static void beat(Object owner, long nowMs) { OWNERS.put(owner, nowMs); }

    public static void release(Object owner) { OWNERS.remove(owner); }

    public static boolean any(long nowMs) {
        long stale = (long) (1000 * io.github.mojolowjo.entropybot.aa.brain.Tuning.get("moves.ownerStaleS"));
        OWNERS.values().removeIf(t -> nowMs - t > stale);
        return !OWNERS.isEmpty();
    }
}
