package io.github.mojolowjo.entropybot.strip;

import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.clear.ClearBox;
import io.github.mojolowjo.entropybot.clear.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B7d D2: one run's steps as the bridge's stripSteps builds them, for the sim's strip scenarios (strip, stripout,
 * strip2, stripfar): which boxes, which order, torches, collecting, the base trip, the walk legs, the label.
 */
class StripPlanTest {
    /** The bridge's MAP_AREA (x -272..223, z -64..431) as the owner's one area. */
    static final Predicate<ClearBox> MAP = b -> b.x1() >= -272 && b.x2() <= 223 && b.z1() >= -64 && b.z2() <= 431;
    static final MineGeom MINE = new MineGeom(0, 40, 0, "north");

    static JsonObject note(int k, boolean setup) {
        JsonObject n = MineBook.fresh("0 40 0 north");
        n.addProperty("k", k);
        n.addProperty("setup", setup);
        return n;
    }

    static List<String> lines(StripPlan.Run r) {
        return r.items().stream().map(StripPlan.Item::toString).toList();
    }

    @Test
    void geometryAsTheSim() {
        assertEquals(new Pos(-1, 40, 0), MINE.cell(0, 1), "left of north is west");
        assertEquals("west", MINE.leftDir());
        assertEquals("east", MINE.rightDir());
        assertEquals(new ClearBox(0, 40, -3, 0, 41, -1), MINE.corridor(1));
        assertEquals(new ClearBox(-12, 40, -3, -1, 41, -3), MINE.branch(1, 1, 12));
        assertEquals(new ClearBox(1, 40, -6, 12, 41, -6), MINE.branch(2, -1, 12));
        assertEquals(List.of(new Pos(0, 40, -6)), MINE.corridorTorches(2));
        assertEquals(List.of(), MINE.corridorTorches(1));
        assertEquals(List.of(new Pos(-11, 40, -3), new Pos(-5, 40, -3)), MINE.branchTorches(1, 1, 12));
        MineGeom east = new MineGeom(-119, -54, 194, "east");
        assertEquals(new Pos(-116, -54, 194), east.cell(3, 0));
        assertEquals("north", east.leftDir());
    }

    @Test
    void stripRunOneSetsUpThenDigsAndCollects() {
        StripPlan.Run r = StripPlan.run(MINE, note(1, false), 1, 12, true, MAP, new int[]{0, 40, 0});
        assertEquals("strip mining branch 1 (setting up chests first)", r.label());
        assertEquals(List.of(
                "setupplan",
                "craft torch 16 unless minecraft:torch 8 optional",
                "dig corridor 1 0 40 -3 to 0 41 -1 collect keepOres=false dump=mine 'digging the mine corridor (branch 1)'",
                "dig left 1 -12 40 -3 to -1 41 -3 collect keepOres=false dump=mine torches=[-11 40 -3, -5 40 -3] 'digging branch 1 left'",
                "dig right 1 1 40 -3 to 12 41 -3 collect keepOres=false dump=mine torches=[11 40 -3, 5 40 -3] 'digging branch 1 right'",
                "oredig 1",
                "basedeposit",
                "minedone 1"), lines(r));
        assertEquals(List.of(MINE.corridor(1), MINE.branch(1, 1, 12), MINE.branch(1, -1, 12)), r.items().get(5).boxes);
        // the corridor's has-to-end-open check is the strip step's, so the clear itself never ends the errand
        assertFalse(r.items().get(2).opts.mustFinish);
    }

    @Test
    void stripRunTwoNearTheMineHasNoLegsAndATorchInTheCorridor() {
        StripPlan.Run r = StripPlan.run(MINE, note(2, true), 1, 12, true, MAP, new int[]{0, 40, -3});
        assertEquals("strip mining branch 2", r.label());
        assertEquals("dig corridor 2 0 40 -6 to 0 41 -4 collect keepOres=false dump=mine torches=[0 40 -6] 'digging the mine corridor (branch 2)'", lines(r).get(1));
    }

    @Test
    void stripoutLeavesOresAndHasNoBaseTrip() {
        MineGeom out = new MineGeom(300, 40, 0, "north");
        StripPlan.Run r = StripPlan.run(out, note(1, true), 1, 12, true, MAP, new int[]{300, 40, 0});
        assertEquals(List.of(
                "craft torch 16 unless minecraft:torch 8 optional",
                "dig corridor 1 300 40 -3 to 300 41 -1 keepOres=false dump=mine 'digging the mine corridor (branch 1)'",
                "dig left 1 288 40 -3 to 299 41 -3 dump=mine torches=[289 40 -3, 295 40 -3] 'digging branch 1 left'",
                "dig right 1 301 40 -3 to 312 41 -3 dump=mine torches=[311 40 -3, 305 40 -3] 'digging branch 1 right'",
                "minedone 1"), lines(r));
        assertTrue(r.items().get(2).opts.keepOres, "outside the area the branches list the ores");
    }

    @Test
    void collectingOffInsideTheAreaIsLikeOutside() {
        StripPlan.Run r = StripPlan.run(MINE, note(1, true), 1, 12, false, MAP, new int[]{0, 40, 0});
        assertTrue(lines(r).stream().noneMatch(l -> l.startsWith("oredig") || l.equals("basedeposit")));
    }

    @Test
    void severalBranchesAndABaseTripOnlyAtTheEnd() {
        StripPlan.Run r = StripPlan.run(MINE, note(3, true), 3, 16, true, MAP, new int[]{0, 40, -6});
        assertEquals("strip mining branches 3-5", r.label());
        List<String> l = lines(r);
        assertEquals(1, l.stream().filter("basedeposit"::equals).count());
        assertEquals(List.of("oredig 5", "basedeposit", "minedone 5"), l.subList(l.size() - 3, l.size()));
        assertTrue(l.contains("dig left 4 -16 40 -12 to -1 41 -12 collect keepOres=false dump=mine torches=[-15 40 -12, -9 40 -12] 'digging branch 4 left'"), l.toString());
    }

    @Test
    void aBranchNotedBadIsNotDugAgain() {
        JsonObject n = note(7, true);
        MineBook.addBad(n, "7 left");
        List<String> l = lines(StripPlan.run(MINE, n, 1, 12, true, MAP, new int[]{0, 40, -18}));
        assertTrue(l.stream().noneMatch(s -> s.startsWith("dig left 7")), l.toString());
        assertTrue(l.stream().anyMatch(s -> s.startsWith("dig right 7")), l.toString());
    }

    @Test
    void stripfarWalksEntranceThenCorridorLegs() {
        // the sim's stripfar: the corridor dug out to branch 30, the bot at the base's /home spot
        StripPlan.Run r = StripPlan.run(MINE, note(30, true), 1, 12, true, MAP, new int[]{45, 53, 38});
        List<String> l = lines(r);
        assertEquals(List.of(
                "leg 0 40 0 within 8 (the mine entrance)",
                "leg 0 40 -40 within 8 (the corridor end)",
                "leg 0 40 -80 within 8 (the corridor end)",
                "leg 0 40 -87 within 6 (the corridor end)"), l.subList(0, 4));
        // (branch 30 reaches z -90, past the mapped area's z -64: nothing is collected there)
        assertEquals("dig corridor 30 0 40 -90 to 0 41 -88 keepOres=false dump=mine torches=[0 40 -90] 'digging the mine corridor (branch 30)'", l.get(5));
        for (int i = 0; i < 4; i++) assertEquals(2, r.items().get(i).leg.tries());
    }

    @Test
    void legsOnlyWhenFarOrLevelsOff() {
        assertTrue(MINE.legs(2, new int[]{0, 40, -3}).isEmpty());
        assertTrue(MINE.legs(2, new int[]{30, 45, 20}).isEmpty(), "within 48 and 10 levels");
        assertEquals(1, MINE.legs(1, new int[]{0, 60, 0}).size(), "branch 1 from 20 levels up: only the entrance");
        assertEquals(2, MINE.legs(2, new int[]{0, 60, 0}).size(), "branch 2: the entrance, then the corridor end");
    }
}
