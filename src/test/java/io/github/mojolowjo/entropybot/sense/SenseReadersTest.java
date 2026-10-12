package io.github.mojolowjo.entropybot.sense;

import io.github.mojolowjo.entropybot.brain.BrainState;
import io.github.mojolowjo.entropybot.brain.Feasibility;
import io.github.mojolowjo.entropybot.gather.GatherSources;
import io.github.mojolowjo.entropybot.poi.Pois;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 0.28.0: the readers of the pass outside sense/: POIs gone, gather's ore source order, the brain's feasibility. */
class SenseReadersTest {
    @Test
    void poiGoneUnlessAnotherOfItsKindIsNear() {
        Pois p = new Pois();
        Pois.Poi a = p.saw("spawner", 10, 20, 30, "minecraft:overworld", 1, 1, null);
        p.saw("village", 100, 64, 100, "minecraft:overworld", 1, 1, null);
        assertNull(p.gone("village", 101, 64, 100, "minecraft:overworld", 2, x -> true), "a second bell near: kept");
        assertEquals(2, p.size());
        assertEquals(a.id(), p.gone("spawner", 10, 20, 30, "minecraft:overworld", 2, x -> false).id());
        assertEquals(1, p.size());
        assertNull(p.gone("spawner", 10, 20, 30, "minecraft:the_nether", 2, null));
    }

    @Test
    void gatherMinesKnownOreFirst() {
        GatherSources.Source iron = GatherSources.resolve("raw_iron", Map.of());
        assertEquals(List.of("mine iron_ore 5", "mine strip iron 5", "mine cave iron 5 10m"), GatherSources.commands(iron, 5, true, false, true));
        assertEquals(List.of("mine strip iron 5", "mine iron_ore 5", "mine cave iron 5 10m"), GatherSources.commands(iron, 5, true, false, false));
    }

    @Test
    void feasibilityAsksTheIndex() {
        BrainState s = new BrainState();
        s.feas.known = true;
        s.feas.pickTier = 3;
        s.feas.oreKnown = fam -> fam.equals("coal");
        assertTrue(Feasibility.check("mine iron_ore 4", s).infeasible());
        assertEquals("no iron ore known within 64 in my areas", Feasibility.check("mine iron_ore 4", s).missing());
        assertFalse(Feasibility.check("mine coal_ore 4", s).infeasible());
        assertFalse(Feasibility.check("mine sand 4", s).infeasible(), "not an ore: not asked");
        assertFalse(Feasibility.check("mine strip iron 4", s).infeasible() && Feasibility.check("mine strip iron 4", s).missing().contains("known"));
        s.feas.oreKnown = null;
        assertFalse(Feasibility.check("mine iron_ore 4", s).infeasible(), "index not ready: as before");
    }
}
