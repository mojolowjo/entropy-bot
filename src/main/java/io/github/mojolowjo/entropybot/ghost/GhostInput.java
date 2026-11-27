package io.github.mojolowjo.entropybot.ghost;

/**
 * Dry-run package 1 (plan "Dry-run checker plan (ghost body)", 2026-11-26): what the ghost is told to do for one game
 * tick, in the numbers the game's own movement code reads. {@code forward} and {@code left} are the final impulses
 * (-1..1, already scaled for sneaking and item use, as {@code Input.forwardImpulse / leftImpulse} hold them after
 * {@code LocalPlayer.aiStep}); {@code jump}, {@code sprint} and {@code shift} are the held keys and the sprint state;
 * {@code yaw} and {@code pitch} are the body's facing in degrees for that tick's move, {@code jumpYaw} the facing its jump
 * used (Baritone's mixin can give the jump and the move different yaws in one tick; review of package 1).
 *
 * <p>Pure (JUnit: ShadowRunTest). Loader notes: none; the game side fills it from {@code LocalPlayer.input} (vanilla).
 */
public record GhostInput(float forward, float left, boolean jump, boolean sprint, boolean shift, float yaw, float pitch, float jumpYaw) {
    /** The jump faces the same way as the move. */
    public GhostInput(float forward, float left, boolean jump, boolean sprint, boolean shift, float yaw, float pitch) {
        this(forward, left, jump, sprint, shift, yaw, pitch, yaw);
    }

    /** Standing still, facing the given way. */
    public static GhostInput still(float yaw, float pitch) {
        return new GhostInput(0f, 0f, false, false, false, yaw, pitch);
    }
}
