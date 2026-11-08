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

    /**
     * job-band2: a known cave's Y span from its record - the record Y and the Ys of its points (openings, passage ends) -
     * {lo, hi}; when the record holds one Y only (no point off it), widened by {@code slack} (band.caveSpanY) each way.
     */
    public static int[] span(int y, java.util.List<int[]> points, double slack) {
        int lo = y, hi = y;
        if (points != null) for (int[] p : points) if (p != null && p.length >= 3) { lo = Math.min(lo, p[1]); hi = Math.max(hi, p[1]); }
        if (lo == hi) { int s = (int) Math.round(slack); lo -= s; hi += s; }
        return new int[]{lo, hi};
    }

    /** job-band2: the walk from the dig's end to a cave's lip: seconds and words. */
    public record Lip(double seconds, String line) {}

    /**
     * job-band2: the lip walk's price - Baritone's estimate from the dig's end ({@code estS}; NaN: not known, the straight
     * metres' walk {@code straightS} instead, said) at least the straight walk (an estimate is a lower bound, rule 18), plus
     * {@code downS} (the job-est dig charge) when the lip is {@code deeper} levels under the dig's end.
     */
    public static Lip lip(double estS, double straightS, double downS, int deeper, int[] span) {
        boolean known = Double.isFinite(estS);
        double walk = known ? Math.max(estS, straightS) : straightS;
        double dig = deeper > 0 ? downS : 0;
        String line = String.format(Locale.ROOT, "the walk to the lip %.0f s (%s)%s, the cave's y %d..%d", walk + dig,
                known ? String.format(Locale.ROOT, "Baritone's estimate from the dig's end %.1f s, the straight walk %.1f s", estS, straightS)
                        : String.format(Locale.ROOT, "no estimate: the straight walk %.1f s", straightS),
                dig > 0 ? String.format(Locale.ROOT, " + %d levels down %.0f s", deeper, dig) : "", span[0], span[1]);
        return new Lip(walk + dig, line);
    }

    static String fmt(double v) { return String.format(Locale.ROOT, v >= 0.01 ? "%.3f" : "%.5f", v); }   // structural: the words' digits
}
