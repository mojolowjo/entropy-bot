package io.github.mojolowjo.entropybot.routewalk;

import java.util.ArrayList;
import java.util.List;

/**
 * The order of {@code route test A B n}: first a walk to A (not measured), then n trips back and forth (A to B, B to
 * A, ...), the mode going goal, legs, plain in turn. With n a multiple of 6 every mode gets as many trips each way.
 */
public final class RouteTestPlan {
    private RouteTestPlan() {
    }

    public static final int MAX_TRIPS = 30;
    public static final List<RouteWalk.Mode> MODES = List.of(RouteWalk.Mode.GOAL, RouteWalk.Mode.LEGS, RouteWalk.Mode.PLAIN);

    /** One trip: its number (1-based), the mode, and whether it goes A to B (else B to A). */
    public record Trip(int n, RouteWalk.Mode mode, boolean aToB) {
    }

    public static List<Trip> trips(int n) {
        List<Trip> out = new ArrayList<>();
        int k = Math.max(1, Math.min(n, MAX_TRIPS));
        for (int i = 0; i < k; i++) out.add(new Trip(i + 1, MODES.get(i % MODES.size()), i % 2 == 0));
        return out;
    }
}
