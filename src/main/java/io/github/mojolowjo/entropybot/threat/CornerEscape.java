package io.github.mojolowjo.entropybot.threat;

/**
 * 0.37.x (brain-b1 open item): cornered (no flee heading clear of mobs) and fighting is bad (low health, too strong, a
 * losing verdict, or several mobs): pillar up {@link #UP} blocks with throwaway blocks (cobble, dirt...: the cheap ones
 * the refill-cost rules allow) so melee mobs can't reach. Too few blocks, too little headroom, or not on firm ground =
 * fight the closest, as before. Digging into a wall and sealing is not done here (open). Pure; loader notes: none.
 */
public final class CornerEscape {
    private CornerEscape() {}

    public static final int UP = 3;
    public static final int MIN_UP = 2;
    /** Ticks a pillar may take before it gives up (a jump-place step takes about 12). */
    public static final int TIMEOUT = 100;

    public record Plan(int height, String why) {
        public boolean pillar() { return height >= MIN_UP; }
    }

    /** Fighting the closest is a bad answer: health at or below the retreat line, a too-strong mob, a losing verdict, or 2+ mobs near. */
    public static boolean fightBad(double health, double retreatAt, boolean strong, boolean losing, int mobsNear) {
        return health <= retreatAt || strong || losing || mobsNear >= 2;
    }

    /**
     * @param headroom free cells straight above the head (from feet + 2 up, counted until the first solid)
     * @param blocks   throwaway blocks in the inventory
     */
    public static Plan plan(boolean fightBad, boolean onGround, boolean inFluid, int headroom, int blocks) {
        if (!fightBad) return new Plan(0, "fighting the closest is fine");
        if (inFluid || !onGround) return new Plan(0, "not on firm ground to pillar from");
        int h = Math.min(UP, Math.min(headroom, blocks));
        if (h >= MIN_UP) return new Plan(h, "pillaring up " + h + " (" + blocks + " throwaway blocks, headroom " + headroom + ")");
        return new Plan(0, blocks < MIN_UP ? "only " + blocks + " throwaway blocks to pillar with" : "headroom " + headroom + " is too low to pillar");
    }

    /** Place the next block into cell {@code cellY} once the feet have risen clear of it (a block can't overlap the bot). */
    public static boolean placeNow(double feetY, int cellY) { return feetY >= cellY + 1.0; }
}
