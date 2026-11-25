package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.PlanNodes;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-own-aside-worker review (key order): the worker starts an own chain from its snapshot's bag less the held -d amount,
 * in the live World's order; the port's check read the bag of OwnAsk.pricedOn's World, made again, and a World's
 * constructor (Map.copyOf) orders its bag anew. The scans (the fuels, the pickaxes) and the whole bag are compared in
 * order, so a fresh own result with a held part was dropped as "bag fuels" / "bag pickaxes" until planner.dropsBeforeTake
 * rounds took it, on every new request - in the JVM sessions whose Map.copyOf order differs (the reviewer's scratch check:
 * 3 of 8). Here a bag is searched for whose order a World made again changes in this JVM, with several fuels and two
 * pickaxes and part of the order held; its own result is checked through the port, and the tick and the worker answer
 * alike on it.
 */
class OwnAskKeyOrderTest {
    static final String INGOT = "minecraft:iron_ingot";
    /** 5 iron_ingot -d, 3 of them held: 2 asked, 3 aside (Goals.ownAsk's answer). */
    static final BatchWay.OwnAsk ASK = new BatchWay.OwnAsk(INGOT, List.of(INGOT), 2, false, Map.of(INGOT, 3));
    static final List<String> FUELS = List.of("minecraft:coal", "minecraft:charcoal");
    static final List<String> PICKS = List.of("minecraft:stone_pickaxe", "minecraft:iron_pickaxe");
    static final List<String> FILL = List.of("minecraft:dirt", "minecraft:cobblestone", "minecraft:oak_planks", "minecraft:stick", "minecraft:sand",
            "minecraft:gravel", "minecraft:oak_sapling", "minecraft:torch", "minecraft:andesite", "minecraft:granite", "minecraft:diorite",
            "minecraft:flint", "minecraft:wheat_seeds", "minecraft:apple", "minecraft:bone", "minecraft:string", "minecraft:feather",
            "minecraft:clay_ball", "minecraft:birch_log", "minecraft:spruce_log", "minecraft:raw_copper", "minecraft:copper_ingot");

    @BeforeEach
    void on() {
        PlanNodes.forgetMine();
        PlanNodes.on(true);
        D6WorkerTest.data();
    }

    @AfterEach
    void off() {
        PlanNodes.defaultOn();
        PlanNodes.forgetMine();
        TestData.clear();
        Tuning.reset();
        BatchWay.clear();
    }

    static List<String> only(Map<String, Integer> bag, List<String> words) {
        List<String> out = new ArrayList<>();
        for (String k : bag.keySet()) if (words.contains(k)) out.add(k);
        return out;
    }

    /** D6WorkerTest.Live's World (its trees, loot book, strip band, walks) on {@code bag} in the order given. */
    static NeedChain.World world(Map<String, Integer> bag) {
        D6WorkerTest.Live live = new D6WorkerTest.Live();
        List<NeedChain.Spot> t = List.copyOf(live.trees);
        return new NeedChain.World(bag, false, false, word -> word.contains("log") ? t : List.of(), x -> List.of(), live.book, null, 0, live.paths())
                .withStrip(D6WorkerTest::strip)
                .withWhere(new NeedChain.WhereFacts(D6WorkerTest::where, () -> new NeedChain.Surface(0, 0, 64, 0, 0), () -> new int[]{0, 64, 0},
                        () -> new io.github.mojolowjo.entropybot.aa.brain.GenWhere.Base(D6WorkerTest.STONE, Integer.MIN_VALUE, null, "test")));
    }

    /** {@code bag} with the two words of each pair swapped in place (the same counts, another order). */
    static LinkedHashMap<String, Integer> swapped(Map<String, Integer> bag, List<String> pair) {
        List<String> keys = new ArrayList<>(bag.keySet());
        int a = keys.indexOf(pair.get(0)), b = keys.indexOf(pair.get(1));
        if (a >= 0 && b >= 0) java.util.Collections.swap(keys, a, b);
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        for (String k : keys) out.put(k, bag.get(k));
        return out;
    }

    /**
     * A live World whose bag (the held ingots, the two fuels, the two pickaxes, some filler) a World made again re-orders in
     * the fuels or the pickaxes, in this JVM; null: none of the tried bags does (Map.copyOf's order is set per JVM).
     */
    static NeedChain.World reordered() {
        for (int n = 0; n <= FILL.size(); n++) {
            for (int turn = 0; turn < 4; turn++) {
                List<String> words = new ArrayList<>();
                words.add(INGOT);
                words.addAll(FUELS);
                words.addAll(PICKS);
                words.addAll(FILL.subList(0, n));
                java.util.Collections.rotate(words, turn * 2 + 1);
                if (turn % 2 == 1) java.util.Collections.reverse(words);
                LinkedHashMap<String, Integer> bag = new LinkedHashMap<>();
                for (int i = 0; i < words.size(); i++) bag.put(words.get(i), words.get(i).equals(INGOT) ? 3 : 1 + i % 5);
                NeedChain.World w = world(bag);
                LinkedHashMap<String, Integer> start = ASK.bagLess(NeedChain.liveBag(w));
                LinkedHashMap<String, Integer> again = NeedChain.liveBag(ASK.pricedOn(w));
                if (!only(start, FUELS).equals(only(again, FUELS)) || !only(start, PICKS).equals(only(again, PICKS))) return w;
            }
        }
        return null;
    }

    @Test
    void anOwnResultWithAHeldPart_isNeverDroppedOnTheBagsOrder() throws InterruptedException {
        NeedChain.World w = reordered();
        org.junit.jupiter.api.Assumptions.assumeTrue(w != null, "no tried bag is re-ordered by a World made again in this JVM (Map.copyOf's order is per JVM)");
        // the worker's start bag and the check's are the same, in the same order
        PlannerSnapshot snap = PlannerSnapshot.take(w, List.of(), null, 1, 0);
        PlannerWorker.Job job = new PlannerWorker.Job("own o1", 1, null, Set.of(), snap, ASK);
        assertEquals(new ArrayList<>(ASK.bagLess(snap.bag()).entrySet()), new ArrayList<>(PlannerPort.startBag(job, w).entrySet()),
                "the check compares the bag the worker started from, in its order");
        // the worker's result, settled through its fetch rounds, reads an ordered scan and holds on the live World
        PlannerWorker.Done d = PlannerWorker.resolve(job);
        for (int round = 2; round < 2 + PlannerPort.ROUNDS; round++) {
            assertNull(d.error(), String.valueOf(d.error()));
            List<String> ch = PlannerPort.check(d, w, new HashMap<>());
            assertTrue(ch.stream().noneMatch(c -> c.startsWith("bag")), "an own result is never dropped on the bag's order: " + ch);
            if (ch.isEmpty()) break;
            d = PlannerWorker.resolve(new PlannerWorker.Job("own o1", round, null, Set.of(), PlannerSnapshot.take(w, d.read().facts().keySet(), null, round, 0), ASK));
        }
        assertTrue(PlannerPort.check(d, w, new HashMap<>()).isEmpty(), "the own result holds: " + PlannerPort.check(d, w, new HashMap<>()));
        LinkedHashMap<String, Integer> start = PlannerPort.startBag(job, w);
        assertNotNull(d.read().bagChanged(swapped(swapped(start, FUELS), PICKS)),
                "the resolve read the bag's fuels or pickaxes in order (else this test proves nothing): " + d.legs().chain().why());
        // the tick's resolve reads the same bag in the same order: one answer whoever resolves
        NeedChain.Chain tick = ASK.resolve(w);
        NeedChain.Read tickRead = NeedChain.lastRead();
        assertNull(tickRead.bagChanged(ASK.bagLess(snap.bag())), "the tick read the worker's start bag, in its order");
        assertNull(d.read().bagChanged(PlannerPort.startBag(job, w)));
        assertEquals(tick.why(), d.legs().chain().why(), "one answer whoever resolves");
        assertTrue(io.github.mojolowjo.entropybot.aa.Deep.equal(tick.steps(), d.legs().chain().steps()), tick.steps() + " vs " + d.legs().chain().steps());
        // through the real port: applied at once, no drop, no take
        List<String> log = new ArrayList<>();
        PlannerWorker worker = new PlannerWorker();
        PlannerPort port = new PlannerPort(worker, () -> 0, log::add);
        NeedChain.Chain c = port.own("own o1", ASK, w);
        long until = System.nanoTime() + 30_000_000_000L;
        while (NeedChain.thinking(c)) {
            if (System.nanoTime() > until) fail("the planner did not answer within 30 s: " + c.why() + " " + log);
            long seen = worker.landed();
            while (worker.landed() == seen && worker.busy("own o1") && System.nanoTime() < until) Thread.sleep(2);
            c = port.own("own o1", ASK, w);
        }
        assertTrue(log.stream().noneMatch(l -> l.contains("re-planned while thinking: bag")), "never dropped as bag changed: " + log);
        assertTrue(log.stream().noneMatch(l -> l.contains("this answer is taken")), "no answer taken against the facts: " + log);
        assertFalse(NeedChain.thinking(port.own("own o1", ASK, w)), "asked again on the same facts: the applied result is kept");
    }
}
