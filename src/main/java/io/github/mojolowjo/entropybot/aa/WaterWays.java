package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.guard.DrownRisk;

/**
 * aa-water (to-do 1f, owner aa386: floating, it wants a block, swims up, retries): mining an ore in or by water is a
 * costed situation, not a loop. The ways to a wet ore, each priced in walk-seconds (the time it takes, plus the drowning
 * risk K x risk^2 as seconds; fatal = not offered):
 * <ul>
 * <li>FLOAT: as before, dig while floating: vanilla 5x submerged x 5x off the ground, from the ore's depth;</li>
 * <li>STAND: swim to a floor block by the ore under water, then dig (5x, standing);</li>
 * <li>PLACE: place a block from the bag under a water cell by the ore, stand on it, then dig (5x, standing);</li>
 * <li>DRY: walk to a dry cell by the ore, then dig dry (1x, no risk);</li>
 * </ul>
 * the fifth way, another ore, is the scorer's next row. IronOptions turns seconds into its walk-blocks with
 * {@link #WALK_BPS}. Pure (JUnit: WaterWaysTest). Loader notes: none (the cells come from Mining.aaOres).
 */
public final class WaterWays {
    private WaterWays() {}

    public enum Way { FLOAT, STAND, PLACE, DRY }

    /** Blocks per second walking, swimming; the factor for digging off the ground. */
    public static final double WALK_BPS = 4.3, SWIM_BPS = 2.0, FLOAT_SLOW = 5;

    /**
     * The wet facts of one ore (null cells / -1 distances: that way is not there).
     *
     * @param inWater   I am in the water now
     * @param oreDepth  the ore's depth below the water surface (0: the ore is not under water, only by it)
     * @param air       air ticks now (300 full)
     * @param tier      pickaxe tier
     * @param floor     a floor cell to stand in (water, solid below) by the ore; floorDepth its depth below the surface
     * @param place     the water cell to place a block into (stand on it, in {@code place} + 1); placeDepth the stand's depth
     * @param dry       a dry cell to stand in by the ore
     * @param throwaway blocks in the bag to place
     */
    public record Wet(boolean inWater, int oreDepth, int air, int tier,
                      int[] floor, double floorDist, int floorDepth,
                      int[] place, double placeDist, int placeDepth,
                      int[] dry, double dryDist, int throwaway) {}

    public record Priced(Way way, int[] stand, double seconds, String why) {}

    /** Seconds per block dug on dry ground with this tier (DrownRisk's table, without the drop). */
    static double dryDigS(int tier) { return (DrownRisk.digS(tier) - DrownRisk.DROP_S) / DrownRisk.WATER_SLOW; }

    /** The drowning risk as seconds (K walk-blocks per risk^2, over the walking speed); infinite when fatal. */
    static double riskS(int depth, double workS, int air) {
        double r = DrownRisk.cellRisk(depth, workS, air);
        return DrownRisk.fatal(r) ? Double.POSITIVE_INFINITY : DrownRisk.K * r * r / WALK_BPS;
    }

    /** One way's price, or null when it is not there or fatal. */
    public static Priced price(Way w, Wet f) {
        double sub = DrownRisk.digS(f.tier());                     // standing, submerged (5x)
        switch (w) {
            case FLOAT -> {
                if (f.oreDepth() <= 0 && !f.inWater()) return null;
                double dig = sub * FLOAT_SLOW;
                double risk = riskS(f.oreDepth(), dig, f.air());
                if (Double.isInfinite(risk)) return null;
                return new Priced(w, null, dig + risk, String.format(java.util.Locale.ROOT, "floating dig %.0f s, %d under", dig, f.oreDepth()));
            }
            case STAND -> {
                if (f.floor() == null || f.floorDist() < 0) return null;
                double risk = riskS(f.floorDepth(), sub, f.air());
                if (Double.isInfinite(risk)) return null;
                double s = f.floorDist() / (f.inWater() ? SWIM_BPS : WALK_BPS) + sub + risk;
                return new Priced(w, f.floor(), s, String.format(java.util.Locale.ROOT, "floor %d %d %d, %d under", f.floor()[0], f.floor()[1], f.floor()[2], f.floorDepth()));
            }
            case PLACE -> {
                if (f.place() == null || f.placeDist() < 0 || f.throwaway() < 1) return null;
                double work = DrownRisk.PLACE_S + sub;
                double risk = riskS(f.placeDepth(), work, f.air());
                if (Double.isInfinite(risk)) return null;
                double s = f.placeDist() / (f.inWater() ? SWIM_BPS : WALK_BPS) + work + risk;
                int[] stand = {f.place()[0], f.place()[1] + 1, f.place()[2]};
                return new Priced(w, stand, s, String.format(java.util.Locale.ROOT, "place at %d %d %d, %d under", f.place()[0], f.place()[1], f.place()[2], f.placeDepth()));
            }
            case DRY -> {
                if (f.dry() == null || f.dryDist() < 0) return null;
                double s = f.dryDist() / (f.inWater() ? SWIM_BPS : WALK_BPS) + dryDigS(f.tier());
                return new Priced(w, f.dry(), s, String.format(java.util.Locale.ROOT, "dry side %d %d %d", f.dry()[0], f.dry()[1], f.dry()[2]));
            }
        }
        return null;
    }
}
