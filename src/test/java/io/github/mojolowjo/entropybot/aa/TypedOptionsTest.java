package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * rm-execution: AA's wood, food (hunt) and iron option sets emit typed steps for aa/moves/Steps; none hands chain text
 * to the old job interpreter (a typed option's command is only the steps' label, never parsed back).
 */
@org.junit.jupiter.api.extension.ExtendWith(io.github.mojolowjo.entropybot.aa.brain.VanillaData.class)
class TypedOptionsTest {
    static void typed(Options.Option o) {
        assertTrue(o.typed(), o.id() + ": typed steps");
        assertFalse(o.command().contains(" then "), o.id() + ": no chain text: " + o.command());
        assertEquals(Step.label(o.steps()), o.command(), "the command is the steps' label");
    }

    @Test
    void woodEmitsAWalkAndBaritonesTreeMining() {
        Options.Choice c = WoodOptions.choose(new WoodOptions.WoodFacts(6, List.of(new WoodOptions.Tree(40, 70, -12, 30, null))), new Options.OptionMemory(), 0);
        typed(c.best());
        assertInstanceOf(Step.Walk.class, c.best().steps().get(0));
        Step.ChopTree t = (Step.ChopTree) c.best().steps().get(1);
        assertArrayEquals(new int[]{40, 70, -12}, t.at(), "the tree's cell comes from Sightings, no mod-side column scan");
        assertEquals(6, t.logs());
        Options.Choice none = WoodOptions.choose(new WoodOptions.WoodFacts(3, List.of()), new Options.OptionMemory(), 0);
        typed(none.best());
        assertEquals(List.of(new Step.ChopTree(null, 3, null)).toString(), none.best().steps().toString());
    }

    @Test
    void foodHuntRowsAreTypedHuntSteps() {
        Options.Choice c = FoodOptionsTest.choose(FoodOptionsTest.withHerds(Map.of("cow", 4), List.of()));
        Options.Option hunt = c.ranked().stream().filter(o -> o.way().equals("hunt")).findFirst().orElseThrow();
        typed(hunt);
        Step.HuntAt h = (Step.HuntAt) hunt.steps().get(0);
        assertEquals("minecraft:cow", h.type());
        assertNull(h.at(), "the animals in view: no place");
    }

    @Test
    void ironRowsAreTypedOreAndSmeltSteps() {
        IronOptions.Facts f = IronOptionsTest.facts(List.of(IronOptionsTest.open(10, 60, 0, 10, true), IronOptionsTest.buried(5, 50, 0, 5, 12, false)), 60);
        Options.Choice c = new IronOptions.Set().choose(f, new Options.OptionMemory(), 0, IronOptionsTest.PLANNED, 10, null, null);
        assertFalse(c.ranked().isEmpty(), c.why());
        for (Options.Option o : c.ranked()) typed(o);
        Options.Option walk = c.ranked().stream().filter(o -> o.id().startsWith("walk_known")).findFirst().orElseThrow();
        assertEquals(new Step.MineAt("iron", 10, 60, 0, false), walk.steps().get(0));
        assertTrue(c.best().steps().stream().anyMatch(s -> s instanceof Step.MineAt));
    }

    @Test
    void aPlannerStepWithNoTypedStepDropsTheRowWithItsReason() {
        IronOptions.Facts f = IronOptionsTest.facts(List.of(IronOptionsTest.open(10, 60, 0, 10, true)), 60);
        Options.Choice c = new IronOptions.Set().choose(f, new Options.OptionMemory(), 0, "frobnicate 2 then mine iron 3", 10, null, null);
        assertNull(c.best(), "never handed on as text");
        assertTrue(String.join("; ", c.dropped()).contains("\"frobnicate 2\" has no typed step"), c.dropped().toString());
    }

    @Test
    void thePlannersWordsParseToTypedSteps() {
        assertEquals(new Step.Craft("iron_pickaxe", 1), PlanSteps.one("craft iron_pickaxe 1"));
        assertEquals(new Step.SmeltCollect(), PlanSteps.one("smelt collect all"));
        assertEquals(new Step.Mine("iron", 3, Step.Mine.How.STRIP), PlanSteps.one("mine strip iron 3"));
        assertEquals(new Step.FindCave(5), PlanSteps.one("find cave 5"));
        assertEquals(new Step.Restore(true, 0), PlanSteps.one("restore keep pillar"));
        assertEquals("dig 1 2 3 1 4 3 ores via engine", PlanSteps.one("dig 1 2 3 1 4 3 ores via engine").text());
        assertEquals(new Step.ChopTree(null, 4, null).text(), PlanSteps.one("cut 4 logs").text());
        assertNull(PlanSteps.one("goal bed"));
    }
}
