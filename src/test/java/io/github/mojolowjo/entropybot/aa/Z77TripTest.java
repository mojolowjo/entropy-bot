package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 trip (manager's ruling 2026-10-26 ~21:35; run CU 21:02-21:06, `job smelting 8 iron`): "probe aatick: ... playbook 2 =
 * 250-650 ms" on most AA ticks and 16 `iron_helmet: trip b1` lines whose carries swapped, 0 seen asks. The brain's per-tick
 * waiting test (Goals.blocked -> ownChain) asks the trip for every order; the met iron_helmet carried the run on outside
 * BatchWay.open, so its ask keyed the batch's holds with its own and the next order's without them - two keys, the trip
 * derived in turn (CU: "playbook 2" from each "met inside the batch's playbook run" to the next fresh pick, "playbook 1"
 * otherwise). Fakes: Z77PlaybookTest's (the fake paths, bands, loot, world-gen; feet 0 64 0 on land), the paths always
 * pending (the waiting test asks), a landing counter. Pure (JUnit). Loader notes: none.
 */
class Z77TripTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
        BatchWay.clear();
    }

    /** Z77PlaybookTest's paths, always computing something (the waiting test runs), each landing counted by hand. */
    static final class Landing implements PathPrice {
        long landed;
        @Override public Answer price(int x, int y, int z, double dist) { return Z77PlaybookTest.PATHS.price(x, y, z, dist); }
        @Override public boolean pending() { return true; }
        @Override public long landed() { return landed; }
    }

    /** CU's bag at 21:02: the kit bag, the torches, the pickaxe and the helmet made, 19 raw iron for the rest of the armour. */
    static Map<String, Integer> cuBag(int rawIron, int ingots) {
        Map<String, Integer> b = new HashMap<>(Z77PlaybookTest.kitBag(9));
        b.put("minecraft:torch", 4);
        b.put("minecraft:iron_pickaxe", 1);
        b.put("minecraft:iron_helmet", 1);
        if (rawIron > 0) b.put("minecraft:raw_iron", rawIron);
        if (ingots > 0) b.put("minecraft:iron_ingot", ingots);
        return b;
    }

    /** The sensed bag as Goals counts an order's item (the item's path, as Z77PlaybookTest's boards: "iron_helmet"). */
    static Map<String, Integer> sensed(Map<String, Integer> b) {
        Map<String, Integer> out = new HashMap<>();
        for (var e : b.entrySet()) out.put(e.getKey().substring(e.getKey().indexOf(':') + 1), e.getValue());
        return out;
    }

    record Cu(Blackboard bb, List<Goals.Goal> gs, World[] world, Landing paths) {
        Goals.Goal helmet() { return gs.get(2); }

        void bag(Map<String, Integer> b) {
            bb.in.bag = sensed(b);
            world[0] = world[0].withBag(b);
        }

        /** One AA tick of the brain's waiting tests: the met runner (its score kept) and the open orders, as Brain.score asks. */
        void tick() {
            for (Goals.Goal g : gs) if (g == helmet() || Goals.have(bb.in.bag, g) < g.n) Goals.blocked(bb, g);
        }
    }

    /** The KIT batch with the torches, the pickaxe and the helmet had; the helmet's order carries the run on and holds a source of its own. */
    static Cu cu() {
        Z77PlaybookTest.data();
        Landing paths = new Landing();
        World[] w = {Z77PlaybookTest.world(cuBag(19, 0), Map.of()).withPath(paths)};
        List<Goals.Goal> gs = new ArrayList<>();
        Clock.Fake clock = new Clock.Fake(10_000_000);
        Blackboard bb = Z77PlaybookTest.batchBoard(new FakePort(), clock, () -> w[0], gs);
        bb.in.bag = sensed(cuBag(19, 0));
        bb.in.furnaceKnown = true;
        bb.in.tableKnown = true;
        Goals.Goal helmet = gs.get(2);
        helmet.playbookRun = true;
        // CU 21:05:53: the helmet's order had dropped sources the other orders had not (collect sand at 45 58 -5, ...)
        helmet.sources.failedFor(helmet.id(), "collect sand at 45 58 -5", "unreachable (test)", clock.now(), null, Long.MAX_VALUE / 4);
        return new Cu(bb, gs, w, paths);
    }

    static int smeltAt(Chain c) {
        for (int i = 0; i < c.steps().size(); i++) if (c.steps().get(i) instanceof Step.Smelt s && s.here()) return i;
        return -1;
    }

    // ---- scenario 1: CU's shape - a smelt of 8 running ----

    /**
     * CU 21:02:25-21:04:29: the smelt runs at the furnace while paths land for the later legs, the furnace's input falls and the
     * bag gains the ingots - 20 AA ticks, the waiting test of the runner and the three open orders each tick: 0 trip
     * derivations (was: 2 a tick - the runner's key and the next order's - plus one per bag change and landing). The smelt's
     * furnace gone (a fact the step itself depends on): derived once, then held again on the new facts.
     */
    @Test
    void cu_aSmeltRunning_pathsLandAndTheBagGainsIngots_noDerivation_theFurnaceGone_once() {
        Cu cu = cu();
        Chain ch = Goals.chain(cu.bb(), cu.gs().get(3));       // the planning ask: the batch's trip
        assertTrue(ch.ok() && ch.why().startsWith(NeedChain.PLAYBOOK), ch.why());
        int at = smeltAt(ch);
        assertTrue(at >= 0, "a smelt here in the trip: " + ch.steps());
        Step smelt = ch.steps().get(at);
        Map<String, Integer> uses = ch.uses().get(at);
        assertTrue(uses.keySet().stream().anyMatch(k -> k.contains("raw_iron")), uses.toString());
        Map<String, Integer> furnace = new HashMap<>(Map.of("minecraft:raw_iron", 8));
        cu.bb().fact(BatchWay.RUN_FACT, new BatchWay.Run(cu.helmet().id(), smelt, uses, furnace));
        cu.bag(cuBag(11, 0));                                   // the 8 raw iron went into the furnace
        cu.tick();
        int p0 = BatchWay.priced("b1");
        for (int t = 1; t <= 20; t++) {
            cu.paths().landed++;                                // an answer for a later leg's walk
            if (t <= 8) {
                furnace.put("minecraft:raw_iron", 8 - t);       // the furnace's input falls
                cu.bag(cuBag(11, t));                           // the bag gains the ingots
            }
            cu.bb().fact(BatchWay.RUN_FACT, new BatchWay.Run(cu.helmet().id(), smelt, uses, new HashMap<>(furnace)));
            cu.tick();
        }
        assertEquals(p0, BatchWay.priced("b1"), "a smelt running: no trip derived in 20 ticks");
        cu.bb().in.furnaceKnown = false;                        // the smelt's furnace removed
        for (int t = 0; t < 5; t++) {
            cu.paths().landed++;
            cu.tick();
        }
        assertEquals(p0 + 1, BatchWay.priced("b1"), "the step's own fact changed: derived once");
    }

    /** Control: the same ticks with no typed run known (no hold, as before this fix) derive the trip on the bag and the landings. */
    @Test
    void cu_control_noRunKnown_theTicksDeriveTheTrip() {
        Cu cu = cu();
        Goals.chain(cu.bb(), cu.gs().get(3));
        cu.tick();
        int p0 = BatchWay.priced("b1");
        for (int t = 1; t <= 8; t++) {
            cu.paths().landed++;
            cu.bag(cuBag(11, t));
            cu.tick();
        }
        assertTrue(BatchWay.priced("b1") >= p0 + 8, "no hold: priced " + (BatchWay.priced("b1") - p0) + " times in 8 ticks");
    }

    // ---- the key: the batch's holds are the same whoever asks ----

    /**
     * CU 20:59-21:01 (`collect raw_iron 19`, a step that walks: no hold): nothing changes for 10 ticks - the trip is derived
     * at most once, whoever of the batch asks (the runner's holds are in every ask's key). Control: no typed run known - the
     * runner's key and the next order's differ, the trip is derived in turn, twice a tick (CU's "playbook 2").
     */
    @Test
    void cu_aWalkingStep_theRunnersHoldsInEveryKey_noDerivationInTurn() {
        Cu cu = cu();
        Goals.chain(cu.bb(), cu.gs().get(3));
        cu.bb().fact(BatchWay.RUN_FACT, new BatchWay.Run(cu.helmet().id(), new Step.Walk(5, 30, -16, 0), null, null));
        cu.tick();
        int p0 = BatchWay.priced("b1");
        for (int t = 0; t < 10; t++) cu.tick();
        assertEquals(p0, BatchWay.priced("b1"), "one key for the batch: kept");
        cu.bb().fact(BatchWay.RUN_FACT, null);
        cu.tick();
        int p1 = BatchWay.priced("b1");
        for (int t = 0; t < 10; t++) cu.tick();
        assertEquals(p1 + 20, BatchWay.priced("b1"), "the runner's key and the next order's: two derivations a tick (was)");
    }

    // ---- scenario 2: the fuel the trip burns stays ----

    static List<String> fuels(Chain c) {
        List<String> out = new ArrayList<>();
        for (Map<String, Integer> u : c.uses()) if (u != null) for (String k : u.keySet()) if (k.startsWith(NeedChain.FUEL_USE)) out.add(k.substring(NeedChain.FUEL_USE.length()));
        return out;
    }

    /**
     * The kit trip priced with 9 coal in the bag burns coal (its smelts: 3 and 24 raw iron, 1 and 3 coal at 1600 ticks a coal,
     * 200 a smelt). The bag then gains a coal block (16000 ticks: one covers the 24 smelts, fewer pieces than 3 coal - the
     * cheapest pick with no worth known): the trip is priced again (a fuel is a referenced count) and still burns coal while
     * the bag's coal covers it. Control: the same bag priced fresh picks the coal block for the 24.
     */
    @Test
    void aFuelChosenAsCoal_staysCoal_acrossRePrices_whileCoalIsInTheBag() {
        Z77PlaybookTest.data();
        World[] w = {Z77PlaybookTest.world(Z77PlaybookTest.kitBag(9), Map.of())};
        List<Goals.Goal> gs = new ArrayList<>();
        Blackboard bb = Z77PlaybookTest.batchBoard(new FakePort(), new Clock.Fake(10_000_000), () -> w[0], gs);
        Chain first = Goals.chain(bb, gs.get(0));
        assertTrue(first.ok(), first.why());
        assertFalse(fuels(first).isEmpty(), "the trip smelts: " + first.steps());
        assertTrue(fuels(first).stream().allMatch(f -> f.equals("minecraft:coal")), fuels(first).toString());
        Map<String, Integer> more = new HashMap<>(Z77PlaybookTest.kitBag(9));
        more.put("minecraft:coal_block", 1);
        w[0] = w[0].withBag(more);
        int p = BatchWay.priced("b1");
        Chain again = Goals.chain(bb, gs.get(0));
        assertEquals(p + 1, BatchWay.priced("b1"), "the bag's fuel changed: priced again");
        assertTrue(fuels(again).stream().allMatch(f -> f.equals("minecraft:coal")), "coal stays coal: " + fuels(again));
        BatchWay.clear();
        Chain fresh = Goals.chain(bb, gs.get(0));
        assertTrue(fuels(fresh).contains("minecraft:coal_block"), "control - priced fresh, the coal block is the cheapest pick: " + fuels(fresh));
    }
}
