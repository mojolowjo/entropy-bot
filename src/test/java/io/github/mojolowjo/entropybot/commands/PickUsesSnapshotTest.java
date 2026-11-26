package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.PlanNodes;
import io.github.mojolowjo.entropybot.aa.Playbook;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Merge review 2026-11-25 (12): on the planner worker a pickaxe's 'uses' fact not copied answered UNLIMITED and the readers
 * stopped at the first one, so each round fetched one more pickaxe's uses (one dropped resolve per pickaxe kind while the
 * bot stood). The bag holds iron_pickaxe and stone_pickaxe; the trip digs iron ore. Wanted: every pickaxe's uses are in the
 * first snapshot - no round of the worker path fetches a 'uses' fact.
 */
class PickUsesSnapshotTest {
    @BeforeEach
    void on() {
        PlanNodes.forgetMine();
        PlanNodes.on(true);
    }

    @AfterEach
    void off() {
        PlanNodes.defaultOn();
        PlanNodes.forgetMine();
        TestData.clear();
        Tuning.reset();
        BatchWay.clear();
    }

    static final Function<String, Integer> USES = id -> id.endsWith("iron_pickaxe") ? 250 : id.endsWith("stone_pickaxe") ? 131 : null;

    @Test
    void everyPickaxesUsesIsInTheFirstSnapshot_noRoundFetchesOne() {
        D6WorkerTest.data();
        D6WorkerTest.Live live = new D6WorkerTest.Live();
        live.bag.put("minecraft:iron_pickaxe", 1);
        NeedChain.World w = live.world().withUses(USES);
        PlannerSnapshot first = PlannerSnapshot.take(w, Set.of(), null, 1, 0);
        for (String p : List.of("minecraft:iron_pickaxe", "minecraft:stone_pickaxe"))
            assertTrue(first.copied(new PlanNodes.Fact("uses", p)), p + "'s uses copied from the first round");
        assertEquals(250, first.world().usesLeft().apply("minecraft:iron_pickaxe"));
        assertEquals(131, first.world().usesLeft().apply("minecraft:stone_pickaxe"));
        assertFalse(first.copied(new PlanNodes.Fact("uses", "minecraft:coal")), "no other word's uses");

        // the worker path's rounds (SyncPlanner's loop): no round's check finds a 'uses' fact missing
        Playbook pb = Playbook.derive(List.of(new Playbook.Target("minecraft:iron_helmet", 1), new Playbook.Target("minecraft:torch", 4)), Playbook.facts(w, null));
        assertTrue(pb.ok(), pb.why());
        Set<PlanNodes.Fact> keys = new HashSet<>();
        List<String> usesMisses = new ArrayList<>();
        boolean usesRead = false;
        for (int round = 0; round < PlannerPort.ROUNDS; round++) {
            Map<PlanNodes.Fact, Object> known = new HashMap<>();
            PlannerWorker.Done d = PlannerWorker.resolve(new PlannerWorker.Job("b1", round + 1, pb, Set.of(), PlannerSnapshot.take(w, keys, known, round + 1, 0)));
            assertNull(d.error(), String.valueOf(d.error()));
            for (PlanNodes.Fact f : d.read().facts().keySet()) if (f.field().equals("uses")) usesRead = true;
            List<String> changed = PlannerPort.check(d, w, known);
            for (String c : changed) if (c.startsWith(PlannerPort.MISS + "uses")) usesMisses.add("round " + round + ": " + c);
            keys.addAll(d.read().facts().keySet());
            if (changed.isEmpty()) break;
        }
        assertTrue(usesRead, "the trip prices the pickaxes' wear (the case is not vacuous)");
        assertTrue(usesMisses.isEmpty(), "no round fetched a pickaxe's uses: " + usesMisses);
    }
}
