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
 * text to the old job interpreter (an option's command is only the steps' label, never parsed back). The planner's
 * routine is typed once at the planner's side; a planner goal runs as one {@link Step.Plan} the machine expands.
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

    /** A step the game side runs: its command is a verb the bot knows (a Plan / compound is expanded by Steps first). */
    static void runnable(Step s) {
        if (s instanceof Step.Plan || s instanceof Step.Compound || s instanceof Step.Fish || s instanceof Step.KelpCut) return;     // fishing and kelp cuts have hands of their own, no verb
        String cmd = io.github.mojolowjo.entropybot.commands.StepCommands.command(s);
        assertNotNull(io.github.mojolowjo.entropybot.commands.VerbTable.of(cmd.split("\\s+")[0]), "no verb for " + cmd);
    }

    static void allTyped(Options.Choice c) {
        assertFalse(c.ranked().isEmpty(), c.why());
        for (Options.Option o : c.ranked()) typed(o);
    }

    @Test
    void woodEmitsAWalkAndBaritonesTreeMining() {
        Options.Choice c = WoodOptions.choose(new WoodOptions.WoodFacts(6, List.of(new WoodOptions.Tree(40, 70, -12, 30, null))), new Options.OptionMemory(), 0);
        typed(c.best());
        assertInstanceOf(Step.Walk.class, c.best().steps().get(0));
        Step.ChopTree t = (Step.ChopTree) c.best().steps().get(1);
        assertArrayEquals(new int[]{40, 70, -12}, t.at(), "the tree's cell comes from Sightings, no mod-side column scan");
        assertEquals(6, t.logs());
    }

    @Test
    void foodHuntRowsAreTypedHuntSteps() {
        Options.Choice c = FoodOptionsTest.choose(FoodOptionsTest.withHerds(Map.of("cow", 4), List.of()));
        allTyped(c);
        Options.Option hunt = c.ranked().stream().filter(o -> o.way().equals("hunt")).findFirst().orElseThrow();
        Step.HuntAt h = (Step.HuntAt) hunt.steps().get(0);
        assertEquals("minecraft:cow", h.type());
    }

    @Test
    void ironRowsAreTypedOreAndSmeltSteps() {
        IronOptions.Facts f = IronOptionsTest.facts(List.of(IronOptionsTest.open(10, 60, 0, 10, true), IronOptionsTest.buried(5, 50, 0, 5, 12, false)), 60);
        Options.Choice c = new IronOptions.Set().choose(f, new Options.OptionMemory(), 0, TS.s(IronOptionsTest.PLANNED), 10, null, null);
        allTyped(c);
        Options.Option walk = c.ranked().stream().filter(o -> o.id().startsWith("walk_known")).findFirst().orElseThrow();
        assertEquals(new Step.MineAt("iron", 10, 60, 0, false), walk.steps().get(0));
    }

    @Test
    void coalRowsAreTyped() {
        var ore = IronOptionsTest.open(3, 60, 3, 8, true);
        var disc = List.of(CoalOptionsTest.strip(300));
        Options.Choice c = CoalOptions.choose(new CoalOptions.CoalFacts(8, 1, List.of(ore), disc, List.of(), 2, 0, false, -1, true, 0), new Options.OptionMemory(), 0);
        allTyped(c);
        Options.Option mine = c.ranked().stream().filter(o -> o.id().equals("seen coal")).findFirst().orElseThrow();
        assertEquals(new Step.MineAt("coal", 3, 60, 3, false), mine.steps().get(0), "the priced ore is the step's target");
        Options.Option charcoal = c.ranked().stream().filter(o -> o.id().equals("charcoal")).findFirst().orElseThrow();
        assertEquals("collect cobblestone 8 > craft furnace 1 > cut 8 > smelt charcoal 8", charcoal.command());
        assertEquals("smelt charcoal 2 > craft torch 16", Step.label(CoalOptions.torchSteps(List.of(new Step.Smelt("charcoal", 2, false)), 16)));
    }

    @Test
    void bedBaseBagRowsAreTyped() {
        allTyped(BedOptions.choose(new BedOptions.BedFacts(0, 2, new int[]{9, 9, 30, 50}, ME, true, true, true, 4), new Options.OptionMemory(), 0));
        Options.Choice base = BaseOptions.choose(new BaseOptions.BaseFacts("home", null, 20, true, 1, true, 5, true), new Options.OptionMemory(), 0);
        allTyped(base);
        Options.Option burrow = base.ranked().stream().filter(o -> o.id().equals("burrow")).findFirst().orElseThrow();
        assertEquals(List.of(new Step.Burrow(), new Step.Wait(120)), burrow.steps(), "no 'burrow then wait 120' text: two typed steps, the wait from Tuning");
        Options.Option house = base.ranked().stream().filter(o -> o.id().startsWith("house")).findFirst().orElseThrow();
        assertEquals(List.of(new Step.Climb(), new Step.House("home")), house.steps(), "under ground the climb comes first");
        allTyped(BagOptions.choose(new BagOptions.BagFacts(0, 1, true, 5, ME, false, 8, 0, ME, ME, 1, List.of(new Step.DropJunk("dirt", 3))), new Options.OptionMemory(), 0));
        Options.Choice chest = BagOptions.choose(new BagOptions.BagFacts(0, 1, false, -1, null, false, 8, 0, new int[]{1, 64, 0}, ME, 0, List.of()), new Options.OptionMemory(), 0);
        allTyped(chest);
        assertEquals(List.of(new Step.Craft("chest", 1), new Step.PlaceChest(1, 64, 0), new Step.MarkBase(0, 64, 0), new Step.Scan(true), new Step.Deposit()), chest.best().steps());
    }

    @Test
    void wayUpAndToolsRowsAreTyped() {
        var f = new RungOptions.SurfaceFacts(10, ME, true, 20, true, true, new int[]{20, 70, 5}, 25, false);
        allTyped(WayUp.choose(f, new Options.OptionMemory(), 0));
        Options.Choice tools = new RungOptions.Tools().choose(new RungOptions.ToolsFacts(1, true), new Options.OptionMemory(), 0, TS.s("cut 3 logs then craft stone_pickaxe 1"), 12, null, null);
        allTyped(tools);
        assertTrue(tools.ranked().stream().anyMatch(o -> o.id().equals("planner")));
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
        assertEquals(List.of(new Step.Plan("bed")), BedOptions.steps(3, null));
    }

    @Test
    void thePlannerRowIsOnePlanStep() {
        var p = new Ways.Plan("torch 16", io.github.mojolowjo.entropybot.aa.core.JobPort.PlanState.READY, List.of(new Step.Craft("torch", 16)), 4, null, 0, null);
        Options.Choice c = Ways.goalRows(TS.plan("torch 16"), p, new Ways.State(), new Options.OptionMemory(), 0, 30);
        assertEquals(List.of(new Step.Plan("torch 16")), c.best().steps(), "the routine is asked for again when the step runs");
        Options.Choice plain = Ways.goalRows(List.of(new Step.Deposit()), null, new Ways.State(), new Options.OptionMemory(), 0, 30);
        assertEquals(List.of(new Step.Deposit()), plain.best().steps());
    }

    @Test
    void thePlannersWordsAreTypedAtItsSideOnce() {
        var L = (java.util.function.Function<String, Step>) io.github.mojolowjo.entropybot.commands.PlanLines::one;
        assertEquals(new Step.Craft("iron_pickaxe", 1), L.apply("craft iron_pickaxe 1"));
        assertEquals(new Step.SmeltCollect(), L.apply("smelt collect all"));
        assertEquals(new Step.Mine("iron", 3, Step.Mine.How.STRIP), L.apply("mine strip iron 3"));
        assertEquals(new Step.Restore(true, 0), L.apply("restore keep pillar"));
        assertEquals(new Step.Deposit(), L.apply("deposit"));
        assertNull(L.apply("goal bed"));
        // a routine line with no typed step: the planner's answer is NONE with that line named (never handed on as text)
        var port = new io.github.mojolowjo.entropybot.aa.core.FakePort();
        port.resolveTo = "frobnicate 2 then mine iron 3";
        var r = port.resolveState("iron tools");
        assertEquals(io.github.mojolowjo.entropybot.aa.core.JobPort.PlanState.NONE, r.state());
    }

    /** Fake hands: every step is DONE at once; the planner's routine for "torch 4" is a craft. */
    static final class Hands implements Steps.Hands {
        final List<Step> ran = new ArrayList<>();
        Step cur;

        @Override public String start(Step s) { ran.add(s); cur = s; return null; }

        @Override public Car.Status status(Step s) { return Car.Status.done("ok"); }

        @Override public void cancel(String why) {}

        @Override public Steps.Routine routine(Step.Plan p) {
            return p.goal().equals("torch 4") ? new Steps.Routine(List.of(new Step.Craft("stick", 1), new Step.Craft("torch", 4)), null) : new Steps.Routine(null, "no plan for " + p.goal());
        }
    }

    @Test
    void aPlanStepRunsThePlannersRoutineOnTheSameMachine() {
        Hands h = new Hands();
        Steps st = new Steps(h);
        st.start("k", List.of(new Step.Plan("torch 4"), new Step.Deposit()), 0);
        for (int i = 1; i < 10 && st.running(); i++) st.tick(i);
        assertEquals(Steps.Phase.DONE, st.phase(), st.why());
        assertEquals(List.of(new Step.Craft("stick", 1), new Step.Craft("torch", 4), new Step.Deposit()), h.ran);
        Steps bad = new Steps(new Hands());
        bad.start("k", List.of(new Step.Plan("bed")), 0);
        assertEquals(Steps.Phase.FAILED, bad.phase());
        assertEquals("k", bad.failedKey());
        assertTrue(bad.why().contains("no routine for bed: no plan for bed"), bad.why());
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
