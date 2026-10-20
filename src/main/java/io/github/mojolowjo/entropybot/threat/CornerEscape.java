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

    public enum Kind { NONE, PILLAR, DIG_IN, DIG_DOWN }

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
        return choose(fightBad, onGround, inFluid, headroom, blocks, wallDig, 0);
    }

    /** aa-night3: the escapes' costs in "steps": a placement {@link #PLACE}, a break {@link #BREAK}. */
    public static final double PLACE = 1, BREAK = 2;

    /**
     * aa-night3 (normal2: "cornered, no escape: only 0 throwaway blocks to pillar with; no wall to dig into", 7 deaths):
     * a third escape, dig DOWN {@code downDepth} cells (2 or 3, 0 = no fit; {@link #burrowDepth}) and seal the hole above
     * the head with a block just dug out, so it needs no throwaway block. The cheapest that fits wins: a pillar
     * (h x PLACE), the wall niche (2 BREAK + 2 PLACE, 2 blocks), the burrow (depth x BREAK + 1 PLACE).
     */
    public static Choice choose(boolean fightBad, boolean onGround, boolean inFluid, int headroom, int blocks, boolean wallDig, int downDepth) {
        Plan pillar = plan(fightBad, onGround, inFluid, headroom, blocks);
        if (!fightBad || inFluid || !onGround) return new Choice(Kind.NONE, 0, pillar.why());
        double cp = pillar.pillar() ? pillar.height() * PLACE : Double.NaN;
        double cw = wallDig && blocks >= SEAL_BLOCKS ? 2 * BREAK + 2 * PLACE : Double.NaN;
        double cd = downDepth >= 2 ? downDepth * BREAK + PLACE : Double.NaN;
        String costs = "costs: pillar " + c(cp) + ", wall " + c(cw) + ", down " + c(cd);
        if (Double.isNaN(cp) && Double.isNaN(cw) && Double.isNaN(cd)) {
            String why = pillar.why() + (wallDig ? "; only " + blocks + " throwaway blocks to seal a dug-in niche with" : "; no wall to dig into") + "; no ground to dig down into";
            return new Choice(Kind.NONE, 0, why);
        }
        double best = Math.min(n(cp), Math.min(n(cw), n(cd)));
        if (best == n(cp)) return new Choice(Kind.PILLAR, pillar.height(), pillar.why() + " (" + costs + ")");
        if (best == n(cw)) return new Choice(Kind.DIG_IN, 0, "digging into the wall and sealing (" + pillar.why() + "; " + costs + ")");
        return new Choice(Kind.DIG_DOWN, downDepth, "digging down " + downDepth + " and sealing above with a dug block (" + pillar.why() + "; " + costs + ")");
    }

    private static double n(double v) { return Double.isNaN(v) ? Double.MAX_VALUE : v; }

    private static String c(double v) { return Double.isNaN(v) ? "-" : String.valueOf((int) v); }

    /**
     * aa-night3: how deep a burrow here goes: 2 when the cell at the feet has a solid side to seal against (the seal goes
     * there), else 3 (the seal goes in the first dug cell, inside the ground); 0 when the cells to dig don't fit.
     *
     * @param digOk     the cells below the feet, 1 to 3 down: breakable, not falling, no fluid next to them (BreakPolicy too)
     * @param sidesOk   for a final depth d (index d-2 for 2 and 3): the feet and head cells there are closed on all 4 sides and the floor holds
     * @param feetSide  the cell at the feet has a solid horizontal neighbour
     */
    public static int burrowDepth(boolean[] digOk, boolean[] sidesOk, boolean feetSide) {
        if (digOk == null || digOk.length < 3 || sidesOk == null || sidesOk.length < 2) return 0;
        if (feetSide && digOk[0] && digOk[1] && sidesOk[0]) return 2;
        if (digOk[0] && digOk[1] && digOk[2] && sidesOk[1]) return 3;
        return 0;
    }

    /**
     * aa-night3 burrow stages for depth d: 0..d-1 break cell k+1 below the start and drop into it, d seal, d+1 hold.
     *
     * @param cellOpen the cell being dug (stage k: k+1 below the start) is open
     * @param atDepth  the feet are at that cell's height
     * @param sealed   the seal cell is solid
     */
    public static int burrowStage(int stage, int depth, boolean cellOpen, boolean atDepth, boolean sealed) {
        if (stage < depth) return cellOpen && atDepth ? stage + 1 : stage;
        if (stage == depth) return sealed ? depth + 1 : depth;
        return stage;
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
