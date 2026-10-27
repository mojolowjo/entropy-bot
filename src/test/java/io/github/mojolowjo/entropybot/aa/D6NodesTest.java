package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 D6 (docs/designs/D6-planner-incremental.md, "Scenarios"): the planner's nodes keep their sums. Each scenario
 * resolves twice with one fact changed in between and checks (a) the incremental resolve gives the same chain as the old
 * one on the new facts (deep-equal, PlanNodes.old), (b) which nodes were resolved again, by each node's resolve counter.
 * Verify mode is on: every reuse is computed again and compared (a difference throws).
 */
class D6NodesTest {
    @BeforeEach
    void on() {
        PlanNodes.forgetMine();
        PlanNodes.on(true);
        PlanNodes.verify(true);
    }

    @AfterEach
    void off() {
        PlanNodes.verify(false);
        PlanNodes.defaultOn();
        PlanNodes.forgetMine();
        TestData.clear();
        Tuning.reset();
        BatchWay.clear();
    }

    /** The same call with the old resolve (no node read or kept) and with the incremental one: equal, and the incremental one returned. */
    static <T> T both(Supplier<T> run) {
        T old = PlanNodes.old(run);
        T now = run.get();
        assertTrue(Deep.equal(old, now), "the old resolve and the incremental one differ: " + diff(old, now));
        return now;
    }

    /** The first part where two values differ (a record's component, a list's index), as text. */
    static String diff(Object a, Object b) {
        if (Deep.equal(a, b)) return "(equal)";
        if (a != null && b != null && a.getClass() == b.getClass() && a.getClass().isRecord()) {
            for (var p : a.getClass().getRecordComponents()) {
                try {
                    p.getAccessor().setAccessible(true);
                    Object x = p.getAccessor().invoke(a), y = p.getAccessor().invoke(b);
                    if (!Deep.equal(x, y)) return p.getName() + ": " + diff(x, y);
                } catch (ReflectiveOperationException e) {
                    return "? " + e;
                }
            }
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            for (int i = 0; i < Math.min(x.size(), y.size()); i++) if (!Deep.equal(x.get(i), y.get(i))) return "[" + i + "] " + diff(x.get(i), y.get(i));
            return "sizes " + x.size() + " vs " + y.size();
        }
        return "\n old " + a + "\n now " + b;
    }

    // ---- 1. a bag count no node uses changes: zero nodes dirty, the stored chain returned ----

    @Test
    void s1_aBagCountNoNodeUsesChanges_zeroDirty_theStoredChainReturned() {
        ScenariosTest.world("wooden");
        Map<String, Integer> bag = new LinkedHashMap<>(Map.of("minecraft:oak_log", 1, "minecraft:dirt", 5));
        Chain a = both(() -> NeedChain.resolve("planks", 4, ScenariosTest.w(bag, false, false, Map.of())));
        assertTrue(a.ok() && a.why().startsWith("planks <- 4 oak_planks <- craft: 1 oak_log"), a.why());
        bag.put("minecraft:dirt", 7);                         // a word the chain never reads
        Chain b = NeedChain.resolve("planks", 4, ScenariosTest.w(bag, false, false, Map.of()));
        PlanNodes.Stats st = PlanNodes.last();
        assertEquals(0, st.resolved(), "nothing re-resolved: " + st.text());
        assertEquals(0, st.dirty(), "nothing dirty: " + st.text());
        assertTrue(Deep.equal(a, b), "the stored chain: " + b.why());
        assertNotSame(a.steps().get(0), b.steps().get(0), "handed out as fresh step objects");
        assertTrue(Deep.equal(b, PlanNodes.old(() -> NeedChain.resolve("planks", 4, ScenariosTest.w(bag, false, false, Map.of())))), "as the old resolve");
        // a word it reads: the request is resolved again
        bag.put("minecraft:oak_log", 2);
        both(() -> NeedChain.resolve("planks", 4, ScenariosTest.w(bag, false, false, Map.of())));
        assertEquals(2, PlanNodes.resolves("root", "planks"), PlanNodes.nodes().toString());
    }

    // ---- 2. a path answer lands for one way: that way's node and its ancestors re-sum, the sibling ways untouched ----

    @Test
    void s2_aPathAnswerLandsForOneWay_onlyThatWayAndItsAncestorsReSum() {
        ScenariosTest.world("wooden");
        Map<String, PathPrice.Answer> over = new HashMap<>();
        over.put("20,64,0", PathPrice.Answer.computing(null));          // tree B's walk: not known yet
        PathPrice paths = (x, y, z, dist) -> over.getOrDefault(x + "," + y + "," + z, PathPrice.Answer.priced(new PathPrice.Walk(FakePaths.walkS(dist))));
        Map<String, List<Spot>> seen = Map.of("oak_log", List.of(ScenariosTest.tree(10, 4), ScenariosTest.tree(20, 4), ScenariosTest.tree(30, 4)));
        Supplier<Chain> run = () -> NeedChain.resolve("oak_log", 1, ScenariosTest.w(Map.of(), false, false, seen).withPath(paths));
        Chain a = both(run);
        assertTrue(a.ok() && a.why().contains("cut tree seen 10 m"), "tree A while B's walk computes: " + a.why());
        over.put("20,64,0", PathPrice.Answer.priced(new PathPrice.Walk(1.0)));   // B's answer lands: 1 s
        Chain b = both(run);
        assertTrue(b.why().contains("cut tree seen 20 m"), "tree B now: " + b.why());
        assertEquals(2, PlanNodes.resolves("root", "oak_log"), "the request re-sums");
        assertEquals(2, PlanNodes.resolves("obtain", "oak_log"), "the need re-sums");
        assertEquals(2, PlanNodes.resolves("way", "tree 20 m"), "the way whose path landed re-sums");
        assertEquals(1, PlanNodes.resolves("way", "tree 10 m"), "its sibling way is untouched");
        assertEquals(1, PlanNodes.resolves("way", "tree 30 m"), "its ranked sibling is untouched");
        PlanNodes.Stats st = PlanNodes.last();
        assertTrue(st.reused() >= 2 && st.dirty() >= 2, st.text());
    }

    // ---- 3. a new seen source for a needed item: the need's node is dirty, re-resolved, the collect way offered ----

    @Test
    void s3_aNewSeenSource_dirtiesTheNeed_theDigWayIsOffered() {
        ScenariosTest.world("wooden", "stone");
        Map<String, List<Spot>> seen = new HashMap<>();
        Map<String, Integer> bag = Map.of("minecraft:wooden_pickaxe", 1);
        Supplier<Chain> run = () -> NeedChain.resolve("cobblestone", 3, ScenariosTest.w(bag, false, false, seen));
        Chain a = both(run);
        assertTrue(a.explore(), "nothing known: the explore: " + a.why());
        seen.put("minecraft:cobblestone", List.of(ScenariosTest.STONE_12));   // the J2 seen trigger's fact
        Chain b = both(run);
        assertFalse(b.explore(), b.why());
        assertTrue(b.steps().stream().anyMatch(s -> s instanceof Step.Collect k && k.item().contains("cobblestone")), "the dig of the seen stone: " + b.steps());
        assertEquals(2, PlanNodes.resolves("obtain", "cobblestone"), "the need was dirty and re-resolved");
    }

    // ---- a playbook trip: one path answer changes, the rest of the trip reused where its facts hold ----

    @Test
    void playbook_aLandedPathRePricesTheTrip_asTheOldResolve() {
        TestData d = Z77PlaybookTest.data();
        Map<String, PathPrice.Answer> over = new HashMap<>();
        PathPrice paths = (x, y, z, dist) -> over.getOrDefault(x + "," + y + "," + z, PathPrice.Answer.priced(new PathPrice.Walk(y < 60 ? (64 - y) * 0.6 : dist / io.github.mojolowjo.entropybot.aa.brain.Unit.WALK_BPS)));
        Map<String, List<Spot>> seen = new HashMap<>();
        seen.put("minecraft:oak_log", List.of(ScenariosTest.tree(12, 5), ScenariosTest.tree(25, 6)));
        // the sticks, the tables and the furnace from wood: the trees' walks are read by the trip's first leg
        World one = Z77PlaybookTest.world(Map.of("minecraft:stone_pickaxe", 1, "minecraft:coal", 8, "minecraft:cobblestone", 16), seen).withPath(paths);   // one loot book: the epoch holds
        Supplier<World> world = () -> one;
        Playbook pb = Playbook.derive(Z77PlaybookTest.KIT, Playbook.facts(world.get(), null));
        assertTrue(pb.ok(), pb.why());
        NeedChain.Legs a = both(() -> NeedChain.resolvePlaybookLegs(pb, world.get()));
        assertNotNull(a.chain(), "a chain");
        over.put("12,64,0", PathPrice.Answer.priced(new PathPrice.Walk(40.0)));   // the near tree's walk landed dearer
        NeedChain.Legs b = both(() -> NeedChain.resolvePlaybookLegs(pb, world.get()));
        assertNotNull(b.chain());
        PlanNodes.Stats st = PlanNodes.last();
        assertTrue(st.resolved() > 0 && st.reused() > 0, "the trip re-summed where the walk was read, the rest reused: " + st.text());
        over.put("12,64,0", PathPrice.Answer.unreachable("no path"));
        both(() -> NeedChain.resolvePlaybookLegs(pb, world.get()));
        assertTrue(d != null);
    }

    // ---- a node that cannot be kept (it read the unreachable facts whole: the climb) still dirties its request ----

    @Test
    void aNodeThatCannotBeKept_itsFactsStillDirtyTheRequest() {
        ScenariosTest.world("wooden", "stone", "iron");
        Map<String, PathPrice.Answer> over = new HashMap<>();
        over.put("0,84,0", PathPrice.Answer.unreachable("no path A"));
        PathPrice paths = (x, y, z, dist) -> over.getOrDefault(x + "," + y + "," + z, PathPrice.Answer.priced(new PathPrice.Walk(FakePaths.walkS(dist))));
        // 20 blocks under ground, nothing known of the need: the explore from the surface - the walk up has no path, so the climb
        Supplier<World> world = () -> ScenariosTest.w(Map.of("minecraft:stone_pickaxe", 1), false, false, Map.of()).withPath(paths)
                .withWhere(new NeedChain.WhereFacts(w -> io.github.mojolowjo.entropybot.aa.brain.GenWhere.unknown("test"), () -> new NeedChain.Surface(20, 0, 84, 0, 20),
                        () -> new int[]{0, 64, 0}, () -> null));
        Chain a = both(() -> NeedChain.resolve("oak_log", 1, world.get()));
        assertTrue(a.why().contains("no path A"), a.why());
        over.put("0,84,0", PathPrice.Answer.unreachable("no path B"));
        Chain b = both(() -> NeedChain.resolve("oak_log", 1, world.get()));
        assertTrue(b.why().contains("no path B"), "not the kept chain: " + b.why());
    }

    // ---- the request kept across a changed unused fact, and the effects run again on a reuse ----

    @Test
    void aReusedRequestRunsItsEffectsAgain() {
        ScenariosTest.world("wooden");
        List<String> ranked = new ArrayList<>();
        PathPrice paths = new PathPrice() {
            @Override public Answer price(int x, int y, int z, double dist) { return Answer.priced(new Walk(FakePaths.walkS(dist))); }
            @Override public void rankedOut(int x, int y, int z) { ranked.add(x + " " + y + " " + z); }
        };
        Map<String, List<Spot>> seen = Map.of("oak_log", List.of(ScenariosTest.tree(10, 4), ScenariosTest.tree(20, 4), ScenariosTest.tree(30, 4)));
        PlanNodes.verify(false);                             // count the calls of one resolve each
        NeedChain.resolve("oak_log", 1, ScenariosTest.w(Map.of(), false, false, seen).withPath(paths));
        assertEquals(List.of("30 64 0"), ranked, "the third tree ranked out (pathCost.pricedTop 2)");
        NeedChain.resolve("oak_log", 1, ScenariosTest.w(Map.of(), false, false, seen).withPath(paths));
        assertEquals(0, PlanNodes.last().resolved(), PlanNodes.last().text());
        assertEquals(List.of("30 64 0", "30 64 0"), ranked, "the kept request's effect ran again");
    }
}
