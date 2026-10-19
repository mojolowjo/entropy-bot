package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Brain;
import io.github.mojolowjo.entropybot.aa.brain.NightWork;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** to-do 1d: night work from inside the shelter (the pure decision). */
class NightWorkTest {
    final Blackboard bb = new Blackboard(new Clock.Fake(10_000_000), new FakePort(), new Tracer());

    void night() {
        bb.in.hungerDrains = true;
        bb.in.dayTime = 18000;
        bb.in.sheltered = true;
        bb.in.pickTier = 2;
        bb.in.pickPct = 80;
    }

    @Test
    void desireIsRegistered() {
        night();
        assertTrue(AaDesires.night().stream().anyMatch(x -> x.id.equals("night.work")));
    }

    @Test
    void scoreOnlyWhenShelteredAtHostileNightWithWork() {
        night();
        assertEquals(NightWork.SCORE, NightWork.score(bb));
        assertTrue(NightWork.SCORE > Brain.URGENT, "ends the shelter's wait");
        bb.in.sheltered = false;
        assertEquals(-1, NightWork.score(bb), "not in a shell");
        night();
        bb.in.dayTime = 6000;
        assertEquals(-1, NightWork.score(bb), "dawn: climb out, carry on");
        night();
        bb.in.hungerDrains = false;
        assertEquals(-1, NightWork.score(bb), "Peaceful");
        night();
        bb.in.pickTier = 0;
        assertEquals(NightWork.Work.NONE, NightWork.pick(bb.in));
        assertEquals(-1, NightWork.score(bb), "no pickaxe, nothing to do: it waits");
    }

    @Test
    void picksSmeltThenCraftThenStairs() {
        night();
        assertEquals(NightWork.Work.STAIRS, NightWork.pick(bb.in));
        bb.in.torches = 2; bb.in.coal = 4; bb.in.planks = 8; bb.in.tableInBag = true;
        assertEquals(NightWork.Work.CRAFT, NightWork.pick(bb.in));
        assertEquals("craft torch 16", NightWork.craft(bb.in));
        bb.in.ironIngots = 3;
        assertEquals("craft iron_pickaxe", NightWork.craft(bb.in));
        bb.in.bag = Map.of("raw_iron", 5);
        bb.in.furnaceInBag = true;
        assertEquals(NightWork.Work.SMELT, NightWork.pick(bb.in));
        assertEquals("smelt iron_ingot 5 here", NightWork.chain(bb.in, NightWork.Work.SMELT, 0, 64, 0, 0, -1));
    }

    @Test
    void stairsDigDownThroughTheFloorFirstNeverThroughTheWall() {
        var s = NightWork.stairs(10, 64, 20, 0, -1);
        assertEquals("dig 10 62 20 10 63 20 ores via engine", s.get(0), "the shaft is straight under the feet");
        assertEquals("goto 10 62 20", s.get(1));
        assertTrue(s.get(2).startsWith("dig 10 61 19 10 63 19"), "first stair column is below the wall's foot: " + s.get(2));
    }
}
