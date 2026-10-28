package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.cave.CaveWalk;
import io.github.mojolowjo.entropybot.sense.CaveIndex;
import io.github.mojolowjo.entropybot.sense.CaveRecord;
import io.github.mojolowjo.entropybot.sense.Sightings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 J6b run EO (05:13:14-44: "at the branch at -51 51 81" 611 times; 05:13:44 on: the 0.5-min caving walk with no
 * ore made the cave's yield 0 and no iron order was ever offered it, silently; the helmet climbed out of the cave it stood
 * in): a yield under the floor of minutes is not measured; every cave left out is said; the cave the bot is in is the way;
 * an arrival that shows nothing new drops the goto.
 */
class J6bEoTest {
    static final String D = "minecraft:overworld";

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** EO's save round -50 52 80 (resource runEO-branch-51-51-81.txt): the solid cells and the cube's open cells (the cave's air there). */
    static List<Set<Long>> eoCube() throws java.io.IOException {
        Set<Long> solid = new HashSet<>(), fluid = new HashSet<>(), open = new HashSet<>();
        try (var in = J6bEoTest.class.getResourceAsStream("/runEO-branch-51-51-81.txt")) {
            assertNotNull(in, "the EO save's cells");
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.trim().split(" ");
                long c = Sightings.cell(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                (p[0].equals("S") ? solid : fluid).add(c);
            }
        }
        for (int x = -57; x <= -43; x++) for (int z = 73; z <= 87; z++) for (int y = 42; y <= 59; y++) {
            long c = Sightings.cell(x, y, z);
            if (!solid.contains(c) && !fluid.contains(c)) open.add(c);
        }
        return List.of(solid, open);
    }

    static Sightings caveOf(Set<Long> open) {
        Sightings s = new Sightings();
        assertNotNull(s.knowCave(D, new CaveIndex.Known(-45, 60, 78, CaveIndex.Size.LARGE, CaveIndex.Depth.DEEPSLATE, List.of(Sightings.cell(-45, 60, 78))), 1));
        s.caveCells(D, -45, 60, 78, new ArrayList<>(open));
        return s;
    }

    /**
     * EO's -51 51 81 from the save: from the feet -49 53 79 it is in reach and sight (its reach goal holds the feet), but the
     * stand's look (6 steps through the air) reaches it at its edge - seen, with unseen cells behind it; the arrival adds
     * nothing, so the goto drops ONCE ("arrived, nothing new") and is never picked again.
     */
    @Test
    void eosBranch_51_51_81_arrivedNothingNew_dropsOnce() throws java.io.IOException {
        var cube = eoCube();
        Set<Long> solid = cube.get(0), open = cube.get(1);
        int[] feet = {-49, 53, 79}, b = {-51, 51, 81};
        assertTrue(open.contains(Sightings.cell(feet[0], feet[1], feet[2])) && open.contains(Sightings.cell(b[0], b[1], b[2])), "both in the cave's air");
        assertTrue(J6bCaveStepTest.reachGoal(b, solid).isInGoal(feet[0], feet[1], feet[2]), "its reach goal holds the feet: in goal at once (EO's loop)");
        Sightings s = caveOf(open);
        assertTrue(s.caveStood(D, -45, 60, 78, feet[0], feet[1], feet[2], 1) > 0);
        var before = s.caveFacts(D, -45, 60, 78);
        int seenBefore = before.cellCount() - before.unseen();
        assertEquals(0, s.caveSaw(D, -45, 60, 78, List.of(Sightings.cell(b[0], b[1], b[2]))), "already seen by the stand: the arrival marks nothing");
        var after = s.caveFacts(D, -45, 60, 78);
        assertTrue(CaveWalk.nothingNew(seenBefore, after.cellCount() - after.unseen()), "arrived, nothing new: dropped");
        // there IS more behind it - the stand's look stopped at its edge
        Sightings fresh = caveOf(open);
        fresh.caveStood(D, -45, 60, 78, feet[0], feet[1], feet[2], 1);
        List<Long> behind = new ArrayList<>();
        for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) behind.add(Sightings.cell(b[0] + d[0], b[1] + d[1], b[2] + d[2]));
        assertTrue(fresh.caveSaw(D, -45, 60, 78, behind) > 0, "an unseen neighbour behind it (the 7th step)");
        // dropped once: never picked again
        var facts = new CaveRecord.Facts(-45, 60, 78, 1000, 500, 0.5, 2, false, Map.of(), 0, Double.NaN, 0, true, "t");
        long key = Sightings.cell(b[0], b[1], b[2]), other = Sightings.cell(-46, 54, 79);
        var groups = List.of(new CaveRecord.Group(key, 1, CaveRecord.BRANCH_MIN + 1), new CaveRecord.Group(other, 1, CaveRecord.BRANCH_MIN + 1));
        var next = CaveWalk.next(0, 1, 0, 10, facts, groups, List.of(), feet, 4.5, null, Set.of(key));
        assertTrue(next instanceof CaveWalk.Goto g && g.key() == other, "the next branch, never -51 51 81 again: " + next);
    }

    /** The 0.5-min walk with no ore: not measured (the prior holds, 2 min floor at 0.5 ore/min); 2.5 min: measured 0, and said. */
    @Test
    void aHalfMinuteWalk_isNotMeasured_theCaveStaysOffered() {
        double prior = 0.5;
        assertEquals(2.0, CaveRecord.measureFloorMinutes(prior), 1e-9, "the minutes one ore takes at the prior");
        Sightings s = new Sightings();
        s.knowCave(D, new CaveIndex.Known(-45, 60, 78, CaveIndex.Size.LARGE, CaveIndex.Depth.DEEPSLATE, List.of(Sightings.cell(-45, 60, 78))), 1);
        s.caveWalked(D, -45, 60, 78, 0.5, 0, 10);
        var f = s.caveFacts(D, -45, 60, 78);
        assertFalse(f.measured(prior), "0.5 min: not measured");
        assertEquals(prior, f.rate(prior), 1e-9, "the prior holds, never 0");
        Chain c = NeedChain.resolve("raw_iron", 3, Z77CaveWayTest.world(new CaveWay(20, 21, -10, 30, "minecraft:iron_ore", "iron", f.rate(prior), f.measured(prior), CaveIndex.Size.LARGE, -1, List.of())));
        assertTrue(Z77CaveWayTest.caveMined(c), "the cave stays offered: " + c.why());
        s.caveWalked(D, -45, 60, 78, 2.0, 0, 20);
        var g = s.caveFacts(D, -45, 60, 78);
        assertTrue(g.measured(prior));
        assertEquals(0, g.rate(prior), 1e-9, "2.5 min with no ore: measured 0");
        Chain zero = NeedChain.resolve("raw_iron", 3, Z77CaveWayTest.world(new CaveWay(20, 21, -10, 30, "minecraft:iron_ore", "iron", g.rate(prior), true, CaveIndex.Size.LARGE, -1, List.of())));
        assertTrue(zero.why().contains("known cave 30 m at 20 21 -10: its yield is 0.00 ore a minute (measured)"), "said, never silent: " + zero.why());
        Chain stuck = NeedChain.resolve("raw_iron", 3, Z77CaveWayTest.world(new CaveWay(20, 21, -10, 30, "minecraft:iron_ore", "iron", 0.5, false, CaveIndex.Size.LARGE, -1, List.of())
                .withOut("its last walk gained nothing and nothing new is known of it since (stuck)")));
        assertTrue(stuck.why().contains("known cave 30 m at 20 21 -10: its last walk gained nothing"), stuck.why());
    }

    /** A bot inside the known cave (15 under ground) gets its branches - the caving step from here - never the surface leg. */
    @Test
    void insideTheKnownCave_itsBranches_notTheSurfaceLeg() {
        var over = new NeedChain.Surface(15, -58, 52, 72, 1.4, false);
        CaveWay in = new CaveWay(-45, 60, 78, 18, "minecraft:iron_ore", "iron", 0.5, false, CaveIndex.Size.LARGE, 30, List.of(), List.of(new int[]{-45, 60, 78})).withInside(true);
        Chain c = NeedChain.resolve("raw_iron", 3, J6bEhTest.iron(over, Map.of()).withCave(w -> w.contains("raw_iron") || w.contains("iron_ore") ? in : null));
        assertTrue(c.ok(), c.why());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Cave), "the caving step: " + c.steps());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.ToSurface || s instanceof Step.ToCave || s instanceof Step.Explore), "no surface leg, no lip walk, no explore: " + c.steps());
        assertTrue(c.why().contains("the bot is inside it"), c.why());
        Chain out = NeedChain.resolve("raw_iron", 3, J6bEhTest.iron(over, Map.of()).withCave(w -> w.contains("raw_iron") || w.contains("iron_ore") ? in.withInside(false) : null));
        assertTrue(out.steps().stream().anyMatch(s -> s instanceof Step.ToCave), "outside it: the lip walk first, as before - " + out.steps());
    }
}
