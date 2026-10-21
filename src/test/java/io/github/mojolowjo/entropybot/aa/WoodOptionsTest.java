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
    void aSeenTreeIsAWalkThereAndBackThenTheCut() {
        Options.OptionMemory m = new Options.OptionMemory();
        Options.Choice c = WoodOptions.choose(new WoodFacts(4, List.of(tree(50, 50, null))), m, 0);
        assertEquals(1, c.ranked().size(), c.line());
        assertTrue(c.best().id().startsWith("chop at"), c.line());
        assertEquals("goto 50 64 0 near 2 > cut 4 at 50 64 0", c.best().command(), "no area command: the world is open");
        assertTrue(c.best().why().contains("there and back"), c.best().why());
        assertNotNull(c.best().site());
        // rm-execution: typed steps (the walk to the seen tree, then Baritone's tree mining), no chain text
        assertEquals(List.of(new io.github.mojolowjo.entropybot.aa.moves.Step.Walk(50, 64, 50, 2).getClass(), io.github.mojolowjo.entropybot.aa.moves.Step.ChopTree.class),
                c.best().steps().stream().map(Object::getClass).toList());
        assertFalse(c.best().command().contains(" then "), c.best().command());
    }
    @Test
    void failedOptionIsHeldAndTheNextTreeOrOptionRuns() {
        Options.OptionMemory m = new Options.OptionMemory();
        List<Tree> trees = List.of(tree(50, 50, null), tree(80, 80, null));
        Options.Choice c = WoodOptions.choose(new WoodFacts(4, trees), m, 0);
        m.failed(c.best().memoryKey(), "error: chopped nothing - no trees within 32", 0);
        Options.Choice d = WoodOptions.choose(new WoodFacts(4, trees), m, 1000);
        assertNotNull(d.best());
        assertNotEquals(c.best().id(), d.best().id(), "the held option is not offered again");
        assertTrue(d.line().contains("dropped"), d.line());
        Options.Choice later = WoodOptions.choose(new WoodFacts(4, trees), m, Options.retryMs() + 1);
        assertTrue(later.ranked().stream().anyMatch(o -> o.id().equals(c.best().id())), "after the hold, offered again (at the failure's price)");
        assertTrue(later.ranked().stream().filter(o -> o.id().equals(c.best().id())).findFirst().get().total() > c.best().total());
    }

    @Test
    void noTreeIsAnOptionDropWithAReason() {
        Options.OptionMemory m = new Options.OptionMemory();
        Options.Choice c = WoodOptions.choose(new WoodFacts(4, List.of()), m, 0);
        assertEquals("cut 4", c.best().command(), "one priced try of the plain cut");
        m.failed(c.best().memoryKey(), "error: chopped nothing - no trees within 32", 0);
        Options.Choice d = WoodOptions.choose(new WoodFacts(4, List.of()), m, 60_000);
        assertNull(d.best(), "held: no task-fail loop");
        assertTrue(d.line().contains("no tree seen"), d.line());
    }

    @Test
    void rulesDropRows() {
        Options.OptionMemory m = new Options.OptionMemory();
        assertEquals("cut 4", WoodOptions.choose(new WoodFacts(4, List.of(tree(50, 50, "a protected build is near"))), m, 0).best().command(), "every tree denied: only the plain cut is left");
        Options.Choice far = WoodOptions.choose(new WoodFacts(4, List.of(tree(300, 300, null))), m, 0);
        assertEquals("cut 4", far.best().command(), "past FAR_MAX: the plain cut");
        Options.Choice ok = WoodOptions.choose(new WoodFacts(4, List.of(tree(200, 200, null))), m, 0);
        assertTrue(ok.best().id().startsWith("chop at"), "anywhere in reach: the world is open");
    }
    @Test
    void ironChainThatSmeltsWhatItLacksGetsAnOreStep() {
        IronOptions.Facts none = facts(0);
        var chain = io.github.mojolowjo.entropybot.aa.TS.s("get stone_pickaxe 1 then smelt iron_ingot 16");
        assertEquals("get stone_pickaxe 1 > mine iron 16 > smelt iron_ingot 16", TS.label(IronOptions.withOreStep(chain, none)));
        assertEquals("get stone_pickaxe 1 > mine iron 10 > smelt iron_ingot 16", TS.label(IronOptions.withOreStep(chain, facts(6))));
        assertSame(chain, IronOptions.withOreStep(chain, facts(16)), "enough raw iron: as it is");
        assertSame(chain, IronOptions.withOreStep(chain, facts(-1)), "bag unknown: as it is");
        var withMine = io.github.mojolowjo.entropybot.aa.TS.s("mine iron 16 then smelt iron_ingot 16");
        assertSame(withMine, IronOptions.withOreStep(withMine, none));
        // no ore seen, no cave, no mine: no instant "missing raw_iron" - no option, the reasons listed
        Options.Choice c = new IronOptions.Set().choose(none, new Options.OptionMemory(), 0, io.github.mojolowjo.entropybot.aa.TS.s("smelt iron_ingot 16"), 5, null, null);
        assertNull(c.best());
        assertTrue(c.line().contains("no way to iron"), c.line());
    }

    @Test
    void missingRawIronIsChargedToTheOreWay() {
        IronOptions.Set set = new IronOptions.Set();
        Options.Option ran = new Options.Option("cave", "cave", TS.s("find cave 5 then mine cave iron 16 then smelt iron_ingot 16"), 100, "x", "CAVE", true);
        String end = "stopped at step 3 (smelt iron_ingot 16): can't smelt 16 iron_ingot - missing 16 raw_iron";
        assertEquals("CAVE", set.blame(null, ran, end));
        assertTrue(set.switchable(end));
        assertEquals("ore", IronOptions.failedPart("stopped at step 1 (find cave 5): no cave"));
    }

    @Test
    void plainCutIsTheFallbackWhenEveryTreeIsDenied() {
        Options.Choice c = WoodOptions.choose(new WoodFacts(4, List.of(tree(50, 50, "water between"))), new Options.OptionMemory(), 0);
        assertNotNull(c.best());
        assertEquals("cut 4", c.best().command());
    }

    @Test
    void ownerStopIsNotSwitchable() {
        assertFalse(WoodOptions.switchable("stopped by the owner"));
    }
    static IronOptions.Facts facts(int rawIron) {
        return new IronOptions.Facts(List.of(), 60, false, 0, true, -1, false, "no mine marked (place mine)", -1, false, false, 0, null, null, null, rawIron);
    }
}

