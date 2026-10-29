package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Brain;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Thing;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** 0.39.z28 aa debug: the tree of an order priced by the one chain, recorded read only (docs/SCENARIOS.md S4; NeedChainTest's no-tree world). */
class DebugTreeTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    @AfterEach
    void clear() { TestData.clear(); }

    static Thing.Result item(String id) {
        return new Thing.Result(id, "minecraft:" + id, false, List.of("minecraft:" + id), List.of("minecraft:" + id), 0, null, id, null);
    }

    String s4Tree() {
        ScenariosTest.world("wooden", "stone", "iron", "golden");
        bb.fact(NeedChain.WORLD_FACT, (Supplier<NeedChain.World>) () -> ScenariosTest.w(Map.of(), false, false, ScenariosTest.s4Seen()));
        Goals.Goal g = Goals.draft(item("cobblestone"), 3, 0, clock.now());
        return DebugTree.order(bb, g, "want cobblestone 3", null, List.of("score: test"), List.of());
    }

    @Test
    void s4TheTreeShowsEveryWayItsSecondsAndTheDroppedReasons() {
        String t = s4Tree();
        assertTrue(t.contains("need: 3 more minecraft:cobblestone"), t);
        assertTrue(t.contains("chain: cobblestone <- dig cobblestone seen 12 m with wooden_pickaxe"), t);
        // the pickaxe requirement's ways, each with its verdict in the chain's words
        assertTrue(t.matches("(?s).*way wooden_pickaxe -> picked: 3[23]\\.\\d s.*"), "wooden picked at S4's 28.95 s + the table's take 3.75 (owner 2026-10-29): " + t);
        assertTrue(t.matches("(?s).*way golden_pickaxe -> dropped: no known source.*"), t);
        assertTrue(t.matches("(?s).*way iron_pickaxe -> dropped: no known source.*"), t);
        assertTrue(t.matches("(?s).*way stone_pickaxe -> dropped: loop.*"), t);
        // the facts under the ways: the tree's walk and break, the stone's walk and break
        assertTrue(t.contains("walk to 30 64 0 (30 m straight): Baritone path, complete, 9.0 s"), t);
        assertTrue(t.matches("(?s).*break oak_log x\\d+: tool hand, 3\\.0 s each.*"), t);
        assertTrue(t.contains("walk to 12 60 3 (12 m straight): Baritone path, complete, 3.6 s"), t);
        assertTrue(t.matches("(?s).*break stone x3: tool wooden_pickaxe, 1\\.5 s each = 4\\.5 s.*"), t);
        assertTrue(t.contains("dropped (3):"), t);
        assertTrue(t.contains("first step: step 1/"), t);
        assertTrue(t.contains(" started (of "), t);
    }

    @Test
    void theUntilSetNamesTheWaysThatPutEachWordThere() {
        TestData d = TestData.planner();
        for (String m : List.of("wooden", "stone", "iron", "diamond")) d.tag(m + "_pickaxe", "minecraft:pickaxes");
        d.install();
        bb.fact(NeedChain.WORLD_FACT, (Supplier<NeedChain.World>) () -> io.github.mojolowjo.entropybot.aa.FakePaths.paths(new NeedChain.World(Map.of(), false, NeedChainTest.seen(null, NeedChainTest.STONE))));
        String t = DebugTree.order(bb, Goals.draft(item("stone_pickaxe"), 1, 0, clock.now()), "want stone_pickaxe", w -> Set.of("block of " + w), null, null);
        int at = t.indexOf("explore until one of these is known (");
        assertTrue(at > 0, t);
        String part = t.substring(at, t.indexOf("dropped (", at));
        List<String> rows = part.lines().skip(1).filter(l -> !l.isBlank()).toList();
        assertTrue(rows.size() >= 2, "rows: " + t);
        for (String r : rows) {
            assertTrue(r.contains(" - blocks: block of "), r);
            assertTrue(r.matches(".* - put there by: \\S.*") && !r.endsWith("the chain's own leaf"), "each word names its way: " + r);
        }
        assertTrue(rows.get(0).trim().startsWith("oak_log") && rows.get(0).contains("wooden_pickaxe"), part);
        assertTrue(rows.stream().anyMatch(r -> r.contains("iron_pickaxe")), "the iron way's word: " + part);
    }

    @Test
    void aDebugRunWritesNothing() {
        Brain br = new Brain("brain", "t");
        int desires = br.desires().size();
        s4Tree();
        assertTrue(Goals.of(bb).isEmpty(), "no goal saved: " + Goals.of(bb));
        assertTrue(port.started.isEmpty(), "no job started: " + port.started);
        assertTrue(port.told.isEmpty(), port.told.toString());
        assertEquals(desires, br.desires().size(), "the brain's desires unchanged");
    }

    /** S43 (0.39.z36, rule 15 NOT_LOADED): no world fact yet: the header and ONE body line with what is not ready and the time. */
    @Test
    void s43_noFactsYetIsOneNotLoadedLineNotATree() {
        bb.fact(Goals.NOT_READY_FACT, (Supplier<String>) () -> Goals.notReadyLine("aa is off and the senses run only while it is on (none read yet)", 100, "aa on, then try again"));
        String t = DebugTree.order(bb, Goals.draft(item("torch"), 4, 0, clock.now()), "want torch 4", null, List.of("score: test"), List.of("stations: x"));
        List<String> lines = t.lines().toList();
        assertEquals(2, lines.size(), t);
        assertTrue(lines.get(0).startsWith("aa debug want torch 4 " + DebugTree.DRY), t);
        assertEquals("  senses not ready: aa is off and the senses run only while it is on (none read yet) (world up 100 s); aa on, then try again", lines.get(1));
        Blackboard bare = new Blackboard(clock, port, new Tracer());
        assertTrue(DebugTree.order(bare,Goals.draft(item("torch"), 4, 0, clock.now()), "want torch 4", null, null, null)
                .endsWith("senses not ready: the reason is not known (no adapter installed it) (world up unknown); try again"), "no adapter: said so");
    }

    @Test
    void outsideTracedTheRecorderRecordsNothing() {
        ScenariosTest.world("wooden", "stone", "iron", "golden");
        NeedChain.Trace t = NeedChain.trace();
        NeedChain.traced(t, () -> NeedChain.resolve("cobblestone", 3, ScenariosTest.w(Map.of(), false, false, ScenariosTest.s4Seen())));
        int lines = t.lines(0).size();
        assertTrue(lines > 0, "traced records");
        NeedChain.resolve("cobblestone", 3, ScenariosTest.w(Map.of(), false, false, ScenariosTest.s4Seen()));
        assertEquals(lines, t.lines(0).size(), "a normal run adds nothing to an old trace");
        NeedChain.Trace fresh = NeedChain.trace();
        NeedChain.resolve("cobblestone", 3, ScenariosTest.w(Map.of(), false, false, ScenariosTest.s4Seen()));
        assertTrue(fresh.lines(0).isEmpty() && fresh.untilFrom.isEmpty(), "a normal chain run leaves the trace empty");
    }
}
