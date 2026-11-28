package io.github.mojolowjo.entropybot.nav.steer;

/**
 * Ghost steering S1 (plan to-do/nav-steer.md, m0j 2026-11-27): a body the steerer can try key choices on. The live one
 * (S2) is the ghost player stepped through the game's own movement code; the tests use a small fake physics. The steerer
 * calls {@link #reset} before each try, so every option starts from the real body as it is now.
 *
 * <p>Pure (JUnit: SteererTest, through FakeWaterSim). Loader notes: none; the live side wraps {@code GhostPlayer}.
 */
public interface Sim {
    /** Copies the real body's state in again (position, speed, air, flags). */
    void reset();

    /** One game tick with these keys, the body facing {@code yaw} degrees (the game's convention: 0 = south, +z). */
    void step(Choice c, float yaw);

    double x();
    double y();
    double z();

    /** True when something since the last reset hurt or would hurt the body: lava, fire, a fall over the safe height, drowning. */
    boolean hurt();

    /** Air left, in the game's units (300 = full lungs). */
    int air();
}
