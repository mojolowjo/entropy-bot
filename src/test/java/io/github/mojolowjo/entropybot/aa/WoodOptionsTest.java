package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.WoodOptions.Tree;
import io.github.mojolowjo.entropybot.aa.WoodOptions.WoodFacts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** aa-trees: the wood options over the trees seen, and the iron chain that must not smelt what it lacks. */
class WoodOptionsTest {
    static Tree tree(int x, double dist, String deny) { return new Tree(x, 64, 0, dist, deny); }

    @Test
    void treesFiftyOutGiveExtendCheaperThanChopAt() {
        Options.OptionMemory m = new Options.OptionMemory();
        Options.Choice c = WoodOptions.choose(new WoodFacts(4, List.of(tree(50, 50, null)), 0, true), m, 0);
        assertEquals(2, c.ranked().size());
        assertTrue(c.best().id().startsWith("extend wood area"), c.line());
        assertEquals("area 30 -20 70 20 trees1 work neutral then cut 4", c.best().command());
        assertTrue(c.ranked().get(1).command().startsWith("area 42 -8 58 8 treeat work neutral then cut 4"));
        assertTrue(c.ranked().get(1).total() > c.best().total(), "chop at counts the way back");
        assertNotNull(c.best().site());
    }

    @Test
    void failedOptionIsHeldAndTheNextTreeOrOptionRuns() {
        Options.OptionMemory m = new Options.OptionMemory();
        List<Tree> trees = List.of(tree(50, 50, null), tree(80, 80, null));
        Options.Choice c = WoodOptions.choose(new WoodFacts(4, trees, 0, true), m, 0);
        m.failed(c.best().memoryKey(), "error: chopped nothing - no trees in my areas", 0);
        Options.Choice d = WoodOptions.choose(new WoodFacts(4, trees, 0, true), m, 1000);
        assertNotNull(d.best());
        assertNotEquals(c.best().id(), d.best().id(), "the held option is not offered again");
        assertTrue(d.line().contains("dropped"), d.line());
        Options.Choice later = WoodOptions.choose(new WoodFacts(4, trees, 0, true), m, Options.RETRY_MS + 1);
        assertTrue(later.ranked().stream().anyMatch(o -> o.id().equals(c.best().id())), "after the hold, offered again (at the failure's price)");
        assertTrue(later.ranked().stream().filter(o -> o.id().equals(c.best().id())).findFirst().get().total() > c.best().total());
    }

    @Test
    void noTreeIsAnOptionDropWithAReason() {
        Options.OptionMemory m = new Options.OptionMemory();
        Options.Choice c = WoodOptions.choose(new WoodFacts(4, List.of(), 0, true), m, 0);
        assertEquals("cut 4", c.best().command(), "one priced try of the plain cut");
        m.failed(c.best().memoryKey(), "error: chopped nothing - no trees in my areas", 0);
        Options.Choice d = WoodOptions.choose(new WoodFacts(4, List.of(), 0, true), m, 60_000);
        assertNull(d.best(), "held: no task-fail loop");
        assertTrue(d.line().contains("no tree seen"), d.line());
    }

    @Test
    void rulesDropRows() {
        Options.OptionMemory m = new Options.OptionMemory();
        assertNull(WoodOptions.choose(new WoodFacts(4, List.of(tree(50, 50, "a protected build is near")), 0, true), m, 0).best());
        Options.Choice cap = WoodOptions.choose(new WoodFacts(4, List.of(tree(50, 50, null)), WoodOptions.AREA_CAP, true), m, 0);
        assertTrue(cap.best().id().startsWith("chop at"), "the area cap leaves chop at");
        Options.Choice mp = WoodOptions.choose(new WoodFacts(4, List.of(tree(50, 50, null)), 0, false), m, 0);
        assertTrue(mp.best().id().startsWith("extend"));
        assertEquals(1, mp.ranked().size(), "outside logs not allowed: only the area");
        Options.Choice far = WoodOptions.choose(new WoodFacts(4, List.of(tree(200, 200, null)), 0, true), m, 0);
        assertTrue(far.best().id().startsWith("chop at"), "past the reach only chop at");
        assertTrue(WoodOptions.isWoodCommand(far.best().command()));
    }

    @Test
    void ironChainThatSmeltsWhatItLacksGetsAnOreStep() {
        IronOptions.Facts none = facts(0);
        List<String> chain = IronOptions.split("fetch stone_pickaxe 1 then smelt iron_ingot 16");
        assertEquals(List.of("fetch stone_pickaxe 1", "mine iron 16", "smelt iron_ingot 16"), IronOptions.withOreStep(chain, none));
        assertEquals(List.of("fetch stone_pickaxe 1", "mine iron 10", "smelt iron_ingot 16"), IronOptions.withOreStep(chain, facts(6)));
        assertSame(chain, IronOptions.withOreStep(chain, facts(16)), "enough raw iron: as it is");
        assertSame(chain, IronOptions.withOreStep(chain, facts(-1)), "bag unknown: as it is");
        List<String> withMine = IronOptions.split("mine iron 16 then smelt iron_ingot 16");
        assertSame(withMine, IronOptions.withOreStep(withMine, none));
        // no ore seen, no cave, no mine: no instant "missing raw_iron" - no option, the reasons listed
        Options.Choice c = new IronOptions.Set().choose(none, new Options.OptionMemory(), 0, "smelt iron_ingot 16", 5, null, null);
        assertNull(c.best());
        assertTrue(c.line().contains("no way to iron"), c.line());
    }

    @Test
    void missingRawIronIsChargedToTheOreWay() {
        IronOptions.Set set = new IronOptions.Set();
        Options.Option ran = new Options.Option("cave", "cave", "find cave 5 then mine cave iron 16 then smelt iron_ingot 16", 100, "x", "CAVE", true);
        String end = "stopped at step 3 (smelt iron_ingot 16): can't smelt 16 iron_ingot - missing 16 raw_iron";
        assertEquals("CAVE", set.blame(null, ran, end));
        assertTrue(set.switchable(end));
        assertEquals("ore", IronOptions.failedPart("stopped at step 1 (find cave 5): no cave"));
    }

    static IronOptions.Facts facts(int rawIron) {
        return new IronOptions.Facts(List.of(), 60, false, 0, true, -1, false, "no mine marked (place mine)", -1, false, false, 0, null, null, null, rawIron);
    }
}
