package io.github.mojolowjo.entropybot.nav.steer;

import java.util.List;

/**
 * Ghost steering S1: one key choice the steerer can try and hold. {@code forward} and {@code left} are the impulses
 * (-1, 0 or 1), {@code turn} is degrees added to the facing toward the target (so "forward" always means "toward it"),
 * and the three keys are held for the whole try.
 *
 * <p>Pure (JUnit: SteererTest). Loader notes: none; S2 turns it into the driver's keys.
 */
public record Choice(String name, int forward, int left, boolean jump, boolean sprint, boolean sneak, float turn) {
    // structural: the turn of the side options; a 15-tick try covers about a block sideways at walking speed
    static final float SIDE = 30f;

    /** The options the steerer tries each time (plan S1): the plain swim and walk choices a player would use. */
    public static final List<Choice> OPTIONS = List.of(
            new Choice("forward", 1, 0, false, false, false, 0f),
            new Choice("forward jump", 1, 0, true, false, false, 0f),
            new Choice("sprint jump", 1, 0, true, true, false, 0f),
            new Choice("left jump", 1, 0, true, false, false, -SIDE),
            new Choice("right jump", 1, 0, true, false, false, SIDE),
            new Choice("jump", 0, 0, true, false, false, 0f),
            new Choice("sneak", 0, 0, false, false, true, 0f),
            new Choice("back", -1, 0, false, false, false, 0f));

    /** Standing still: what the body does before the first try. */
    public static final Choice STILL = new Choice("still", 0, 0, false, false, false, 0f);
}
