package io.github.mojolowjo.entropybot.threat;

/**
 * 0.37.x (brain-b1 open item): cornered (no flee heading clear of mobs) and fighting is bad (low health, too strong, a
 * losing verdict, or several mobs): pillar up {@link #UP} blocks with throwaway blocks (cobble, dirt...: the cheap ones
 * the refill-cost rules allow) so melee mobs can't reach. Too few blocks, too little headroom, or not on firm ground =
 * fight the closest, as before. aa-mobs2: with a wall next to it and no pillar room it digs in and seals instead ({@link #choose}). Pure; loader notes: none.
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

    // ---- dig into a wall and seal (brain-b1 open item, aa-mobs2) ----

    public enum Kind { NONE, PILLAR, DIG_IN }

    /** Blocks the seal needs: the entrance cell at feet and at head height. */
    public static final int SEAL_BLOCKS = 2;
    /** Ticks the whole dig-in (two breaks by hand or tool, a step, two placements) may take. */
    public static final int DIG_TIMEOUT = 240;

    public record Choice(Kind kind, int height, String why) {}

    /**
     * Pillar or dig in. A pillar needs room above and blocks; a dig-in needs a wall cell next to the bot that
     * {@code BreakPolicy} lets it break, a niche that is closed on the other sides, and {@link #SEAL_BLOCKS} blocks.
     * Wall adjacent and no pillar room = dig in; otherwise the pillar (cheaper, faster) stays the first choice.
     *
     * @param wallDig a wall cell next to the bot passed {@link #nicheOk} and the break policy
     */
    public static Choice choose(boolean fightBad, boolean onGround, boolean inFluid, int headroom, int blocks, boolean wallDig) {
        Plan pillar = plan(fightBad, onGround, inFluid, headroom, blocks);
        if (pillar.pillar()) return new Choice(Kind.PILLAR, pillar.height(), pillar.why());
        if (!fightBad || inFluid || !onGround) return new Choice(Kind.NONE, 0, pillar.why());
        if (!wallDig) return new Choice(Kind.NONE, 0, pillar.why() + "; no wall to dig into");
        if (blocks < SEAL_BLOCKS) return new Choice(Kind.NONE, 0, "only " + blocks + " throwaway blocks to seal a dug-in niche with");
        return new Choice(Kind.DIG_IN, 0, "digging into the wall and sealing (" + pillar.why() + ")");
    }

    /**
     * A niche (the two wall cells in front of the bot) is safe to hide in: the cells beside and behind it at both
     * heights, and the cell above, are solid, the floor holds, and nothing wet or falling is near.
     *
     * @param enclosedSides how many of the 3 sides (left, right, far) are solid at BOTH heights
     */
    public static boolean nicheOk(boolean frontBreakable, int enclosedSides, boolean ceilingSolid, boolean floorSolid, boolean fluidNear) {
        return frontBreakable && enclosedSides == 3 && ceilingSolid && floorSolid && !fluidNear;
    }

    /** The dig-in stages: 0 break the feet cell, 1 break the head cell, 2 step in, 3 seal at feet, 4 seal at head, 5 hold. */
    public static int nextStage(int stage, boolean feetOpen, boolean headOpen, boolean inNiche, boolean feetSealed, boolean headSealed) {
        if (stage == 0) return feetOpen ? 1 : 0;
        if (stage == 1) return headOpen ? 2 : 1;
        if (stage == 2) return inNiche ? 3 : 2;
        if (stage == 3) return feetSealed ? 4 : 3;
        if (stage == 4) return headSealed ? 5 : 4;
        return stage;
    }

    /** A running retreat looks again once a second (20 ticks): cornered then means switching to the escape. */
    public static boolean recheckNow(long now, long retreatStart) {
        return now > retreatStart && (now - retreatStart) % 20 == 0;
    }

    /** Place the next block into cell {@code cellY} once the feet have risen clear of it (a block can't overlap the bot). */
    public static boolean placeNow(double feetY, int cellY) { return feetY >= cellY + 1.0; }
}
