package io.github.mojolowjo.entropybot.cave;

import java.util.Locale;

/**
 * Job-band (run JAQ 2026-11-08: a y-64 cave priced 1253 s an ore beat "explore under ground, toward the band", whose price
 * was its walk only; four runs combed y-64 caves 12 minutes each and saw no iron): the way to the ore's band in seconds,
 * by facts (rule 18). The parts:
 * <ol>
 * <li>down: the levels from {@code fromY} to the band's Y, each one block's break (the game's formula with the tool in
 * hand: the caller's breakS, stone over y 0, deepslate under it), plus the climb back the same levels (the climb row's
 * price, rungOptions.climbBlock walk-blocks a level);</li>
 * <li>at the band: ore per exposed block at that Y (the placements' curve / 256 a chunk layer: game data; a dig exposes rock
 * that was not air at generation, so no air-exposure discard) x the faces a dig exposes a second (band.facesPerBreak over
 * one break's seconds) gives the seconds an ore, plus the ore's own break.</li>
 * </ol>
 * Price for n ore = down + back + n x seconds an ore. Density not known (rule 15): the Tuning fallback
 * cave.orePerCellNotKnown, said in the line. Pure (JUnit: JobBandTest). Loader notes: none.
 */
public final class BandPrice {
    private BandPrice() {}

    /** The price: its seconds, the parts, and the words. */
    public record Price(double seconds, double downS, double backS, double perOreS, double perBlock, int levels, int y, boolean known, String line) {}

    /**
     * @param fromY      where the dig starts (the feet, or where the band leg leaves the bot)
     * @param bandY      the band's Y (a peak of the placements' curve)
     * @param perBlock   ore per exposed block at bandY (NaN or not above 0: not known)
     * @param why        the density's words (game data's basis)
     * @param n          ore blocks to find
     * @param downS      the dig's seconds over the levels (the caller sums the break of each level's block)
     * @param backS      the climb back's seconds over the levels
     * @param breakS     one break at the band (the faces' pace)
     * @param faces      faces one break exposes (band.facesPerBreak)
     * @param oreBreakS  one ore block's break
     * @param fallback   the not-known density (cave.orePerCellNotKnown)
     */
    public static Price dig(int fromY, int bandY, double perBlock, String why, int n, double downS, double backS, double breakS, double faces,
                            double oreBreakS, double fallback) {
        int levels = Math.abs(fromY - bandY);
        boolean known = perBlock > 0 && Double.isFinite(perBlock);
        double d = known ? perBlock : fallback;
        String dw = known ? why : String.format(Locale.ROOT, "density not known: the fallback cave.orePerCellNotKnown %s", fmt(fallback));
        double fps = breakS > 0 ? faces / breakS : 0;
        double perOre = d > 0 && fps > 0 ? 1 / (d * fps) + oreBreakS : Double.POSITIVE_INFINITY;
        double secs = downS + backS + n * perOre;
        String head = levels == 0 ? "at the band (y " + bandY + ")" : (fromY > bandY ? "down" : "up") + " to y " + bandY + ": " + levels + " levels, "
                + String.format(Locale.ROOT, "%.0f s (dig) + %.0f s back", downS, backS);
        String line = String.format(Locale.ROOT, "%s + %d ore at %s ore per exposed block, %.1f faces per second (%.1f s per ore) -> %.0f s (%s)",
                head, n, fmt(d), fps, perOre, secs, dw);
        return new Price(secs, downS, backS, perOre, d, levels, bandY, known, line);
    }

    static String fmt(double v) { return String.format(Locale.ROOT, v >= 0.01 ? "%.3f" : "%.5f", v); }   // structural: the words' digits
}
