package io.github.mojolowjo.entropybot.clear;

/** Where the bot is: its feet (the player's x, y, z) and its eye height (getEyeY). */
public record Bot(double x, double y, double z, double eyeY) {
    public static final double EYE = 1.62;

    /** Standing (not crouching) at x y z. */
    public static Bot at(double x, double y, double z) {
        return new Bot(x, y, z, y + EYE);
    }
}
