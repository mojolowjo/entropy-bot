package io.github.mojolowjo.entropybot.aa.brain;

import io.github.mojolowjo.entropybot.aa.core.Inputs;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HelpTest {
    static Inputs owner(int dist, String act) {
        Inputs in = new Inputs();
        in.ownerOnline = true;
        in.ownerDist = dist;
        in.ownerAct = act;
        return in;
    }

    @Test void offWhenOfflineFarUnseenOrAssist() {
        Inputs in = owner(5, "mining");
        in.ownerHostiles = 2;
        in.ownerOnline = false;
        assertNull(Help.pick(in, 20));
        in.ownerOnline = true;

        in.ownerDist = 60;
        assertNull(Help.pick(in, 20));
        in.ownerDist = -1;
        assertNull(Help.pick(in, 20));
        assertNull(Help.pick(owner(5, "mining"), 0));
    }

    @Test void monstersNearOwnerFirstNeverUrgent() {
        Inputs in = owner(30, null);
        in.ownerHostiles = 1;
        Help.Pick p = Help.pick(in, 20);
        assertEquals("fight", p.what());
        assertEquals(50, p.score(), 1e-9);
        in.ownerHostiles = 9;
        assertEquals(70, Help.pick(in, 20).score(), 1e-9);
        assertTrue(Help.pick(in, 37).score() < 75);
    }

    @Test void buildingGivesFromBagElseFetches() {
        Inputs in = owner(4, "building");
        in.ownerPlaced = "minecraft:oak_planks";
        in.bag = Map.of("oak_planks", 50);
        Help.Pick p = Help.pick(in, 20);
        assertEquals("give oak_planks 32", io.github.mojolowjo.entropybot.aa.TS.label(p.steps()));
        assertEquals(30, p.score(), 1e-9);
        in.bag = Map.of("oak_planks", 3);
        p = Help.pick(in, 20);
        assertEquals("fetch", p.what());
        assertEquals("obtain 32 oak_planks > give oak_planks 32", io.github.mojolowjo.entropybot.aa.TS.label(p.steps()));
    }

    @Test void everyPickRunsOnlyVerbsThatExist() {
        java.util.List<Help.Pick> picks = new java.util.ArrayList<>();
        Inputs a = owner(4, "building");
        a.ownerPlaced = "minecraft:oak_planks";
        a.bag = Map.of("oak_planks", 50);
        picks.add(Help.pick(a, 20));
        a.bag = Map.of("oak_planks", 3);
        picks.add(Help.pick(a, 20));
        Inputs f = owner(4, "mining");
        f.ownerHostiles = 2;
        picks.add(Help.pick(f, 20));
        for (Help.Pick p : picks) {
            assertNotNull(p);
            assertFalse(p.steps().isEmpty(), p.line());
        }
    }

    @Test void darkLightsThenFollowsWhenFar() {
        Inputs in = owner(30, "mining");
        in.ownerDark = true;
        in.torches = 10;
        assertEquals("come > light here 8", io.github.mojolowjo.entropybot.aa.TS.label(Help.pick(in, 20).steps()));
        in.torches = 2;
        assertEquals("come", io.github.mojolowjo.entropybot.aa.TS.label(Help.pick(in, 20).steps()));
        in.ownerDist = 10;
        assertNull(Help.pick(in, 20));
    }

    @Test void idleOwnerHalvesAndIdleIsNotBusy() {
        Inputs in = owner(30, "idle");
        in.ownerDark = true;
        in.torches = 10;
        assertNull(Help.pick(in, 20));           // idle: no light, no follow
        Inputs b = owner(4, null);
        b.ownerAct = "building";
        b.ownerPlaced = "minecraft:stone";
        b.bag = Map.of("stone", 64);
        assertEquals(30, Help.pick(b, 20).score(), 1e-9);
        assertEquals(1, Help.cap(1), 1e-9);
        assertEquals(-1, Help.cap(0.9), 1e-9);
    }
}
