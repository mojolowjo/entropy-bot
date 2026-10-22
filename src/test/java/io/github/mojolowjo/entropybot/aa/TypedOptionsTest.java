package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * rm-execution + rm-execution2: every option set and desire of AA emits typed steps for aa/moves/Steps; none hands chain
 * text to the old job interpreter (an option's command is only the steps' label, never parsed back). one-planner: a
 * need's "I need N of X" runs as one {@link Step.Obtain} the machine expands into the chain's steps.
 */
@org.junit.jupiter.api.extension.ExtendWith(io.github.mojolowjo.entropybot.aa.brain.VanillaData.class)
class TypedOptionsTest {
    static final int[] ME = {0, 64, 0};

    static void typed(Options.Option o) {
        assertTrue(o.typed(), o.id() + ": typed steps");
        assertFalse(o.command().contains(" then "), o.id() + ": no chain text: " + o.command());
        assertEquals(Step.label(o.steps()), o.command(), "the command is the steps' label");
        for (Step s : o.steps()) runnable(s);
    }

    /** A step the game side runs: its command is a verb the bot knows (an Obtain / compound is expanded by Steps first). */
    static void runnable(Step s) {
        if (s instanceof Step.Obtain || s instanceof Step.ToBand || s instanceof Step.Compound || s instanceof Step.Fish || s instanceof Step.KelpCut) return;     // fishing and kelp cuts have hands of their own, no verb
        String cmd = io.github.mojolowjo.entropybot.commands.StepCommands.command(s);
        assertNotNull(io.github.mojolowjo.entropybot.commands.VerbTable.of(cmd.split("\\s+")[0]), "no verb for " + cmd);
    }

    static void allTyped(Options.Choice c) {
        assertFalse(c.ranked().isEmpty(), c.why());
        for (Options.Option o : c.ranked()) typed(o);
    }

    @Test
    void theChainsStepsAreRunnable() {
        io.github.mojolowjo.entropybot.aa.brain.TestData.planner().tag("wooden_pickaxe", "minecraft:pickaxes").tag("stone_pickaxe", "minecraft:pickaxes").install();
        NeedChain.Spot tree = new NeedChain.Spot(10, 64, 0, 10, "minecraft:oak_log", 6), stone = new NeedChain.Spot(0, 60, 16, 16, "minecraft:stone", 40);
        NeedChain.Chain c = NeedChain.resolve("stone_pickaxe", 1, new NeedChain.World(Map.of(), false, w -> w.equals("minecraft:oak_log") ? tree : w.equals("minecraft:cobblestone") ? stone : null));
        assertTrue(c.ok() && !c.steps().isEmpty(), c.why());
        for (Step s : c.steps()) runnable(s);
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.ChopTree t && java.util.Arrays.equals(t.at(), new int[]{10, 64, 0})), "the tree's cell comes from the facts, no mod-side scan: " + c.steps());
    }

    @Test
    void ironOreStepsAreTyped() {
        IronOptions.Facts f = IronOptionsTest.facts(List.of(IronOptionsTest.open(10, 60, 0, 10, true), IronOptionsTest.buried(5, 50, 0, 5, 12, false)), 60);
        IronOptions.Choice c = IronOptions.choose(f, new IronOptions.Memory(), 0);
        for (Step s : c.steps("iron", 3)) runnable(s);
        assertEquals(new Step.MineAt("iron", 10, 60, 0, false), c.steps("iron", 3).get(0), "the owner's ore word names the step, the scorer the cell");
    }

    @Test
    void bedBaseBagRowsAreTyped() {
        Options.Choice base = BaseOptions.choose(new BaseOptions.BaseFacts("home", null, 20, true, 1, true, 5, true), new Options.OptionMemory(), 0);
        allTyped(base);
        Options.Option burrow = base.ranked().stream().filter(o -> o.id().equals("burrow")).findFirst().orElseThrow();
        assertEquals(List.of(new Step.Burrow(), new Step.Wait(120)), burrow.steps(), "no 'burrow then wait 120' text: two typed steps, the wait from Tuning");
        Options.Option house = base.ranked().stream().filter(o -> o.id().startsWith("house")).findFirst().orElseThrow();
        assertEquals(List.of(new Step.Climb(), new Step.House("home")), house.steps(), "under ground the climb comes first");
        allTyped(BagOptions.choose(new BagOptions.BagFacts(0, 1, true, 5, ME, false, 8, 0, ME, 1, List.of(new Step.DropJunk("dirt", 3))), new Options.OptionMemory(), 0));
        Options.Choice chest = BagOptions.choose(new BagOptions.BagFacts(0, 1, false, -1, null, false, 8, 0, ME, 0, List.of()), new Options.OptionMemory(), 0);
        allTyped(chest);
        assertEquals(List.of(new Step.Craft("chest", 1), new Step.PlaceNear("chest"), new Step.MarkBase(0, 64, 0), new Step.Scan(true), new Step.Deposit()), chest.best().steps());
    }

    @Test
    void wayUpRowsAreTyped() {
        var f = new RungOptions.SurfaceFacts(10, ME, true, 20, true, true, new int[]{20, 70, 5}, 25, false);
        allTyped(WayUp.choose(f, new Options.OptionMemory(), 0));
    }

    @Test
    void dangerWaysAreTypedNoWaitTextAfterAMove() {
        for (DangerOptions.Way w : DangerOptions.Way.values()) {
            if (w == DangerOptions.Way.STAY || w == DangerOptions.Way.SHORE) continue;
            List<Step> s = DangerOptions.stepsOf(new DangerOptions.Priced(w, true, 0, 6, 0, "t"));
            assertNotNull(s, w.name());
            for (Step x : s) runnable(x);
        }
        assertEquals(List.of(new Step.Burrow(), new Step.Wait(120)), DangerOptions.stepsOf(new DangerOptions.Priced(DangerOptions.Way.BURROW, true, 0, 6, 0, "t")));
        assertEquals(List.of(new Step.Flee(), new Step.Wait(6)), DangerOptions.stepsOf(new DangerOptions.Priced(DangerOptions.Way.FLEE, true, 0, 6, 0, "t")));
    }

    @Test
    void discoveryRowsAreTypedStairsAndChunks() {
        var f = new io.github.mojolowjo.entropybot.aa.brain.Discovery.Facts(-1, 1, false, 0, 64, 0, 40, 0.01, 0.5, true, null, null, 2);
        List<String> dropped = new ArrayList<>();
        var opts = io.github.mojolowjo.entropybot.aa.brain.Discovery.rank(f, "iron", 3, dropped);
        assertFalse(opts.isEmpty(), dropped.toString());
        for (var o : opts) {
            assertFalse(o.command().contains(" then "), o.command());
            for (Step s : o.steps()) runnable(s);
        }
        var strip = opts.stream().filter(o -> o.kind() == io.github.mojolowjo.entropybot.aa.brain.Discovery.Kind.STRIP).findFirst().orElseThrow();
        assertInstanceOf(Step.StripLeg.class, strip.steps().get(0), "the staircase down is one typed leg");
        var leg = (Step.StripLeg) strip.steps().get(0);
        assertEquals(24, leg.levels());
        assertInstanceOf(Step.MarkMine.class, leg.parts().get(leg.parts().size() - 1), "the mine is marked at the bottom");
        assertTrue(opts.stream().anyMatch(o -> o.steps().get(0) instanceof Step.ClearChunk), "clear chunk for the owner's goal");
    }

    @Test
    void desiresEmitTypedSteps() {
        var in = new io.github.mojolowjo.entropybot.aa.core.Inputs();
        in.bag = Map.of("raw_iron", 5);
        in.furnaceInBag = true;
        assertEquals(List.of(new Step.Smelt("iron_ingot", 5, true)), io.github.mojolowjo.entropybot.aa.brain.NightWork.steps(in, io.github.mojolowjo.entropybot.aa.brain.NightWork.Work.SMELT, 0, 64, 0, 1, 0));
        for (Step s : io.github.mojolowjo.entropybot.aa.brain.NightWork.stairs(0, 64, 0, 1, 0)) runnable(s);
        var d = new io.github.mojolowjo.entropybot.aa.brain.Kit.Death("minecraft:overworld", 5, 60, 5, Map.of(), 0);
        assertEquals(List.of(new Step.Walk(5, 60, 5, 0), new Step.Wait(3)), io.github.mojolowjo.entropybot.aa.brain.Kit.recoverSteps(d));
    }

    @Test
    void theChainRowIsTheChainsSteps() {
        Step.Obtain o = new Step.Obtain("torch", 16);
        var chain = new NeedChain.Chain(List.of(new Step.Craft("torch", 16)), 0.3, "torch <- craft: 4 coal (in the bag) + 4 stick (in the bag): 0.3 s", false, null);
        Options.Choice c = Ways.goalRows(List.of(o), new Ways.Plan(o, chain, null), new Options.OptionMemory(), 0, 30);
        assertEquals(List.of(new Step.Craft("torch", 16)), c.best().steps(), "the chain's typed steps");
        Options.Choice plain = Ways.goalRows(List.of(new Step.Deposit()), null, new Options.OptionMemory(), 0, 30);
        assertEquals(List.of(new Step.Deposit()), plain.best().steps());
    }

    @Test
    void theOwnersLinesAreTypedOnce() {
        var L = (java.util.function.Function<String, Step>) io.github.mojolowjo.entropybot.commands.PlanLines::one;
        assertEquals(new Step.Craft("iron_pickaxe", 1), L.apply("craft iron_pickaxe 1"));
        assertEquals(new Step.SmeltCollect(), L.apply("smelt collect all"));
        assertEquals(new Step.Mine("iron", 3, Step.Mine.How.STRIP), L.apply("mine strip iron 3"));
        assertEquals(new Step.Restore(true, 0), L.apply("restore keep pillar"));
        assertEquals(new Step.Deposit(), L.apply("deposit"));
        assertNull(L.apply("goal bed"));
    }

    /** Fake hands: every step is DONE at once; the chain for 4 torches is two crafts. */
    static final class Hands implements Steps.Hands {
        final List<Step> ran = new ArrayList<>();
        Step cur;

        @Override public String start(Step s) { ran.add(s); cur = s; return null; }

        @Override public Car.Status status(Step s) { return Car.Status.done("ok"); }

        @Override public void cancel(String why) {}

        @Override public Steps.Routine routine(Step.Obtain o) {
            return o.what().equals("torch") && o.n() == 4 ? new Steps.Routine(List.of(new Step.Craft("stick", 1), new Step.Craft("torch", 4)), null) : new Steps.Routine(null, "no way to " + o.what());
        }
    }

    @Test
    void anObtainStepRunsTheChainOnTheSameMachine() {
        Hands h = new Hands();
        Steps st = new Steps(h);
        st.start("k", List.of(new Step.Obtain("torch", 4), new Step.Deposit()), 0);
        for (int i = 1; i < 10 && st.running(); i++) st.tick(i);
        assertEquals(Steps.Phase.DONE, st.phase(), st.why());
        assertEquals(List.of(new Step.Craft("stick", 1), new Step.Craft("torch", 4), new Step.Deposit()), h.ran);
        Steps bad = new Steps(new Hands());
        bad.start("k", List.of(new Step.Obtain("bed", 1)), 0);
        assertEquals(Steps.Phase.FAILED, bad.phase());
        assertEquals("k", bad.failedKey());
        assertTrue(bad.why().contains("no chain for obtain 1 bed: no way to bed"), bad.why());
    }

    @Test
    void aCompoundStepRunsItsParts() {
        Hands h = new Hands();
        Steps st = new Steps(h);
        st.start("k", List.of(new Step.StripLeg(0, 64, 0, "north", 0, -1, 2, true)), 0);
        for (int i = 1; i < 20 && st.running(); i++) st.tick(i);
        assertEquals(Steps.Phase.DONE, st.phase(), st.why());
        assertEquals(5, h.ran.size(), "2 x (dig + walk) + the mark");
        assertEquals(new Step.MarkMine(null, "north"), h.ran.get(4));
    }
}
