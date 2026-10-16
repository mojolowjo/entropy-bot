package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.sense.KnownOre;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 0.30.7 (package 8): the corpse detour (item 1), the climb target (item 4), the empty ore index end (item 5). */
class Pkg8Test {
    @Test void corpseInWayAsksForTheFetch() {
        assertTrue(Chains.corpseInWay("couldn't place the furnace at 34 131 23: my corpse is in the way at 34 131 23 - next: death"));
        assertFalse(Chains.corpseInWay("couldn't place the furnace at 34 131 23: a boat is in the way at 34 131 23 - next: move it"));
        assertFalse(Chains.corpseInWay(null));
    }

    @Test void climbTargetCampThenBaseThenArea() {
        int[] camp = {1, 70, 2}, base = {5, 72, 5}, area = {9, 64, 9};
        assertEquals("the camp", ClimbFallback.target(camp, base, area, "arena").what());
        assertEquals("the base", ClimbFallback.target(null, base, area, "arena").what());
        ClimbFallback.Target t = ClimbFallback.target(null, null, area, "arena");
        assertEquals("area arena (its column at 9 9)", t.what());
        assertArrayEquals(area, t.pos());
        assertNull(ClimbFallback.target(null, null, null, null));
    }

    @Test void nearestAreaColumn() {
        int[] here = {0, 40, 0};
        List<int[]> boxes = List.of(new int[]{100, 100, 120, 120}, new int[]{10, -5, 20, 5}, new int[]{-3, -3, 3, 3});
        int[] c = ClimbFallback.nearestColumn(here, boxes);
        assertArrayEquals(new int[]{10, 0, 1}, c, "the box holding me is skipped; the nearest edge column of the next");
        assertNull(ClimbFallback.nearestColumn(here, List.of(new int[]{-3, -3, 3, 3})));
        assertNull(ClimbFallback.nearestColumn(here, List.of()));
    }

    @Test void climbStartTexts() {
        assertEquals("climb: my own way failed twice, digging stairs up toward the base (12 blocks up, west)", ClimbFallback.startText("the base", "west", 12));
        assertEquals("climb: my own way failed twice, digging stairs up north (5 blocks up; no camp, base or area known)", ClimbFallback.startText((String) null, "north", 5));
        assertEquals("climb: my own way failed twice, digging stairs up toward the camp (15 blocks up, east)", ClimbFallback.startText(true, "east", 15));
    }

    @Test void emptyIndexEnd() {
        assertEquals("done: mining iron: got 0 of 5 - no iron ore known yet (walked 0 blocks) - next: sense (the chunk pass) or mine strip iron",
                KnownOre.noneYetText("iron", 0, 5, 0, ""));
        assertEquals("done: mining coal: got 1 of 5 - no coal ore known yet (walked 1 block), got 1 coal - next: sense (the chunk pass) or mine strip coal",
                KnownOre.noneYetText("coal", 1, 5, 1, ", got 1 coal"));
        assertEquals(32, KnownOre.NONE_WALK_CAP);
    }
}
