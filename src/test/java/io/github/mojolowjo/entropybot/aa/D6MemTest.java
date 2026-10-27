package io.github.mojolowjo.entropybot.aa;

import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.aa.brain.LootBook;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.39.z78 D6 (run DP: the node budget): heap bytes a kept node takes - run DJ's first 20 plan points as distinct requests,
 * their trees kept, then forgotten; the difference over the nodes (printed, "D6 mem"). PlanNodes.INNER is sized from it.
 */
class D6MemTest {
    static long used() {
        for (int i = 0; i < 5; i++) { System.gc(); try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    @Test
    void bytesPerNode() {
        Z77PlaybookTest.data();
        PlanNodes.forgetMine();
        PlanNodes.on(true);
        LootBook book = D6ReplayTest.book();
        // many distinct requests: one root per plan point (the keys differ by a count), so the trees stay kept
        D6ReplayTest.Facts f = new D6ReplayTest.Facts();
        List<NeedChain.Legs> keepOut = new ArrayList<>();
        int points = 0;
        long before = used();
        for (JsonObject e : D6ReplayTest.events("runDJ-z78h")) {
            f.apply(e);
            if (!"plan".equals(e.get("k").getAsString()) || f.orders.isEmpty()) continue;
            if (++points > 20) break;
            List<Playbook.Target> t = new ArrayList<>();
            int k = 0;
            for (var o : f.orders.entrySet()) t.add(new Playbook.Target(o.getKey(), o.getValue() + (k++ == 0 ? points : 0)));
            Playbook pb = Playbook.derive(t, Playbook.facts(f.world(book), null));
            if (pb.ok()) NeedChain.resolvePlaybookLegs(pb, f.world(book));
        }
        int nodes = PlanNodes.nodes().size();
        long with = used();
        PlanNodes.forgetMine();
        long without = used();
        org.junit.jupiter.api.Assertions.assertTrue(nodes > 100, "trees kept: " + nodes);
        System.out.printf("D6 mem: %d nodes kept, %d KB with, %d KB without -> %.0f bytes a node%n", nodes, with >> 10, without >> 10, (double) (with - without) / Math.max(1, nodes));
        PlanNodes.defaultOn();
        TestData.clear();
        Tuning.reset();
    }
}
