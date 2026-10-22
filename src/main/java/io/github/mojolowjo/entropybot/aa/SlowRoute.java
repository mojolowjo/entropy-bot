package io.github.mojolowjo.entropybot.aa;

import java.util.List;

/**
 * AA_STEP3 B: the route slow mode draws in the world (the mod's own renderer, engine/SlowRender): the bot's place and
 * the points the way names, in order. Set by the game thread, read by the render thread (the same thread in the client,
 * volatile anyway). Empty = nothing drawn. Pure; loader notes: none (the drawing is the renderer's).
 */
public final class SlowRoute {
    private SlowRoute() {}

    public static volatile List<int[]> points = List.of();
    /** True while the bot waits for go (drawn bright), false while the way runs (dim). */
    public static volatile boolean waiting;

    public static void clear() {
        points = List.of();
        waiting = false;
    }
}
