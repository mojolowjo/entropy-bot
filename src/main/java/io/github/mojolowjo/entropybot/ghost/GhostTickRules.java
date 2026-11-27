package io.github.mojolowjo.entropybot.ghost;

/**
 * Dry-run package 1: the plain-number pieces of the game's living tick that the ghost copies around {@code travel}, kept
 * here so they are tested without a game. Each one is a copy of vanilla 1.21.1 code (NeoForge 21.1.249 sources), not
 * our own rule:
 * <ul>
 *   <li>{@link #zeroTiny}: {@code LivingEntity.aiStep} sets each speed part under 0.003 to 0 before the move;</li>
 *   <li>{@link #decayDelay} and {@link #jump}: the jump block of {@code LivingEntity.aiStep} with NeoForge's fluid types
 *       (a jump from the ground starts a 10-tick delay; letting go of jump clears it);</li>
 *   <li>{@link #INPUT_SCALE}: the forward and side input are multiplied by 0.98 just before {@code travel}.</li>
 * </ul>
 *
 * <p>Pure (JUnit: GhostTickRulesTest). Loader notes: vanilla {@code LivingEntity.aiStep}; the fluid-type branch is NeoForge's
 * ({@code IEntityExtension.getMaxHeightFluidType}, {@code ILivingEntityExtension.jumpInFluid}). On Fabric the fluid branch
 * is only water and lava.
 */
public final class GhostTickRules {
    private GhostTickRules() {}

    public static final double TINY = 0.003;          // structural: vanilla LivingEntity.aiStep
    public static final float INPUT_SCALE = 0.98f;    // structural: vanilla LivingEntity.aiStep
    public static final int JUMP_DELAY = 10;          // structural: vanilla LivingEntity.aiStep (noJumpDelay)

    /** What the jump block does this tick. */
    public enum Jump { NONE, FROM_GROUND, IN_WATER, IN_LAVA, IN_OTHER_FLUID }

    /** The jump block's outcome: what to do, and the jump delay after it. */
    public record JumpStep(Jump act, int delay) {}

    /** A speed part as the tick leaves it before the move: 0 when its size is under {@link #TINY}. */
    public static double zeroTiny(double v) {
        return Math.abs(v) < TINY ? 0.0 : v;
    }

    /** The jump delay at the start of the tick: one less, never under 0. */
    public static int decayDelay(int delay) {
        return delay > 0 ? delay - 1 : delay;
    }

    /**
     * The jump block of {@code LivingEntity.aiStep}, line for line. {@code fluidHeight} is the height the game reads:
     * of the highest non-air fluid type when {@code maxFluidIsAir} is false, else of lava when in lava, else of water.
     * {@code delay} is the delay after {@link #decayDelay}.
     */
    public static JumpStep jump(boolean jumping, boolean affectedByFluids, boolean maxFluidIsAir, boolean inWater, boolean inLava,
                                boolean onGround, double fluidHeight, double threshold, int delay) {
        if (!(jumping && affectedByFluids)) return new JumpStep(Jump.NONE, 0);
        boolean swimUp = inWater && fluidHeight > 0.0;
        boolean shallow = onGround && !(fluidHeight > threshold);
        if (swimUp && !shallow) return new JumpStep(Jump.IN_WATER, delay);
        if (inLava && !shallow) return new JumpStep(Jump.IN_LAVA, delay);
        if (!maxFluidIsAir && !shallow) return new JumpStep(Jump.IN_OTHER_FLUID, delay);
        if ((onGround || swimUp && fluidHeight <= threshold) && delay == 0) return new JumpStep(Jump.FROM_GROUND, JUMP_DELAY);
        return new JumpStep(Jump.NONE, delay);
    }
}
