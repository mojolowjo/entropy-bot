package io.github.mojolowjo.entropybot.aa.brain;

import java.util.Locale;
import java.util.Map;

/**
 * 0.39.z78 J6c (to-do/j6c-cave-yield-from-gen.md; owner 2026-10-28: "I like that idea over mapping it ourselves" and
 * "whenever we use game data we are doing that to take in account for modded as well"): a known cave's yield PRIOR from
 * the game's world-gen data, never a hand number (the 0.5 ore a minute Tuning prior is deleted). The owner's rulings:
 * <ol>
 * <li>The expected ore lies in the cave's AIR-EXPOSED blocks (the game's own term, {@code discard_chance_on_air_exposure}):
 * expected = the sum over the air-exposed blocks at each Y of the ore's share of the blocks at that Y - every placement in
 * the client's jars that places a block giving the need, vanilla and modded alike (GenWhere: the need's blocks from the
 * loot data, rules 4 and 19), its count x vein size per chunk layer over the layer's {@link #LAYER_BLOCKS} blocks - times
 * (1 - the placer's discard on air exposure): {@link GenWhere.Fact#curve}(y, true) / 256.</li>
 * <li>The cave's volume is a LOWER BOUND: one touching the loaded edge is OPEN ("at least this"), priced again as its
 * chunks load (the record is incremental); the measured yield replaces the prior once the cave is walked.</li>
 * </ol>
 * The yield a minute: the expected ore over the minutes the caving step takes to walk the cave's cells (cells /
 * CaveIndex.CROSS_SECTION a block, at Unit.walkBps). Not known (rule 15): the world-gen read not done, the cave's cells or
 * its air-exposed blocks still being read - the way waits, said. A need whose ore no placement places: "no world-gen data
 * for X" - the cave way is not priced for it. Pure (JUnit: J6cCaveYieldTest). Loader notes: none (the placements are
 * WorldGenFiles' read of the jars' data).
 */
public final class CaveYield {
    private CaveYield() {}

    /** game fact: the blocks of one chunk layer, 16 x 16 (the placements' counts are per chunk). */
    public static final int LAYER_BLOCKS = 256; // game fact: a chunk layer, 16 x 16 blocks

    /**
     * The prior: {@code perMin} ore a minute (NaN: not known), the {@code expected} ore in the cave, its air-exposed
     * {@code blocks}, the minutes the walk takes, {@code open} (a lower bound), and {@code why} - the words: the basis when
     * known, the reason when not; {@code noData}: no placement places the need's ore (the way is not priced).
     */
    public record Prior(double perMin, double expected, long blocks, double minutes, boolean open, boolean noData, String why) {
        public boolean known() { return perMin == perMin; }
    }

    /**
     * The prior of one cave for need {@code word}: {@code where} its world-gen fact (GenWhere, every jar's placements);
     * {@code byY} / {@code blocks} / {@code open} / {@code done}: the cave record's air-exposed count
     * (CaveRecord.Exposure); {@code cells} the cave's cells now; {@code walkBps} the walk's speed.
     */
    public static Prior of(String word, GenWhere.Fact where, Map<Integer, Integer> byY, long blocks, boolean open, boolean done, long cells, double walkBps) {
        String w = word == null ? "?" : word.replaceFirst("^minecraft:", "");
        if (where == null || where.kind() == GenWhere.Kind.NOT_LOADED)
            return new Prior(Double.NaN, 0, blocks, Double.NaN, open, false, "not known yet: " + (where == null ? "the world-gen read is not done" : where.why()));
        if (!where.band() || where.bands().isEmpty())
            return new Prior(Double.NaN, 0, blocks, Double.NaN, open, true, "no world-gen data for " + w + " (" + (where.why() != null ? where.why() : where.text()) + ")");
        if (byY == null || !done || cells <= 0)
            return new Prior(Double.NaN, 0, blocks, Double.NaN, open, false, "not known yet: the cave's cells and air-exposed blocks are still being read ("
                    + Math.max(0, cells) + " cells, " + blocks + " air-exposed blocks so far)");
        double expected = 0;
        for (var e : byY.entrySet()) expected += e.getValue() * where.curve(e.getKey(), true) / LAYER_BLOCKS;
        double minutes = cells / (double) io.github.mojolowjo.entropybot.sense.CaveIndex.CROSS_SECTION / walkBps / 60.0;   // structural: seconds a minute
        double perMin = minutes > 0 ? expected / minutes : Double.NaN;
        String why = String.format(Locale.ROOT, "game data: %.1f %s ore expected in %d air-exposed blocks, ~%.1f min to walk its %d cells", expected, w, blocks, minutes, cells)
                + (open ? "; open at the loaded edge - at least this, priced again as its chunks load" : "");
        return new Prior(perMin, expected, blocks, minutes, open, false, why);
    }
}
