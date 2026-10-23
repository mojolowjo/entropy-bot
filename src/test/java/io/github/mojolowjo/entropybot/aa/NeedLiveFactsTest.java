package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.LootBook;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** live-facts (0.39.y3), one-planner: NeedChain's live source of an item: the loot book's blocks plus the item's own block, then the one Nearby fact. */
class NeedLiveFactsTest {
    static final int[] ME = {0, 64, 0};

    @BeforeEach
    void data() { TestData.planner().install(); }

    @AfterEach
    void clear() { TestData.clear(); }

    /** A fake book: iron ore drops raw iron, coal ore coal, a cod is a mob drop; each log block drops itself. */
    static final LootBook BOOK = new LootBook(List.of(
            new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:iron_ore", "minecraft:raw_iron", 1, 1, false, null),
            new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:coal_ore", "minecraft:coal", 1, 1, false, null),
            new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:oak_log", "minecraft:oak_log", 1, 1, false, null),
            new LootBook.Source(LootBook.Kind.BLOCK, "minecraft:spruce_log", "minecraft:spruce_log", 1, 1, false, null),
            new LootBook.Source(LootBook.Kind.MOB, "minecraft:cod", "minecraft:cod", 1, 1, false, null)), "test");

    /** Items that place a block: the logs and the ore item; raw iron, coal and cod place none. */
    static String ownBlock(String item) { return item.endsWith("_log") || item.endsWith("_ore") ? item : null; }

    /** A world: iron ore 10 blocks east, a spruce tree 20 west (leaves by it), an oak log stack in a build 3 north (no leaves). */
    static final Map<String, String> CELLS = Map.of("10 64 0", "minecraft:iron_ore", "-20 64 0", "minecraft:spruce_log", "0 64 3", "minecraft:oak_log");

    static final Nearby.Scan SCAN = new Nearby.Scan() {
        @Override public List<int[]> logs(int radius) { return find(Set.of("minecraft:oak_log", "minecraft:spruce_log"), radius); }
        @Override public List<int[]> find(Set<String> blocks, int radius) {
            List<int[]> out = new ArrayList<>();
            for (var e : CELLS.entrySet()) if (blocks.contains(e.getValue())) {
                String[] p = e.getKey().split(" ");
                out.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])});
            }
            return out;
        }
        @Override public boolean isLog(String block) { return block.endsWith("_log"); }       // the logs tag, faked
        @Override public boolean fairOk(int x, int y, int z) { return true; }
        @Override public boolean leavesNear(int x, int y, int z, int reach) { return x == -20; }
        @Override public boolean allowed(int x, int y, int z) { return true; }
        @Override public String blockAt(int x, int y, int z) { return CELLS.get(x + " " + y + " " + z); }
    };

    static Spot source(String word) {
        return Nearby.nearest(SCAN, ME, Nearby.sourceBlocks(word, BOOK, NeedLiveFactsTest::ownBlock), () -> null);
    }

    static final Function<String, Spot> SEEN = NeedLiveFactsTest::source;

    @Test
    void rawIronResolvesToTheIronOreAtItsDistance() {
        Spot s = source("raw_iron");
        assertNotNull(s);
        assertEquals("minecraft:iron_ore", s.block());
        assertEquals(10.0, s.dist(), 1e-9);
    }

    @Test
    void aMobDropWithNoBlockResolvesToNullWithoutAThrow() {
        assertTrue(Nearby.sourceBlocks("cod", BOOK, NeedLiveFactsTest::ownBlock).isEmpty());
        assertNull(assertDoesNotThrow(() -> source("cod")));
        assertTrue(Nearby.sourceBlocks("cod", null, NeedLiveFactsTest::ownBlock).isEmpty(), "no loot book: no block either");
    }

    @Test
    void aLogResolvesToATreeNotTheBuild() {
        Spot s = source("spruce_log");
        assertNotNull(s);
        assertEquals(-20, s.x());
        assertEquals("minecraft:spruce_log", s.block());
        assertNull(source("oak_log"), "the oak stack at 3 has no leaves by it: no tree; an item is its own id, no log family");
    }

    @Test
    void anExploreForCoalEndsWhenTheScanReportsCoalOre() {
        Map<String, String> cells = new java.util.HashMap<>();
        Nearby.Scan scan = new Nearby.Scan() {
            @Override public List<int[]> logs(int radius) { return List.of(); }
            @Override public List<int[]> find(Set<String> blocks, int radius) {
                List<int[]> out = new ArrayList<>();
                for (var e : cells.entrySet()) if (blocks.contains(e.getValue())) {
                    String[] p = e.getKey().split(" ");
                    out.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])});
                }
                return out;
            }
            @Override public boolean isLog(String block) { return false; }
            @Override public boolean fairOk(int x, int y, int z) { return true; }
            @Override public boolean leavesNear(int x, int y, int z, int reach) { return false; }
            @Override public boolean allowed(int x, int y, int z) { return true; }
            @Override public String blockAt(int x, int y, int z) { return cells.get(x + " " + y + " " + z); }
        };
        Function<String, Spot> source = w -> Nearby.nearest(scan, ME, Nearby.sourceBlocks(w, BOOK, NeedLiveFactsTest::ownBlock), () -> null);
        // nothing known: a chain's explore names the item it looks for (here the oak log), and the explore for coal waits on its item
        NeedChain.Chain c = NeedChain.resolve("oak_planks", 4, new NeedChain.World(Map.of(), false, source));
        assertTrue(c.explore(), c.why());
        assertEquals("oak_log", ((Step.Explore) c.steps().get(c.steps().size() - 1)).until());
        Step.Explore ex = new Step.Explore(NeedChain.exploreMinutes(), "coal");
        assertNull(io.github.mojolowjo.entropybot.cave.ExploreRules.untilFound(ex.until(), source), "the walk goes on while no coal is known");
        cells.put("6 60 0", "minecraft:coal_ore");
        String end = io.github.mojolowjo.entropybot.cave.ExploreRules.untilFound(ex.until(), source);
        assertNotNull(end, "coal ore came into the scan: the explore ends");
        assertTrue(end.contains("coal_ore") && end.contains("6 60 0"), end);
    }

    @Test
    void anOreThePickaxeDoesNotMeetIsNotTakenOnTheWay() {
        assertFalse(io.github.mojolowjo.entropybot.cave.ExploreRules.toolMeets(1, 0), "coal ore bare-handed");
        assertTrue(io.github.mojolowjo.entropybot.cave.ExploreRules.toolMeets(1, 1));
        assertFalse(io.github.mojolowjo.entropybot.cave.ExploreRules.toolMeets(-1, 4), "an unknown tier is not met");
    }

    @Test
    void theChainSourcesRawIronLiveFromTheOre() {
        NeedChain.Chain c = NeedChain.resolve("raw_iron", 1, new NeedChain.World(Map.of("minecraft:stone_pickaxe", 1), false, SEEN));     // iron ore asks for a stone pickaxe; S26: the scan knows a 1-ore cluster
        assertFalse(c.explore(), c.why());
        boolean walk = false, collect = false;
        for (Step st : c.steps()) {
            if (st instanceof Step.Walk w && w.x() == 10) walk = true;
            if (st instanceof Step.Collect k && k.item().equals("raw_iron")) collect = true;
        }
        assertTrue(!walk && collect, "chop B2: no walk step before a block source, the collect paths itself: " + c.steps());
    }
}
