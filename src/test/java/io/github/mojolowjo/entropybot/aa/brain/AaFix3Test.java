package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.vocab.HuntRules;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Overnight 2026-10-19 fixes: climb out at dawn, the coal goal's ways and reserve, the urgent hunt outside the areas. */
class AaFix3Test {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final Blackboard bb = new Blackboard(clock, new FakePort(), new Tracer());

    static final Thing.Result COAL = new Thing.Result("coal", "coal", true, java.util.List.of("minecraft:coal_ore"),
            java.util.List.of("coal_ore", "coal"), 1, null, null, null);

    @Test
    void climbsOutAtDawnAfterDiggingDownAtNight() {
        bb.in.hungerDrains = true;
        bb.in.dayTime = 18_000;
        bb.in.underDepth = 33;
        assertFalse(NightWork.climbAtDawn(bb, 3), "nothing dug yet");
        NightWork.noteDug(bb, NightWork.Work.CRAFT);
        assertFalse(NightWork.climbAtDawn(bb, 3), "a craft digs nothing");
        NightWork.noteDug(bb, NightWork.Work.STAIRS);
        assertFalse(NightWork.climbAtDawn(bb, 3), "still night: stay down");
        bb.in.dayTime = 0;
        assertTrue(NightWork.climbAtDawn(bb, 3), "dawn, 33 under: climb");
        bb.in.underDepth = 1;
        assertFalse(NightWork.climbAtDawn(bb, 3), "out");
        bb.in.underDepth = 33;
        assertFalse(NightWork.climbAtDawn(bb, 3), "the note was cleared at the surface");
    }

    @Test
    void coalGoalUsesTheIronStyleWays() {
        var cat = BrainB5Test.withLearned(null, 80);
        bb.in.pickTier = 2;
        Goals.Goal g = Goals.add(bb, COAL, 16, 0, clock.now());
        assertEquals("mine strip coal 16", Goals.chain(bb, g), "no facts: as before");
        bb.fact(Goals.WAYS_FACT, new Goals.Ways(-1, false, true));
        assertEquals("place mine north then mine strip coal 16", Goals.chain(bb, g), "no mine marked: place one in my areas");
        bb.fact(Goals.WAYS_FACT, new Goals.Ways(-1, true, true));
        assertEquals("mine strip coal 16", Goals.chain(bb, g));
        bb.fact(Goals.WAYS_FACT, new Goals.Ways(30, true, true));
        assertEquals("mine cave coal 16", Goals.chain(bb, g), "a known cave first");
        bb.fact(Goals.WAYS_FACT, new Goals.Ways(-1, false, false));
        assertEquals("explore 5", Goals.chain(bb, g), "outside my areas: look around");
        bb.fact(Goals.KNOWN_FACT, Map.of(g.r.key(), 5));
        assertEquals("mine coal 16 dig", Goals.chain(bb, g), "known ore: dig to it");
    }

    @Test
    void coalGoalKeepsFourCoalFromTorches() {
        var cat = BrainB5Test.withLearned(null, 80);
        assertEquals(0, Goals.coalReserve(bb));
        Goals.add(bb, Thing.resolve("iron", cat), 8, 0, clock.now());
        assertEquals(0, Goals.coalReserve(bb), "not a coal goal");
        Goals.add(bb, COAL, 16, 0, clock.now());
        assertEquals(Goals.COAL_RESERVE, Goals.coalReserve(bb));
        bb.in.coalReserve = Goals.coalReserve(bb);
        bb.in.coal = 4;
        assertFalse(bb.in.torchCoal());
        bb.in.torches = 0; bb.in.planks = 4; bb.in.tableInBag = true;
        assertNull(NightWork.craft(bb.in), "no torches from the reserve");
        bb.in.coal = 5;
        assertTrue(bb.in.torchCoal());
        assertEquals("craft torch 16", NightWork.craft(bb.in));
    }

    @Test
    void urgentHuntMayGoOut48() {
        assertTrue(HuntRules.urgentOut(6, 20));
        assertTrue(HuntRules.urgentOut(18, 9));
        assertFalse(HuntRules.urgentOut(7, 10));
        assertTrue(HuntRules.outOk(true, 48));
        assertFalse(HuntRules.outOk(true, 49));
        assertFalse(HuntRules.outOk(false, 5));
        assertTrue(HuntRules.outWord("4 cow out"));
        assertFalse(HuntRules.outWord("4 cow"));
        assertArrayEquals(new Object[]{4, "minecraft:cow"}, HuntRules.parse("4 cow out"));
    }
}
