package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.StandWait;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import io.github.mojolowjo.entropybot.commands.PricedAnswers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z76 send-back 3 (rule 22, owner 2026-10-26: "the bot moves only as a priced step of the current order. Waiting is
 * standing"; run AU 01:57:53-02:02:53: the deleted "explore 5 while paths compute" walked 5 min while its own prices were
 * searched again from every new cell). A scenario per waiting state - prices computing, a first answer awaited, a furnace
 * working - asserting ZERO feet movement; past the first-answer wait the walk is taken by Baritone's estimate ("ranked, not
 * priced", rule 18 as amended) and that walk is the first move. Hands here move the feet one block a tick while a moving
 * step (walk, explore, cut, collect, strip leg) runs; ticks are client ticks, the game clock 20 a second.
 */
class Z76StandTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** Hands with feet: a moving step moves them; the stand stands (StandWait on the game clock); a smelt cooks in place. */
    static final class FeetHands implements Steps.Hands {
        final int[] feet = {0, 64, 0};
        long clock;
        Step running;
        final StandWait stand = new StandWait();
        final List<String> started = new ArrayList<>();
        int smeltLeft = -1, carIdled;
        final List<String> said = new ArrayList<>();

        static boolean moving(Step s) {
            return s instanceof Step.Walk || s instanceof Step.Explore || s instanceof Step.ChopTree || s instanceof Step.Collect || s instanceof Step.ToStrip;
        }

        @Override public String start(Step s) {
            running = s;
            started.add(s.text());
            if (s instanceof Step.AwaitPaths a) return stand.start(a, clock, 3, () -> carIdled++, said::add);   // the hands' own stand logic (StandWait)
            if (s instanceof Step.Smelt) smeltLeft = 60 * 20;          // a minute of furnace time
            return null;
        }

        @Override public Car.Status status(Step s) {
            if (moving(s)) feet[0]++;
            if (s instanceof Step.AwaitPaths a) return stand.status(a, clock, 3, said::add);   // the hands' own stand logic
            if (s instanceof Step.Smelt && --smeltLeft <= 0) return Car.Status.done("ok: smelted");
            return Car.Status.running(s.text());
        }

        @Override public void cancel(String why) { stand.end(); }
        @Override public int[] feet() { return feet.clone(); }
    }

    static void tick(Steps run, FeetHands h, int n) {
        for (int i = 0; i < n && run.running(); i++) {
            h.clock++;
            run.tick(h.clock);
        }
    }

    /** Waiting state 1, prices computing: the only tree's walk is computing - the chain's one step is the stand, no move in it. */
    @Test
    void pricesComputing_theChainStandsWithNoMovingStep() {
        ScenariosTest.world("wooden");
        Spot tree = ScenariosTest.tree(10, 4);
        Chain c = NeedChain.resolve("oak_log", 3, ScenariosTest.w(Map.of(), false, false, Map.of("oak_log", List.of(tree)))
                .withPath(new ScenariosTest.FakePath().computing(tree)));
        assertTrue(c.ok() && c.explore() && !c.waiting(), c.why());
        assertEquals(List.of(new Step.AwaitPaths(NeedChain.firstAnswerWaitS())), c.steps());
        assertTrue(c.steps().stream().noneMatch(FeetHands::moving), "no explore while paths compute (deleted), no walk: " + c.steps());
        FeetHands h = new FeetHands();
        Steps run = new Steps(h);
        run.start("goal.logs", c.steps(), 0, (failed, fact, why) -> new Steps.Routine(null, "not known yet"));
        tick(run, h, 20 * 29);                             // 29 s of standing
        assertArrayEquals(new int[]{0, 64, 0}, h.feet, "zero movement while the prices compute");
        assertEquals(List.of("stand while paths compute (up to 30 s)"), h.started);
        assertEquals(1, h.carIdled, "the stand's start idles the car (Baritone cancelled), nothing else");
        assertTrue(h.said.get(0).contains("standing, Baritone idle"), h.said.toString());
    }

    /**
     * Waiting state 2, a first answer awaited: the stand stands its whole 30 s with the answer still out (zero movement);
     * then the order is priced again where it stands - the walk now taken by the estimate - and THAT walk is the first move.
     */
    @Test
    void firstAnswerAwaited_zeroMovementUntilTheEstimatedWalk() {
        FeetHands h = new FeetHands();
        Steps run = new Steps(h);
        List<FactChange.Fact> facts = new ArrayList<>();
        Step walk = new Step.Walk(30, 64, 0, 0);
        run.start("goal.logs", List.of(new Step.AwaitPaths(30)), 0, (failed, fact, why) -> {
            facts.add(fact);
            return new Steps.Routine(List.of(walk), null);
        });
        tick(run, h, 20 * 30 - 1);
        assertArrayEquals(new int[]{0, 64, 0}, h.feet, "zero movement while the first answer is awaited");
        assertTrue(facts.isEmpty());
        tick(run, h, 1);                                    // 30 s: over, priced again (a price fact), the walk starts
        assertEquals(1, facts.size());
        assertTrue(facts.get(0).price(), facts.get(0).toString());
        assertEquals(List.of("stand while paths compute (up to 30 s)", walk.text()), h.started, "the first move is the order's walk");
    }

    /** Waiting state 3, a furnace working: the smelt cooks a minute in place - zero movement, no other step started meanwhile. */
    @Test
    void furnaceWorking_zeroMovementWhileItCooks() {
        FeetHands h = new FeetHands();
        Steps run = new Steps(h);
        run.start("goal.iron", List.of(new Step.Smelt("iron_ingot", 3, true), new Step.Craft("iron_pickaxe", 1)), 0, (failed, fact, why) -> new Steps.Routine(null, "-"));
        tick(run, h, 20 * 60 - 1);
        assertArrayEquals(new int[]{0, 64, 0}, h.feet, "zero movement while the furnace works");
        assertEquals(List.of(new Step.Smelt("iron_ingot", 3, true).text()), h.started);
    }

    /**
     * Past the first-answer wait (30 s) a walk still computing is answered by Baritone's estimate: ESTIMATED with the
     * estimate's seconds (rule 18 as amended); before it, or with no estimate (NaN, rule 15), the answer stays "computing".
     */
    @Test
    void theEstimateAfterTheWait() {
        PathPrice.Answer computing = PathPrice.Answer.computing("queued: pathCost.maxPending 4 searches in flight");
        assertSame(computing, PricedAnswers.afterWait(computing, 20 * 30 - 1, 30, 12));
        PathPrice.Answer est = PricedAnswers.afterWait(computing, 20 * 30, 30, 12);
        assertEquals(PathPrice.Kind.ESTIMATED, est.kind());
        assertEquals(12, est.walk().seconds(), 1e-9);
        // review (rule 15): past the wait with no usable estimate the way drops - NONE, no NOT_KNOWN prefix, never another stand
        PathPrice.Answer none = PricedAnswers.afterWait(computing, 20 * 60, 30, Double.NaN);
        assertEquals(PathPrice.Kind.NONE, none.kind());
        assertTrue(none.reason().startsWith(PathPrice.NO_ESTIMATE), none.reason());
        assertSame(computing, PricedAnswers.afterWait(computing, 20 * 30 - 1, 30, Double.NaN), "before the wait: still computing");
        PathPrice.Answer priced = PathPrice.Answer.priced(new PathPrice.Walk(9));
        assertSame(priced, PricedAnswers.afterWait(priced, 20 * 60, 30, 12), "a price stays a price");
    }

    /** The chain takes an ESTIMATED walk: the cut is picked and walked, aa why says "ranked, not priced". */
    @Test
    void anEstimatedWalkIsPickedAndSaidRanked() {
        ScenariosTest.world("wooden");
        Spot tree = ScenariosTest.tree(10, 4);
        PathPrice est = (x, y, z, d) -> PathPrice.Answer.estimated(new PathPrice.Walk(4), "not known after 30 s from here: Baritone's estimate, ranked, not priced");
        Chain c = NeedChain.resolve("oak_log", 3, ScenariosTest.w(Map.of(), false, false, Map.of("oak_log", List.of(tree))).withPath(est));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals("cut 3 at 10 64 0", c.steps().get(0).text());
        assertTrue(c.why().contains("walk ~4.0 s (" + NeedChain.RANKED + ", not priced: Baritone's estimate after the first-answer wait"), c.why());
    }

    /** Review (rule 15): no estimate after the wait - the next pricing has no stand: the way drops, the explore leaf is the answer. */
    @Test
    void noEstimateAfterTheWaitNeverStandsAgain() {
        ScenariosTest.world("wooden");
        Spot tree = ScenariosTest.tree(10, 4);
        PathPrice[] answer = {(x, y, z, d) -> PathPrice.Answer.computing(null)};
        PathPrice pp = (x, y, z, d) -> answer[0].price(x, y, z, d);
        NeedChain.World w = ScenariosTest.w(Map.of(), false, false, Map.of("oak_log", List.of(tree))).withPath(pp);
        Chain first = NeedChain.resolve("oak_log", 3, w);
        assertEquals(List.of(new Step.AwaitPaths(NeedChain.firstAnswerWaitS())), first.steps(), first.why());
        PathPrice.Answer none = PricedAnswers.afterWait(PathPrice.Answer.computing(null), 20 * 30, 30, Double.NaN);
        answer[0] = (x, y, z, d) -> none;
        Chain second = NeedChain.resolve("oak_log", 3, w);
        assertTrue(second.steps().stream().noneMatch(s -> s instanceof Step.AwaitPaths), "no second stand: " + second.steps());
        assertTrue(second.dropped().stream().anyMatch(d -> d.contains(PathPrice.NO_ESTIMATE)), second.dropped().toString());
    }

    /**
     * Review (rule 18: a heuristic rank is never a price): tree A 30 m away with an estimate of 0 s, tree B 20 m away priced
     * complete at 20 x 1.3 / 4.317 = 6.02 s. A is charged at least its straight walk, 30 / 4.317 = 6.95 s, so B is cut.
     */
    @Test
    void anEstimateIsChargedAtLeastTheStraightWalk() {
        ScenariosTest.world("wooden");
        Spot a = ScenariosTest.tree(30, 4), b = ScenariosTest.tree(20, 4);
        PathPrice pp = (x, y, z, d) -> x == 30 ? PathPrice.Answer.estimated(new PathPrice.Walk(0), "estimate") : PathPrice.Answer.priced(new PathPrice.Walk(FakePaths.walkS(d)));
        Chain c = NeedChain.resolve("oak_log", 3, ScenariosTest.w(Map.of(), false, false, Map.of("oak_log", List.of(a, b))).withPath(pp));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals("cut 3 at 20 64 0", c.steps().get(0).text(), c.why());
        assertTrue(c.dropped().stream().anyMatch(d -> d.startsWith("tree 30 m: ") && d.contains("dearer")), c.dropped().toString());
        assertEquals(30 / io.github.mojolowjo.entropybot.aa.brain.Unit.walkBps(), NeedChain.estimateFloor(NeedChain.walked(PathPrice.Answer.estimated(new PathPrice.Walk(0), "e")), 30).seconds(), 1e-9);
    }
}