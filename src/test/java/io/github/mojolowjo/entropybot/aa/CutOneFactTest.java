package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.ChopSet;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The review of w058-2 (cloud tester 2, probe round 3): the cut's hand breaks only the log blocks that drop the ordered
 * item and leaves the owner's excluded kinds out, but the planner still (1) sized a tree by every log-tagged cell (one
 * acacia_log joined to 51 acacia_wood was "52 acacia_log", one cut priced as covering an order of 16) and (2) offered a
 * tree of a kind "kinds logs exclude" leaves out, whose cut the hand then refused. Rule 23 (2): the planner reads the
 * hand's block set and the hand's kind test.
 */
class CutOneFactTest {
    static final String LOG = "minecraft:acacia_log", BARK = "minecraft:acacia_wood", OAK = "minecraft:oak_log";
    static final int[] ME = {0, 64, 0};

    @org.junit.jupiter.api.BeforeEach
    void data() { TestData.planner().install(); }

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    /** A scan over a map of cells; {@code excluded}: the log blocks the owner's logs kind leaves out (the hand's test). */
    record World(Map<String, String> cells, Set<String> excluded) implements Nearby.Scan {
        static String k(int x, int y, int z) { return x + " " + y + " " + z; }

        World put(int x, int y, int z, String block) { cells.put(k(x, y, z), block); return this; }

        private List<int[]> all(java.util.function.Predicate<String> test) {
            List<int[]> out = new ArrayList<>();
            for (var e : cells.entrySet()) {
                if (!test.test(e.getValue())) continue;
                String[] p = e.getKey().split(" ");
                out.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])});
            }
            return out;
        }

        @Override public List<int[]> logs(int radius) { return all(this::isLog); }
        @Override public List<int[]> find(Set<String> blocks, int radius) { return all(blocks::contains); }
        @Override public boolean isLog(String block) { return block.endsWith("_log") || block.endsWith("_wood"); }
        @Override public boolean fairOk(int x, int y, int z) { return true; }
        @Override public boolean leavesNear(int x, int y, int z, int reach) { return true; }
        @Override public boolean allowed(int x, int y, int z) { return true; }
        @Override public String blockAt(int x, int y, int z) { return cells.get(k(x, y, z)); }
        @Override public Boolean open(int x, int y, int z) { return true; }
        @Override public String cutLeftOut(String block) { return ChopSet.leftOut(block, this::isLog, b -> !excluded.contains(b)); }
    }

    static World world(String... excluded) { return new World(new LinkedHashMap<>(), Set.of(excluded)); }

    /** The w058-2 tree: one acacia_log at the foot, 51 bark blocks (acacia_wood) joined to it above. */
    static World barkTree() {
        World w = world().put(5, 64, 0, LOG);
        for (int i = 1; i <= 51; i++) w.put(5, 64 + i, 0, BARK);
        return w;
    }

    @Test
    void aTreeIsSizedByTheNeedsBlocksNotEveryLogTaggedCell() {
        World w = barkTree();
        Spot item = Nearby.nearest(w, ME, Set.of(LOG), () -> null);
        assertEquals(5, item.x());
        assertEquals(1, item.count(), "an order of acacia_log: the hand cuts the one block that drops it, the 51 bark blocks are no part of the tree");
        Spot tag = Nearby.nearest(w, ME, Set.of(LOG, BARK, OAK), () -> null);
        assertEquals(52, tag.count(), "a need for any log takes the whole tree, bark too");
        Spot bark = Nearby.nearest(w, ME, Set.of(BARK), () -> null);
        assertEquals(51, bark.count(), "an order of the bark: the 51 joined bark blocks, not the log under them");
        // the old count (every log-tagged cell of the scan) was 52 for all three
        assertEquals(52, Nearby.treeLogs(w.logs(0), new int[]{5, 64, 0}, Integer.MAX_VALUE));
    }

    @Test
    void theTreeCountStopsAtAnotherKindAndAtACellNotLoaded() {
        World w = world().put(5, 64, 0, LOG).put(5, 65, 0, LOG).put(5, 66, 0, OAK).put(5, 67, 0, LOG);
        List<int[]> cells = w.find(Set.of(LOG, OAK), 0);
        assertEquals(2, Nearby.treeCut(w, cells, Set.of(LOG), new int[]{5, 64, 0}), "the oak log between is no acacia_log: the log over it is not joined");
        assertEquals(4, Nearby.treeCut(w, cells, Set.of(LOG, OAK), new int[]{5, 64, 0}));
        assertEquals(4, Nearby.treeCut(w, cells, null, new int[]{5, 64, 0}), "no set named: any log of the list");
        List<int[]> more = new ArrayList<>(cells);
        more.add(new int[]{5, 68, 0});                       // in the scan's list, its block not readable now
        assertEquals(4, Nearby.treeCut(w, more, null, new int[]{5, 64, 0}), "a cell not loaded is not counted");
        assertEquals(1, Nearby.treeCut(w, List.of(), Set.of(LOG), new int[]{5, 64, 0}), "at least the start");
    }

    @Test
    void anExcludedKindIsPassedOverWithTheReasonAndNeverCounted() {
        // the nearer tree is oak (excluded), with an acacia log joined to its top; the next tree is acacia
        World w = world(OAK).put(3, 64, 0, OAK).put(3, 65, 0, OAK).put(20, 64, 0, LOG).put(20, 65, 0, LOG).put(20, 66, 0, OAK);
        Nearby.Source s = Nearby.source(w, ME, Set.of(LOG, OAK), () -> null);
        assertEquals(20, s.spot().x(), "the oak 3 m away is a kind the cut leaves out: the acacia is the answer");
        assertEquals(2, s.spot().count(), "the oak log joined to the acacia is not cut: not counted");
        assertEquals(Map.of(OAK + " left out of the cut (" + ChopSet.EXCLUDED + ")", 2), s.refused(), "said, per block, with the reason");
        assertTrue(s.line().contains("2 oak_log left out of the cut (kinds logs exclude), not counted"), s.line());
        // the "a tree nearby" fact reads the same test
        Spot tree = Nearby.log(w, ME, () -> null);
        assertEquals(20, tree.x());
        assertEquals(2, tree.count());
        // nothing excluded: the oak is the nearest tree, as before
        World open = world().put(3, 64, 0, OAK).put(3, 65, 0, OAK).put(20, 64, 0, LOG);
        assertEquals(3, Nearby.source(open, ME, Set.of(LOG, OAK), () -> null).spot().x());
        assertTrue(Nearby.source(open, ME, Set.of(LOG, OAK), () -> null).refused().isEmpty());
    }

    @Test
    void aSightedTreeOfAnExcludedKindIsNoSourceEither() {
        World w = world(OAK);
        Spot sighted = new Spot(40, 64, 0, 40, OAK);
        Nearby.Source s = Nearby.source(w, ME, Set.of(LOG, OAK), () -> sighted);
        assertNull(s.spot());
        assertEquals("none", s.from());
        assertEquals(Map.of(OAK + " left out of the cut (" + ChopSet.EXCLUDED + ")", 1), s.refused());
        Spot unread = new Spot(40, 64, 0, 40, null);
        assertSame(unread, Nearby.source(w, ME, Set.of(LOG, OAK), () -> unread).spot(), "its wood not read: not known is not a no (rule 15)");
    }

    // ---- the planner's refusal up front ----

    static final String REFUSAL = "no oak_log source: every log block that drops it is left out of the owner's logs kind (oak_log: kinds logs exclude; \"kinds logs include <id>\" lets it be cut)";

    private static NeedChain.World planner(NeedChain.Lookup seen) {
        ScenariosTest.world("wooden").install();
        return FakePaths.paths(new NeedChain.World(Map.of(), false, false, seen, null, null, null, 0));
    }

    @Test
    void anOrderOfAnExcludedKindIsRefusedBeforeAnyExplore() {
        NeedChain.Lookup none = (word, skip) -> List.of();
        // no refusal said: the explore for the log, as before
        Chain open = NeedChain.resolve("oak_log", 16, planner(none));
        assertTrue(open.explore(), open.why());
        // the owner's logs kind leaves the item's only block out: no explore, the reason in the chain's words
        Chain c = NeedChain.resolve("oak_log", 16, planner(none.refusing(word -> word.equals("minecraft:oak_log") ? REFUSAL : null)));
        assertFalse(c.explore(), c.why() + " " + c.steps());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Explore || s instanceof Step.ChopTree), c.steps().toString());
        assertTrue((c.why() + " " + c.dropped()).contains(REFUSAL), c.why() + " " + c.dropped());
    }

    @Test
    void theRefusalStaysWithTheLookupThroughTheWorldsWraps() {
        int[] asked = {0};
        NeedChain.Lookup seen = ((NeedChain.Lookup) (word, skip) -> List.of()).refusing(word -> { asked[0]++; return word.endsWith("oak_log") ? REFUSAL : null; });
        NeedChain.World w = planner(seen);
        NeedChain.Lookup kept = (NeedChain.Lookup) w.seenAll();
        assertEquals(REFUSAL, kept.refused("minecraft:oak_log"));
        assertEquals(REFUSAL, kept.refused("oak_log"), "the short word is the same word");
        assertEquals(1, asked[0], "read once per word and World");
        assertNull(kept.refused("minecraft:acacia_log"));
        // an order's held spots (World#without) and the with-copies keep it
        NeedChain.World less = w.without((word, sp) -> false).withBag(Map.of("minecraft:stick", 1));
        assertEquals(REFUSAL, ((NeedChain.Lookup) less.seenAll()).refused("minecraft:oak_log"));
        // a plain lookup says none
        assertNull(((NeedChain.Lookup) (word, skip) -> List.of()).refused("minecraft:oak_log"));
    }
}
