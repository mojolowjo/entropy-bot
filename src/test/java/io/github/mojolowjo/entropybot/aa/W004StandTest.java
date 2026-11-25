package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.Only;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.commands.PricedAnswers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-deposit-park-wake (tester pilot on c606aca8, w004 09:44-09:47; the clock's part is commands.W004NoProgressTest): the
 * bot 20 blocks under ground after the pickaxe broke, the order's tree walks answered no progress from its cell. As the
 * fork's answer (COMPUTING) the chain stood for it again and again; as the answer from here (NONE, the clock's) the next
 * price takes another way at once - the walk up - on the tick and through the planner worker's path (SyncPlanner).
 */
class W004StandTest {
    @AfterEach
    void clear() {
        Only.set("off");
        FactChange.clear();
        TestData.clear();
        Tuning.reset();
    }

    static boolean tree(int x) { return x == 10 || x == 14; }      // OrderLineScenariosTest.stoneAndTree's two trees

    static OrderLineSim under(PathPrice.Answer treeWalk) {
        OrderLineSim s = OrderLineScenariosTest.stoneAndTree();
        s.under(20);
        s.path = (x, y, z, d) -> tree(x) ? treeWalk : PathPrice.Answer.priced(new PathPrice.Walk(10));
        return s;
    }

    static boolean stands(NeedChain.Chain c) {
        return !c.ok() && c.missing() != null && c.missing().startsWith(PathPrice.NOT_KNOWN)
                || c.steps().stream().anyMatch(st -> st instanceof Step.AwaitPaths);
    }

    @Test
    void w004_theForksNoProgress_wasAStand() {
        OrderLineSim s = under(PricedAnswers.noPath("no progress", false, true, "chunks not loaded"));
        Goals.Goal g = s.order("oak_log", 16, null, false);
        var c = Goals.chain(s.bb, g);
        assertTrue(stands(c), "the fault's shape: the chain waits on the tree walk: " + c.why());
    }

    @Test
    void w004_theAnswerFromHere_takesAnotherWay_onTheTick() {
        OrderLineSim s = under(PricedAnswers.noProgressHere(new int[]{0, 64, 0}));
        Goals.Goal g = s.order("oak_log", 16, null, false);
        var c = Goals.chain(s.bb, g);
        assertFalse(stands(c), "no stand on the same question: " + c.why());
        assertTrue(c.ok(), c.why());
        assertTrue(c.steps().get(0) instanceof Step.ToSurface || c.steps().get(0) instanceof Step.Climb, "another way: " + c.why());
    }

    @Test
    void w004_theAnswerFromHere_takesAnotherWay_throughTheWorker() {
        OrderLineSim s = under(PricedAnswers.noProgressHere(new int[]{0, 64, 0}));
        s.plannerThinks();
        s.plannerAnswers.run();
        Goals.Goal g = s.order("oak_log", 16, null, false);
        var c = Goals.chain(s.bb, g);
        assertFalse(stands(c), "no stand on the same question: " + c.why());
        assertTrue(c.ok(), c.why());
        assertTrue(s.sync.drops.isEmpty(), "no planner drop: " + s.sync.drops);
    }
}
