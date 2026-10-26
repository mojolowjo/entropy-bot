package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 walk (run BT on z77f, 2026-10-26, docs/runs/runBT-z77f 13:14:32): "order iron_leggings stopped: stopped at step 2
 * (goto 20 21 -10): couldn't get there (22 blocks away, stopped at 28 21 11) (walk: Baritone stopped short) - next: goto a
 * spot next to it, or come get me" - a walk that ends short was a plain stop (no count, no fact). Now: the typed fact "walk to
 * x y z ended short at feet: why" re-plans; the same target short again at the same feet holds the target for the order and
 * the next way runs. Plus the manager's log-only addition: the "trip bN" line only when the trip changes.
 */
class Z77WalkShortTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
        BatchWay.clear();
    }

    static final String WHY = "error: couldn't get there (22 blocks away, stopped at 28 21 11) (walk: Baritone stopped short)";
    static final int[] TARGET = {20, 21, -10}, FEET = {28, 21, 11};

    @Test
    void theShortEndIsATypedFactKeyedByTheTargetAndTheFeet() {
        FactChange.Fact f = FactChange.walkShortFact(TARGET, FEET, WHY);
        assertEquals("walk short to 20 21 -10 at 28 21 11", f.key());
        assertEquals("walk to 20 21 -10 ended short at 28 21 11: couldn't get there (22 blocks away, stopped at 28 21 11) (walk: Baritone stopped short)", f.text());
        assertTrue(FactChange.isWalkShort(f));
        assertFalse(f.key().startsWith("source "), "not a hold: the first short end re-plans from the feet of now");
        assertFalse(f.text().contains("come get me"), "no owner action is the bot's plan");
        FactChange.Fact h = FactChange.walkHeld(TARGET, f);
        assertEquals("source walk target 20 21 -10", h.key());
        assertTrue(h.text().startsWith(f.text()), h.text());
        assertNull(FactChange.ownSourceKey(h), "not a z65 walk-led source: held by its own key (Goals)");
    }

    /** Run BT: goto 20 21 -10, then the cave mine (no source key after the walk): a re-plan, never a stop; twice at the same feet: held. */
    @Test
    void aShortWalkReplans_theSameTargetShortTwiceAtTheSameFeetIsHeld() {
        Step walk = new Step.Walk(20, 21, -10, 0), mine = new Step.Mine("iron", 4, Step.Mine.How.CAVE, 10);
        assertNull(Steps.walkLeads(List.of(walk, mine), 0, WHY), "the cave mine names no source: z65's walk-led fact does not apply");
        Z61ReservationsTest.Hands h = new Z61ReservationsTest.Hands();
        List<FactChange.Fact> asked = new ArrayList<>();
        Steps run = new Steps(h);
        run.start("owner goal", List.of(walk, mine), 0, (failed, fact, w) -> { asked.add(fact); return new Steps.Routine(List.of(walk, mine), null); });
        h.fact = FactChange.walkShortFact(TARGET, FEET, WHY);
        h.status = Car.Status.failed(WHY);
        run.tick(1);
        assertEquals(1, asked.size(), "re-planned, never ended: " + run.why());
        assertEquals("walk short to 20 21 -10 at 28 21 11", asked.get(0).key());
        assertTrue(run.running(), run.why());

        // the re-plan walked there again from 28 21 11 and ended short at the same feet: the target is held, the next way runs
        h.fact = FactChange.walkShortFact(TARGET, FEET, WHY);
        run.tick(2);
        assertEquals(2, asked.size(), "re-planned again, no park: " + run.why());
        assertEquals("source walk target 20 21 -10", asked.get(1).key());
        assertTrue(asked.get(1).text().contains("twice from there"), asked.get(1).text());
        assertTrue(run.running(), run.why());

        // a walk to the same target that got farther (ended short at other feet) is a new fact: re-planned, not held
        h.fact = FactChange.walkShortFact(TARGET, new int[]{24, 21, 0}, WHY);
        run.tick(3);
        assertEquals(3, asked.size(), run.why());
        assertEquals("walk short to 20 21 -10 at 24 21 0", asked.get(2).key());
    }

    /** A walk that leads to a named source keeps z65's fact (the source is out), over its own short end. */
    @Test
    void aWalkLedSourceStaysThatSourcesFact() {
        Step walk = new Step.Walk(-261, 75, 21, 2), take = new Step.TakeStation("crafting_table", -261, 75, 21);
        Z61ReservationsTest.Hands h = new Z61ReservationsTest.Hands();
        List<FactChange.Fact> asked = new ArrayList<>();
        Steps run = new Steps(h);
        run.start("owner goal", List.of(walk, take), 0, (failed, fact, w) -> { asked.add(fact); return new Steps.Routine(List.of(new Step.Explore(5)), null); });
        h.fact = FactChange.walkShortFact(new int[]{-261, 75, 21}, new int[]{-261, 16, 22}, WHY);
        h.status = Car.Status.failed(WHY);
        run.tick(1);
        assertEquals(1, asked.size(), run.why());
        assertEquals("take at -261 75 21", FactChange.ownSourceKey(asked.get(0)));
    }

    /** The held target: every walk the chain prices there answers unreachable with the hold's reason; other cells as before. */
    @Test
    void aHeldWalkTargetIsPricedUnreachable() {
        PathPrice base = (x, y, z, d) -> PathPrice.Answer.priced(new PathPrice.Walk(12.0));
        Map<String, String> held = Map.of(NeedChain.walkTargetKey(20, 21, -10), "ended short twice at 28 21 11");
        PathPrice p = NeedChain.heldWalks(base, held);
        PathPrice.Answer a = p.price(20, 21, -10, 22);
        assertEquals(PathPrice.Kind.UNREACHABLE, a.kind());
        assertEquals("the walk to 20 21 -10 is held for this order: ended short twice at 28 21 11", a.reason());
        assertEquals(PathPrice.Kind.UNREACHABLE, p.priceInto(20, 21, -10, 22).kind());
        assertNull(p.toBlock(20, 21, -10));
        assertTrue(Double.isNaN(p.estimate(20, 21, -10)));
        assertEquals(PathPrice.Kind.PRICED, p.price(20, 21, -9, 22).kind(), "another cell is priced as before");
        assertSame(base, NeedChain.heldWalks(base, Map.of("collect coal", "x")), "no walk target held: the provider itself");

        World w = FakePaths.paths(new World(Map.of(), true, k -> null)).withoutSources(held);
        assertEquals(PathPrice.Kind.UNREACHABLE, w.path().price(20, 21, -10, 22).kind(), "the order's world prices the held walk out");
    }

    /** Run V's far table, its walk target held: the chain drops the walk there and places a new table here instead. */
    @Test
    void aHeldTargetDropsTheWayThroughIt_theNextWayRuns() {
        ScenariosTest.world("wooden", "stone");
        World w = Z65RunVTest.farTableWorld();
        assertTrue(Z65RunVTest.walksToTable(NeedChain.resolve("stone_pickaxe", 1, w)), "first: the walk to the known table");
        Chain c = NeedChain.resolve("stone_pickaxe", 1, w.withoutSources(Map.of(NeedChain.walkTargetKey(-261, 75, 21), "ended short twice at -261 16 22")));
        assertTrue(c.ok(), c.why());
        assertFalse(Z65RunVTest.walksToTable(c), "the next way: " + c.steps());
    }

    /** The per-position hold: back once the bot stands failures.moved closer to the target than where it failed. */
    @Test
    void aHeldTargetIsBackOnceTheBotIsFailuresMovedCloser() {
        double m = io.github.mojolowjo.entropybot.aa.brain.Failures.movedV();
        String k = NeedChain.walkTargetKey(0, 70, 0);
        int[] from = {0, 10, 0};
        assertFalse(Goals.stationBack(k, from, new int[]{0, (int) (10 + m - 1), 0}), "a little closer: still held");
        assertTrue(Goals.stationBack(k, from, new int[]{0, (int) (10 + m), 0}), "failures.moved closer: back");
        assertFalse(Goals.stationBack(k, null, new int[]{0, 69, 0}), "no position kept: held for the order");
    }

    // ---- the manager's log-only addition: the trip line on a change only ----

    /**
     * Run BT printed 269 trip lines in 20 min (several a second at 12:57), each re-price its own line: the same trip
     * re-priced N times is said once; a changed carry list is said again.
     */
    @Test
    void theTripLineIsSaidOnlyWhenItsLegsOrCarriesChange() {
        Z77PlaybookTest.data();
        World w = Z77PlaybookTest.world(Z77PlaybookTest.kitBag(9), Map.of());
        Playbook pb = Playbook.derive(Z77PlaybookTest.KIT, Playbook.facts(w, null));
        NeedChain.Legs legs = NeedChain.resolvePlaybookLegs(pb, w);
        Chain pc = legs.chain();
        assertTrue(pc.ok(), pc.why());
        BatchWay.Shaped first = BatchWay.shape("b1", 6, pb, pc, legs.starts());
        assertTrue(first.change().contains(", carries "), first.change());
        assertFalse(first.change().contains("priced"), "no price in what is compared: " + first.change());
        int said = 0;
        if (sayAgain("b1", first.change())) said++;
        for (int i = 1; i <= 5; i++) {
            Chain again = new Chain(pc.steps(), pc.cost() + i * 3.5, pc.why(), pc.explore(), pc.missing(), pc.dropped(), pc.unreachable(), pc.uses(), pc.spares(), pc.stepSeconds());
            BatchWay.Shaped s = BatchWay.shape("b1", 6, pb, again, legs.starts());
            assertNotEquals(first.line(), s.line(), "the price moved");
            if (sayAgain("b1", s.change())) said++;
        }
        assertEquals(1, said, "the same trip re-priced 5 times: said once");
        // the stand while a leg's walk computes (its reason changes each pass) is the same legs: not said again
        Chain notYet = new Chain(List.of(), 0, "x", false, PathPrice.NOT_KNOWN + " yet (computing)");
        BatchWay.Shaped stand = BatchWay.shape("b1", 6, pb, notYet, legs.starts());
        assertFalse(stand.change().contains("computing"), "the stand's reason is not compared: " + stand.change());
        assertTrue(first.change().startsWith(stand.change().substring(0, stand.change().indexOf(" leg"))), "the same head and leg count: " + stand.change());

        // a changed carry list: said again
        List<Map<String, Integer>> uses = new ArrayList<>(pc.uses());
        Map<String, Integer> last = new HashMap<>(uses.get(uses.size() - 1));
        last.merge("minecraft:torch", 7, Integer::sum);
        uses.set(uses.size() - 1, last);
        Chain moreCarried = new Chain(pc.steps(), pc.cost(), pc.why(), pc.explore(), pc.missing(), pc.dropped(), pc.unreachable(), uses, pc.spares(), pc.stepSeconds());
        BatchWay.Shaped changed = BatchWay.shape("b1", 6, pb, moreCarried, legs.starts());
        assertNotEquals(first.change(), changed.change(), changed.change());
        assertTrue(sayAgain("b1", changed.change()), "a changed carry list is said");
        assertFalse(sayAgain("b1", changed.change()), "once");
    }

    private static boolean sayAgain(String tk, String change) { return BatchWay.sayAgain(tk, change); }
}
