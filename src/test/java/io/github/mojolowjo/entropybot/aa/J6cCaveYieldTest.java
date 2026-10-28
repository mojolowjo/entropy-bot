package io.github.mojolowjo.entropybot.aa;

import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.aa.brain.CaveYield;
import io.github.mojolowjo.entropybot.aa.brain.GenWhere;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.sense.CaveIndex;
import io.github.mojolowjo.entropybot.sense.CaveRecord;
import io.github.mojolowjo.entropybot.sense.Sightings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6c (to-do/j6c-cave-yield-from-gen.md, the owner's rulings 2026-10-28): a known cave's yield prior from the
 * game data - its AIR-EXPOSED blocks (Nearby.exposed over the cave index's air: one reader) by Y x the share every
 * placement of the need's ore gives there (count x size per chunk layer / 256) x (1 - the air-exposure discard); the
 * volume a lower bound ("open" at the loaded edge); modded placements count; a need with no placement says "no
 * world-gen data for X"; the 0.5 ore a minute Tuning prior is gone. Hand numbers: a 2 x 2 tube along x shows 8 air-exposed
 * blocks a block of length (2 under, 2 over, 2 a side), 2 on each of its 4 Ys (y0 - 1 .. y0 + 2); its 4 cells a block.
 */
class J6cCaveYieldTest {
    static final String D = "minecraft:overworld";

    @AfterEach
    void clear() { Tuning.reset(); }

    static GenWhere.Placer placer(String id, String block, String placed, int size, double discard) {
        return new GenWhere.Placer(id, List.of(block), JsonParser.parseString(placed).getAsJsonObject().getAsJsonArray("placement"), size, discard);
    }

    /** Iron as the vanilla jar places it: ore_iron_middle (trapezoid -24..56, 10 a chunk, size 9) and ore_iron_small. */
    static final List<GenWhere.Placer> IRON = List.of(
            placer("minecraft:ore_iron_middle", "minecraft:iron_ore", Z43StripWayTest.MIDDLE, 9, 0),
            placer("minecraft:ore_iron_small", "minecraft:iron_ore", Z43StripWayTest.SMALL, 4, 0));

    static GenWhere.Fact iron() { return GenWhere.of(Set.of("minecraft:iron_ore"), IRON, -64, 320); }

    /** A 2 x 2 tube along x from x0, {@code len} long, at y0..y0+1, z 0..1. */
    static Set<Long> tube(int x0, int y0, int len) {
        Set<Long> c = new HashSet<>();
        for (int x = x0; x < x0 + len; x++) for (int y = y0; y <= y0 + 1; y++) for (int z = 0; z <= 1; z++) c.add(Sightings.cell(x, y, z));
        return c;
    }

    /** The known cave of {@code cells} entered at x0 y0 0, its air-exposed count run with {@code air}. */
    static Sightings cave(Set<Long> cells, int x0, int y0, io.github.mojolowjo.entropybot.aa.Nearby.Open air) {
        Sightings s = new Sightings();
        assertNotNull(s.knowCave(D, new CaveIndex.Known(x0, y0, 0, CaveIndex.Size.SMALL, CaveIndex.Depth.STONE, List.of(Sightings.cell(x0, y0, 0))), 1));
        s.caveCells(D, x0, y0, 0, cells);
        s.caveExpose(D, x0, y0, 0, air, Integer.MAX_VALUE);
        return s;
    }

    static io.github.mojolowjo.entropybot.aa.Nearby.Open airOf(Set<Long> cells) {
        return (x, y, z) -> cells.contains(Sightings.cell(x, y, z));
    }

    static CaveYield.Prior prior(Sightings s, int x0, int y0, String word, GenWhere.Fact where) {
        CaveRecord.Exposure ex = s.caveExposure(D, x0, y0, 0);
        return CaveYield.of(word, where, ex.byY(), ex.blocks(), ex.open(), ex.done(), s.caveFacts(D, x0, y0, 0).cellCount(), Unit.walkBps());
    }

    @Test
    void theAirExposedBlocks_areTheTubesWalls_byY() {
        Set<Long> t = tube(0, 16, 10);
        Sightings s = cave(t, 0, 16, airOf(t));
        CaveRecord.Exposure ex = s.caveExposure(D, 0, 16, 0);
        assertTrue(ex.done() && !ex.open());
        assertEquals(8 * 10 + 2 * 4, ex.blocks(), "8 a block of length, and the two 2 x 2 ends");
        assertEquals(2 * 10, ex.byY().get(15), "under the tube");
        assertEquals(2 * 10 + 4, ex.byY().get(16), "the sides and the ends' lower row");
        assertEquals(2 * 10, ex.byY().get(18), "over the tube");
    }

    /** A LARGE cave in the iron band expects more ore than a SMALL one at the same Y; a minute of either walks the same walls. */
    @Test
    void aLargeCaveInTheBand_expectsMoreThanASmallOne_atTheSameY() {
        Set<Long> small = tube(0, 16, 20), large = tube(0, 16, 2600);     // 80 and 10,400 cells: 10,400 / 4 / walkBps / 60 > 10 min (LARGE)
        CaveYield.Prior ps = prior(cave(small, 0, 16, airOf(small)), 0, 16, "raw_iron", iron());
        CaveYield.Prior pl = prior(cave(large, 0, 16, airOf(large)), 0, 16, "raw_iron", iron());
        assertEquals(CaveIndex.Size.LARGE, CaveIndex.Size.of(large.size(), Unit.walkBps(), Tuning.get("cave.exploreMinutes")));
        assertTrue(ps.known() && pl.known(), ps.why() + " | " + pl.why());
        assertTrue(pl.expected() > 100 * ps.expected(), ps.expected() + " vs " + pl.expected());
        // the share by hand: the walls at y 15..18 x (middle + small) per chunk layer / 256
        double share = 0;
        for (int y = 15; y <= 18; y++) share += 2 * iron().curve(y, true) / CaveYield.LAYER_BLOCKS;
        assertEquals(share * 20 + 2 * (iron().curve(16, true) + iron().curve(17, true)) / CaveYield.LAYER_BLOCKS * 2, ps.expected(), 1e-9);
        assertTrue(ps.perMin() > 0 && pl.perMin() > 0);
        assertTrue(ps.why().startsWith("game data: ") && ps.why().contains("air-exposed blocks"), ps.why());
    }

    /** Above the iron band (y 120: middle tops at 56, small at 72): ~0 for iron, said with its basis, never dropped silently. */
    @Test
    void aCaveAboveTheBand_getsZeroForIron_said() {
        Set<Long> t = tube(0, 120, 40);
        CaveYield.Prior p = prior(cave(t, 0, 120, airOf(t)), 0, 120, "raw_iron", iron());
        assertTrue(p.known());
        assertEquals(0, p.perMin(), 1e-12);
        NeedChain.CaveWay w = new NeedChain.CaveWay(0, 120, 0, 30, "minecraft:iron_ore", "iron", p.perMin(), false).withBasis(p.why());
        String out = NeedChain.caveOut(w);
        assertNotNull(out);
        assertTrue(out.startsWith("its yield is 0.00 ore a minute (prior, game data: 0.0 raw_iron ore expected in 328 air-exposed blocks"), out);
    }

    /** Ruling 2: a volume touching the loaded edge is open - at least this; priced again as the chunk loads. */
    @Test
    void aVolumeAtTheLoadedEdge_isOpen_aLowerBound_pricedAgainWhenItLoads() {
        Set<Long> t = tube(0, 16, 24);                                       // x 0..23: chunk 0 and chunk 1
        boolean[] loaded = {false};
        io.github.mojolowjo.entropybot.aa.Nearby.Open air = (x, y, z) -> (x >> 4) != 0 && !loaded[0] ? null : t.contains(Sightings.cell(x, y, z));
        Set<Long> fed = new HashSet<>();
        for (long c : t) if ((Sightings.cellOf(c)[0] >> 4) == 0) fed.add(c);     // the index holds chunk 0 only
        Sightings s = cave(fed, 0, 16, air);
        CaveRecord.Exposure before = s.caveExposure(D, 0, 16, 0);
        assertTrue(before.open(), "a neighbour in the chunk not loaded");
        CaveYield.Prior p = prior(s, 0, 16, "raw_iron", iron());
        assertTrue(p.known() && p.open() && p.why().endsWith("open at the loaded edge - at least this, priced again as its chunks load"), p.why());
        loaded[0] = true;                                                    // chunk 1 loads: its cells fed, the pending looked at
        s.caveCells(D, 0, 16, 0, t);
        s.caveExpose(D, 0, 16, 0, air, Integer.MAX_VALUE);
        CaveRecord.Exposure after = s.caveExposure(D, 0, 16, 0);
        assertFalse(after.open());
        assertEquals(8 * 24 + 2 * 4, after.blocks(), "the whole tube's walls now");
        assertTrue(after.blocks() > before.blocks());
    }

    /** Rule 15: a cave whose cells (or air-exposed blocks) are still being read has no prior yet - the way waits, said. */
    @Test
    void aVolumeStillFeeding_theYieldIsNotKnownYet() {
        CaveYield.Prior p = CaveYield.of("raw_iron", iron(), java.util.Map.of(16, 12), 12, false, false, 40, Unit.walkBps());
        assertFalse(p.known());
        assertTrue(p.why().startsWith("not known yet: the cave's cells and air-exposed blocks are still being read (40 cells, 12 air-exposed blocks so far)"), p.why());
        assertTrue(CaveYield.of("raw_iron", GenWhere.notLoaded("the world-gen read is not done"), null, 0, false, true, 40, Unit.walkBps()).why().startsWith("not known yet: "));
    }

    /** The owner's add: a modded placement of a non-vanilla ore block counts; a need whose ore no placement places says so. */
    @Test
    void aModdedPlacement_counts_aNeedWithNoPlacement_saysNoWorldGenData() {
        List<GenWhere.Placer> placers = List.of(IRON.get(0), IRON.get(1),
                placer("othermod:ore_tin", "othermod:tin_ore", Z43StripWayTest.MIDDLE, 8, 0.5));
        Set<Long> t = tube(0, 16, 20);
        Sightings s = cave(t, 0, 16, airOf(t));
        CaveYield.Prior tin = prior(s, 0, 16, "othermod:raw_tin", GenWhere.of(Set.of("othermod:tin_ore"), placers, -64, 320));
        assertTrue(tin.known() && tin.expected() > 0, tin.why());
        assertTrue(tin.why().contains("raw_tin ore expected"), tin.why());
        CaveYield.Prior none = prior(s, 0, 16, "othermod:raw_mystery", GenWhere.of(Set.of("othermod:mystery_ore"), placers, -64, 320));
        assertFalse(none.known());
        assertTrue(none.noData());
        assertTrue(none.why().startsWith("no world-gen data for othermod:raw_mystery (no world-gen feature places othermod:mystery_ore)"), none.why());
    }

    /** No hand number: the 0.5 ore a minute prior and its weight are gone from the Tuning entries. */
    @Test
    void theHandPrior_isDeleted() throws java.io.IOException {
        String defs = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/io/github/mojolowjo/entropybot/aa/brain/TuningDefs.java"));
        String defaults = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/tuning-defaults.txt"));
        for (String k : List.of("seen.priorRate", "seen.priorMin")) {
            assertFalse(defs.contains("\"" + k + "\""), k + " is no Tuning entry");
            assertFalse(defaults.contains(k + "\t"), k + " has no defaults line");
        }
    }
}
