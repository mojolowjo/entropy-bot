package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.Deep;
import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.PathPrice;
import io.github.mojolowjo.entropybot.aa.PlanNodes;
import io.github.mojolowjo.entropybot.aa.Playbook;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.GenWhere;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.LootBook;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 D6 (design "Scenarios", the worker): a snapshot taken, a fact changed before the result is applied - the result
 * is dropped and the request goes again; no chain is ever applied against facts other than the ones it was resolved
 * from (each applied trip equals the old resolve on the facts of the moment it is applied); a fact the result did not read
 * changing drops nothing; a newer request supersedes a queued one. The real worker thread, the real snapshot.
 */
class D6WorkerTest {
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

    static final String IRON_ORE = "minecraft:iron_ore", COAL_ORE = "minecraft:coal_ore", STONE = "minecraft:stone";

    /** The tests' planner data (as Z77PlaybookTest: only coal and charcoal burn; the pickaxes by tier). */
    static void data() {
        TestData d = TestData.planner();
        for (String m : List.of("wooden", "stone", "iron", "diamond")) d.tag(m + "_pickaxe", "minecraft:pickaxes");
        d.fuels.keySet().removeIf(k -> !k.equals(TestData.id("coal")) && !k.equals(TestData.id("charcoal")));
        Object[][] picks = {{"wooden", 2.0, 0}, {"stone", 4.0, 1}, {"iron", 6.0, 2}, {"diamond", 8.0, 3}};
        for (Object[] p : picks) d.tool(p[0] + "_pickaxe", ItemWorth.Role.PICKAXE, (Double) p[1], (Integer) p[2], 0);
        d.install();
    }

    static LootBook.Source src(String block, String item) { return new LootBook.Source(LootBook.Kind.BLOCK, block, item, 1, 1, false, null); }

    /** One run's live facts: the bag, the trees seen, the walks (mutable: the test changes them between asks). */
    static final class Live {
        final Map<String, Integer> bag = new ConcurrentHashMap<>(Map.of("minecraft:stone_pickaxe", 1, "minecraft:coal", 6, "minecraft:dirt", 3));
        final List<Spot> trees = new java.util.concurrent.CopyOnWriteArrayList<>(List.of(new Spot(12, 64, 0, 12, "minecraft:oak_log", 5), new Spot(25, 64, 0, 25, "minecraft:oak_log", 6)));
        final Map<String, PathPrice.Answer> walks = new ConcurrentHashMap<>();
        final LootBook book = new LootBook(List.of(src(IRON_ORE, "minecraft:raw_iron"), src(COAL_ORE, "minecraft:coal"), src(STONE, "minecraft:cobblestone"),
                src("minecraft:oak_log", "minecraft:oak_log")), "test");
        final List<String> ranked = new java.util.concurrent.CopyOnWriteArrayList<>();

        PathPrice paths() {
            return new PathPrice() {
                @Override public Answer price(int x, int y, int z, double dist) {
                    return walks.getOrDefault(x + "," + y + "," + z, Answer.priced(new Walk(y < 60 ? (64 - y) * 0.6 : dist / Unit.WALK_BPS)));
                }
                @Override public void rankedOut(int x, int y, int z) { ranked.add(x + " " + y + " " + z); }
            };
        }

        World world() {
            Map<String, Integer> b = new LinkedHashMap<>(bag);
            List<Spot> t = List.copyOf(trees);
            return new World(b, false, false, word -> word.contains("log") ? t : List.of(), x -> List.of(), book, null, 0, paths())
                    .withStrip(D6WorkerTest::strip)
                    .withWhere(new NeedChain.WhereFacts(D6WorkerTest::where, () -> new NeedChain.Surface(0, 0, 64, 0, 0), () -> new int[]{0, 64, 0},
                            () -> new GenWhere.Base(STONE, Integer.MIN_VALUE, null, "test")));
        }
    }

    static NeedChain.StripBand strip(String word) {
        for (String p : word.split("\\|")) {
            if (p.equals("minecraft:raw_iron") || p.equals(IRON_ORE)) return new NeedChain.StripBand(List.of(IRON_ORE), 0, 31, 16, 256, 0, 0, 64, "east", STONE);
            if (p.equals("minecraft:coal") || p.equals("#minecraft:coals")) return new NeedChain.StripBand(List.of(COAL_ORE), 0, 31, 16, 256, 0, 0, 64, "east", STONE);
        }
        return null;
    }

    static GenWhere.Fact where(String word) {
        if (word.contains("log")) return new GenWhere.Fact(GenWhere.Kind.SURFACE, 0, 0, "minecraft:oak_log", "minecraft:trees_plains", null);
        for (String p : word.split("\\|"))
            if (p.equals("minecraft:raw_iron") || p.equals(IRON_ORE) || p.equals("minecraft:coal") || p.equals("#minecraft:coals") || p.equals(COAL_ORE))
                return new GenWhere.Fact(GenWhere.Kind.BAND, 0, 31, IRON_ORE, "minecraft:ore_test", null);
        return GenWhere.unknown("no world-gen feature places it (test)");
    }

    static final List<Playbook.Target> KIT = List.of(new Playbook.Target("minecraft:torch", 4), new Playbook.Target("minecraft:iron_pickaxe", 1),
            new Playbook.Target("minecraft:iron_helmet", 1));

    /** Asks the port until it stops thinking (the worker answered and the result was applied), at most {@code seconds}. */
    static NeedChain.Legs untilApplied(PlannerPort port, PlannerWorker worker, Playbook pb, Live live, double seconds) throws InterruptedException {
        long until = System.nanoTime() + (long) (seconds * 1e9);
        while (System.nanoTime() < until) {
            long seen = worker.landed();
            NeedChain.Legs l = port.legs("b1", pb, Set.of(), live.world());
            if (!NeedChain.thinking(l.chain())) return l;
            while (worker.landed() == seen && System.nanoTime() < until) Thread.sleep(2);
        }
        fail("the planner did not answer within " + seconds + " s");
        return null;
    }

    /** Waits for a worker landing after {@code seen}; fails after 30 s instead of hanging the build. */
    static void awaitLanding(PlannerWorker worker, long seen) throws InterruptedException {
        long until = System.nanoTime() + 30_000_000_000L;
        while (worker.landed() == seen) {
            if (System.nanoTime() > until) fail("no worker landing within 30 s (nothing was submitted?)");
            Thread.sleep(1);
        }
    }

    @Test
    void aFactChangedWhileThinking_theResultIsDropped_andTheRequestGoesAgain() throws InterruptedException {
        data();
        Live live = new Live();
        List<String> log = new ArrayList<>();
        PlannerWorker worker = new PlannerWorker();
        PlannerPort port = new PlannerPort(worker, () -> 0, log::add);
        Playbook pb = Playbook.derive(KIT, Playbook.facts(live.world(), null));
        assertTrue(pb.ok(), pb.why());
        NeedChain.Legs first = port.legs("b1", pb, Set.of(), live.world());
        assertTrue(NeedChain.thinking(first.chain()), "the first ask thinks: " + first.chain().why());
        assertTrue(first.chain().why().contains("thinking: "), first.chain().why());
        // the snapshot of the first request had no facts (the first resolve): its result fetches them; then a real one
        NeedChain.Legs a = untilApplied(port, worker, pb, live, 30);
        assertTrue(Deep.equal(a, PlanNodes.old(() -> NeedChain.resolvePlaybookLegs(pb, live.world()))), "applied = the old resolve on the facts now");
        // a request whose result lands after a fact it read changed: dropped, said, asked again
        live.trees.remove(0);
        live.trees.add(0, new Spot(12, 64, 0, 12, "minecraft:oak_log", 5));                 // the same tree, so the trip is the same request
        live.walks.put("12,64,0", PathPrice.Answer.priced(new PathPrice.Walk(70.0)));         // a read fact moved: the kept result fails its check
        long seen = worker.landed();
        port.legs("b1", pb, Set.of(), live.world());                                         // a new request (re-price)
        awaitLanding(worker, seen);
        live.walks.put("12,64,0", PathPrice.Answer.priced(new PathPrice.Walk(90.0)));         // the near tree's walk landed dearer meanwhile
        assertTrue(port.ready("b1"), "its result is ready for the kept stand");
        NeedChain.Legs again = port.legs("b1", pb, Set.of(), live.world());
        assertTrue(NeedChain.thinking(again.chain()), "dropped: thinking again: " + again.chain().why());
        assertTrue(log.stream().anyMatch(l -> l.contains("re-planned while thinking: price")), "said: " + log);
        assertTrue(again.chain().why().matches(".*thinking: \\d+ nodes dirty.*"), "the stand says the nodes dirty: " + again.chain().why());
        NeedChain.Legs b = untilApplied(port, worker, pb, live, 30);
        assertTrue(Deep.equal(b, PlanNodes.old(() -> NeedChain.resolvePlaybookLegs(pb, live.world()))), "applied on the new facts");
        assertTrue(port.probe().startsWith("planner: nodes "), port.probe());
    }

    @Test
    void neverAppliedAgainstOtherFacts_everyAppliedTripIsTheOldResolveOfItsMoment() throws InterruptedException {
        data();
        Live live = new Live();
        PlannerWorker worker = new PlannerWorker();
        PlannerPort port = new PlannerPort(worker, () -> 0, l -> {});
        Playbook pb = Playbook.derive(KIT, Playbook.facts(live.world(), null));
        Random r = new Random(7);
        int applied = 0;
        for (int i = 0; i < 24; i++) {
            // a fact the trip reads changes, sometimes while a request is out
            switch (r.nextInt(4)) {
                case 0 -> live.walks.put((r.nextBoolean() ? 12 : 25) + ",64,0", PathPrice.Answer.priced(new PathPrice.Walk(1 + r.nextInt(60))));
                case 1 -> live.walks.put("12,64,0", r.nextBoolean() ? PathPrice.Answer.computing(null) : PathPrice.Answer.unreachable("no path"));
                case 2 -> live.bag.put("minecraft:coal", 2 + r.nextInt(8));
                default -> live.walks.clear();
            }
            long seen = worker.landed();
            NeedChain.Legs l = port.legs("b1", pb, Set.of(), live.world());
            if (r.nextBoolean() && NeedChain.thinking(l.chain())) {                          // a kept result submits nothing (run DP)
                awaitLanding(worker, seen);
                if (r.nextBoolean()) live.walks.put("25,64,0", PathPrice.Answer.priced(new PathPrice.Walk(1 + r.nextInt(40))));   // changed after the snapshot
            }
            if (NeedChain.thinking(l.chain())) l = untilApplied(port, worker, pb, live, 30);
            World now = live.world();
            assertTrue(Deep.equal(l, PlanNodes.old(() -> NeedChain.resolvePlaybookLegs(pb, now))), "round " + i + ": the applied trip is the old resolve on the facts now");
            applied++;
        }
        assertEquals(24, applied);
    }

    @Test
    void aFactTheResultDidNotReadChanging_dropsNothing() throws InterruptedException {
        data();
        Live live = new Live();
        List<String> log = new ArrayList<>();
        PlannerWorker worker = new PlannerWorker();
        PlannerPort port = new PlannerPort(worker, () -> 0, log::add);
        Playbook pb = Playbook.derive(KIT, Playbook.facts(live.world(), null));
        untilApplied(port, worker, pb, live, 30);
        long seen = worker.landed();
        // run DP: the applied result is kept and checked live, so asking again submits nothing (no landing to wait on)
        assertFalse(NeedChain.thinking(port.legs("b1", pb, Set.of(), live.world()).chain()));
        assertEquals(seen, worker.landed());
        // a walk no way of this trip asks (the trip's descent copies the bag whole for its carry list, so a bag count is
        // always one of its facts: D6 report)
        live.walks.put("99,64,99", PathPrice.Answer.unreachable("no path"));
        NeedChain.Legs l = port.legs("b1", pb, Set.of(), live.world());
        assertFalse(NeedChain.thinking(l.chain()), "applied at once: " + l.chain().why());
        assertTrue(log.stream().noneMatch(x -> x.contains("re-planned while thinking")), log.toString());
    }

    // ---- review round ----

    @Test
    void anErrorOnTheWorker_isPosted_theThreadGoesOn_theTripFailsLoud() throws InterruptedException {
        data();
        Live live = new Live();
        List<String> log = new ArrayList<>();
        boolean[] overflow = {true};
        PlannerWorker worker = new PlannerWorker(j -> {
            if (overflow[0]) { overflow[0] = false; throw new StackOverflowError("test: the resolve ran out of stack"); }
            return PlannerWorker.resolve(j);
        });
        PlannerPort port = new PlannerPort(worker, () -> 0, log::add);
        Playbook pb = Playbook.derive(KIT, Playbook.facts(live.world(), null));
        port.legs("b1", pb, Set.of(), live.world());
        while (worker.landed() < 1) Thread.sleep(1);
        assertFalse(worker.busy("b1"), "the request is not left running");
        NeedChain.Legs l = port.legs("b1", pb, Set.of(), live.world());
        assertFalse(l.chain().ok(), "the trip fails with the reason: " + l.chain().why());
        assertTrue(l.chain().why().contains("StackOverflowError"), l.chain().why());
        assertTrue(log.stream().anyMatch(x -> x.contains("could not resolve the trip off the render thread")), "said: " + log);
        NeedChain.Legs again = untilApplied(port, worker, pb, live, 30);     // the same thread resolves the next request
        assertTrue(Deep.equal(again, PlanNodes.old(() -> NeedChain.resolvePlaybookLegs(pb, live.world()))));
    }

    @Test
    void gameDataChangedWhileThinking_theResultIsDropped() throws InterruptedException {
        data();
        Live live = new Live();
        List<String> log = new ArrayList<>();
        boolean[] tune = {false};
        PlannerWorker worker = new PlannerWorker(j -> {
            PlannerWorker.Done d = PlannerWorker.resolve(j);
            if (tune[0]) { tune[0] = false; Tuning.set("walk.farXZ", 129); }    // aa tune while it thought
            return d;
        });
        PlannerPort port = new PlannerPort(worker, () -> 0, log::add);
        Playbook pb = Playbook.derive(KIT, Playbook.facts(live.world(), null));
        untilApplied(port, worker, pb, live, 30);
        tune[0] = true;
        live.walks.put("12,64,0", PathPrice.Answer.priced(new PathPrice.Walk(70.0)));         // a read fact moved: a new request
        long seen = worker.landed();
        port.legs("b1", pb, Set.of(), live.world());
        awaitLanding(worker, seen);
        NeedChain.Legs l = port.legs("b1", pb, Set.of(), live.world());
        assertTrue(NeedChain.thinking(l.chain()), "dropped: " + l.chain().why());
        assertTrue(log.stream().anyMatch(x -> x.contains("re-planned while thinking: game data")), "said: " + log);
        NeedChain.Legs b = untilApplied(port, worker, pb, live, 30);
        assertTrue(Deep.equal(b, PlanNodes.old(() -> NeedChain.resolvePlaybookLegs(pb, live.world()))), "applied in the new data");
    }

    @Test
    void answersKeepLanding_pastTheCapTheTripStandsWithTheReason_neverOnAStalePrice_appliedOnceTheFactsHold() throws InterruptedException {
        data();
        Live live = new Live();
        List<String> log = new ArrayList<>();
        PlannerWorker worker = new PlannerWorker();
        PlannerPort port = new PlannerPort(worker, () -> 0, log::add);
        Playbook pb = Playbook.derive(KIT, Playbook.facts(live.world(), null));
        Tuning.set("planner.dropsBeforeTake", 1000);     // job-freeze: the take guard off - this test is the stand under it
        untilApplied(port, worker, pb, live, 30);
        // every result lands after the near tree's walk answered again (another price): each one is stale on arrival
        live.walks.put("12,64,0", PathPrice.Answer.priced(new PathPrice.Walk(9)));            // a read fact moved: the kept result fails its check
        NeedChain.Legs l = null;
        int rounds = PlannerPort.ROUNDS + 4;
        for (int i = 0; i < rounds; i++) {
            long seen = worker.landed();
            l = port.legs("b1", pb, Set.of(), live.world());
            assertTrue(NeedChain.thinking(l.chain()), "round " + i + ": never applied while every result is stale: " + l.chain().why());
            awaitLanding(worker, seen);
            live.walks.put("12,64,0", PathPrice.Answer.priced(new PathPrice.Walk(10 + i)));
        }
        // run DR round (manager 2026-10-27): past the cap no resolve on the client tick - the stand says why, said once
        l = port.legs("b1", pb, Set.of(), live.world());
        assertTrue(NeedChain.thinking(l.chain()), l.chain().why());
        assertTrue(l.chain().why().contains("the facts keep changing while it thinks"), "the stand's reason: " + l.chain().why());
        assertEquals(1, log.stream().filter(x -> x.contains("results in a row dropped") && x.contains("the trip stands until a result holds")).count(), "said loud, once a streak: " + log);
        assertTrue(log.stream().noneMatch(x -> x.contains("resolved on the client tick")), log.toString());
        assertTrue(port.probe().contains("stood past the cap 1"), port.probe());
        // the facts hold again: the next result is applied, the facts of now
        NeedChain.Legs a = untilApplied(port, worker, pb, live, 30);
        assertTrue(Deep.equal(a, PlanNodes.old(() -> NeedChain.resolvePlaybookLegs(pb, live.world()))), "the facts of now, never a stale price");
    }

    /** Job-freeze (run JAP: 293 drops, the order stood until it parked): the same reason dropsBeforeTake times in a row - the next result is taken, said once. */
    @Test
    void theSameReasonNTimesInARow_theNextResultIsTaken_saidOnce() throws InterruptedException {
        data();
        Live live = new Live();
        List<String> log = new ArrayList<>();
        PlannerWorker worker = new PlannerWorker();
        PlannerPort port = new PlannerPort(worker, () -> 0, log::add);
        Playbook pb = Playbook.derive(KIT, Playbook.facts(live.world(), null));
        untilApplied(port, worker, pb, live, 30);
        live.walks.put("12,64,0", PathPrice.Answer.priced(new PathPrice.Walk(9)));
        int n = PlannerPort.dropsBeforeTake();
        assertEquals(5, n, "the Tuning default");
        NeedChain.Legs l = null;
        for (int i = 0; i <= n + 1; i++) {
            long seen = worker.landed();
            l = port.legs("b1", pb, Set.of(), live.world());
            if (!NeedChain.thinking(l.chain())) break;
            awaitLanding(worker, seen);
            live.walks.put("12,64,0", PathPrice.Answer.priced(new PathPrice.Walk(10 + i)));
        }
        assertFalse(NeedChain.thinking(l.chain()), "taken after " + n + " drops: " + l.chain().why());
        assertEquals(1, log.stream().filter(x -> x.contains("the facts kept changing " + n + " times") && x.contains("this answer is taken (rule 7)")).count(), log.toString());
        assertTrue(log.stream().noneMatch(x -> x.contains("the trip stands until a result holds")), log.toString());
    }

    /** Review (b): a stand past the cap that never settles says its reason again every RESAY_S (nothing else bounds it). */
    @Test
    void pastTheCap_theReasonIsSaidAgainEvery30s_whileTheFactsKeepChanging() throws InterruptedException {
        data();
        Live live = new Live();
        List<String> log = new ArrayList<>();
        long[] tick = {0};
        PlannerWorker worker = new PlannerWorker();
        PlannerPort port = new PlannerPort(worker, () -> tick[0], log::add);
        Tuning.set("planner.dropsBeforeTake", 1000);     // job-freeze: the take guard off - this test is the stand under it
        Playbook pb = Playbook.derive(KIT, Playbook.facts(live.world(), null));
        untilApplied(port, worker, pb, live, 30);
        java.util.function.Predicate<String> said = x -> x.contains("results in a row dropped") && x.contains("the trip stands until a result holds");
        for (int i = 0; i < PlannerPort.ROUNDS + 6; i++) {
            if (i == PlannerPort.ROUNDS + 3) tick[0] = (long) PlannerPort.RESAY_S * 20;     // 30 s of ticks later, still not settled
            live.walks.put("12,64,0", PathPrice.Answer.priced(new PathPrice.Walk(10 + i)));
            long seen = worker.landed();
            assertTrue(NeedChain.thinking(port.legs("b1", pb, Set.of(), live.world()).chain()));
            awaitLanding(worker, seen);
        }
        live.walks.put("12,64,0", PathPrice.Answer.priced(new PathPrice.Walk(99)));
        port.legs("b1", pb, Set.of(), live.world());
        assertEquals(2, log.stream().filter(said).count(), "once at the cap, once 30 s later: " + log);
        assertTrue(port.probe().contains("stood past the cap 1"), "one streak: " + port.probe());
    }

    // ---- run DQ diagnosis ----

    /**
     * Run DR round, option C (manager 2026-10-27: "nodes keep their sums"; run DQ: one walk re-priced re-resolved 78 of 79
     * nodes): the near tree's walk priced anew - its tree still the pick - re-resolves exactly the nodes that read that walk
     * and their ancestors (the paths to the root, from the kept tree), every later node reused with its own sums; the trip
     * is the old resolve's on the new facts. Printed ("D6 sums: ...").
     */
    @Test
    void optionC_oneWalkRePriced_reResolvesOnlyItsPathsToTheRoot() {
        data();
        PlanNodes.forgetMine();
        PlanNodes.on(true);
        Live live = new Live();
        Playbook pb = Playbook.derive(KIT, Playbook.facts(live.world(), null));
        NeedChain.Legs before = NeedChain.resolvePlaybookLegs(pb, live.world());
        NeedChain.resolvePlaybookLegs(pb, live.world());
        PlanNodes.Stats same = PlanNodes.last();
        java.util.function.Predicate<PlanNodes.Fact> nearWalk = f -> f.field().equals("price") && f.key() instanceof NeedChain.PathKey k && k.x() == 12 && k.z() == 0;
        int paths = PlanNodes.touchedBy(nearWalk);
        double was = 12 / Unit.WALK_BPS;
        live.walks.put("12,64,0", PathPrice.Answer.priced(new PathPrice.Walk(was + 0.5)));     // dearer, still the nearest tree's win
        NeedChain.Legs l = NeedChain.resolvePlaybookLegs(pb, live.world());
        PlanNodes.Stats moved = PlanNodes.last();
        System.out.printf("D6 sums: nothing changed: %s; the near tree's walk priced anew: %s (its paths to the root: %d nodes)%n", same.text(), moved.text(), paths);
        assertEquals(0, same.dirty(), same.text());
        assertTrue(paths > 0 && paths < moved.nodes(), "the walk is read below the root: " + paths + " of " + moved.nodes());
        assertEquals(paths, moved.resolved(), "only the walk's nodes and their ancestors resolved again: " + moved.text());
        assertEquals(before.chain().steps().size(), l.chain().steps().size(), "the same pick");
        assertTrue(Deep.equal(l, PlanNodes.old(() -> NeedChain.resolvePlaybookLegs(pb, live.world()))), "the old resolve's trip on the new facts");
        // verify mode: every node reused with its own sums is resolved again and must be equal, exactly
        PlanNodes.verify(true);
        try {
            live.walks.put("12,64,0", PathPrice.Answer.priced(new PathPrice.Walk(was + 0.25)));
            NeedChain.resolvePlaybookLegs(pb, live.world());
        } finally {
            PlanNodes.verify(false);
        }
    }

    // ---- run DP ----

    @Test
    void runDP_aReAskWithNothingChanged_isTheAppliedResult_noWorkerRound_noStand() throws InterruptedException {
        data();
        Live live = new Live();
        List<String> log = new ArrayList<>();
        PlannerWorker worker = new PlannerWorker();
        PlannerPort port = new PlannerPort(worker, () -> 0, log::add);
        Playbook pb = Playbook.derive(KIT, Playbook.facts(live.world(), null));
        NeedChain.Legs a = untilApplied(port, worker, pb, live, 30);
        long landed = worker.landed();
        // the re-price a worker result (or any landing) triggers: the same trip asked again, no fact changed
        for (int i = 0; i < 5; i++) {
            NeedChain.Legs b = port.legs("b1", pb, Set.of(), live.world());
            assertFalse(NeedChain.thinking(b.chain()), "no stand: " + b.chain().why());
            assertTrue(Deep.equal(a, b), "the applied result");
            assertNotSame(a.chain().steps().get(0), b.chain().steps().get(0), "fresh step objects, as a resolve gives");
        }
        assertEquals(landed, worker.landed(), "no worker round: nothing submitted, so nothing lands to re-price on (the run DP loop)");
        assertTrue(port.probe().contains("kept 5"), port.probe());
        // a fact it read changes: the next ask goes to the worker again
        live.walks.put("12,64,0", PathPrice.Answer.priced(new PathPrice.Walk(77.0)));
        assertTrue(NeedChain.thinking(port.legs("b1", pb, Set.of(), live.world()).chain()), "a changed fact: the worker again");
        NeedChain.Legs c = untilApplied(port, worker, pb, live, 30);
        assertTrue(Deep.equal(c, PlanNodes.old(() -> NeedChain.resolvePlaybookLegs(pb, live.world()))));
    }

    @Test
    void runDP_theBatchsHoldsInAnotherAskersOrder_dropNothing() throws InterruptedException {
        data();
        Live live = new Live();
        List<String> log = new ArrayList<>();
        PlannerWorker worker = new PlannerWorker();
        PlannerPort port = new PlannerPort(worker, () -> 0, log::add);
        Map<String, String> mine = new LinkedHashMap<>(), theirs = new LinkedHashMap<>();
        mine.put("station at 90 64 90", "held by order A");
        mine.put("station at 91 64 91", "held by order B");
        theirs.put("station at 91 64 91", "held by order B");
        theirs.put("station at 90 64 90", "held by order A");
        Playbook pb = Playbook.derive(KIT, Playbook.facts(live.world(), null));
        // order A asks (its holds first) until applied: the first result fetches the facts, the next one is snapshotted
        long until = System.nanoTime() + 30_000_000_000L;
        boolean applied = false;
        while (!applied && System.nanoTime() < until) {
            long seen = worker.landed();
            applied = !NeedChain.thinking(port.legs("b1", pb, Set.of(), live.world().withoutSources(mine)).chain());
            if (!applied) awaitLanding(worker, seen);
        }
        assertTrue(applied, "applied within 30 s");
        // order B re-prices the same trip: the kept result's check reads the holds in B's order
        assertFalse(NeedChain.thinking(port.legs("b1", pb, Set.of(), live.world().withoutSources(theirs)).chain()), "kept for B");
        // a result snapshotted in A's order lands and B checks it
        live.walks.put("12,64,0", PathPrice.Answer.priced(new PathPrice.Walk(70.0)));
        long seen = worker.landed();
        assertTrue(NeedChain.thinking(port.legs("b1", pb, Set.of(), live.world().withoutSources(mine)).chain()), "a read fact moved");
        awaitLanding(worker, seen);
        assertFalse(NeedChain.thinking(port.legs("b1", pb, Set.of(), live.world().withoutSources(theirs)).chain()), "applied for B");
        assertTrue(log.stream().noneMatch(x -> x.contains("re-planned while thinking: held")), "the same holds in another order are no change: " + log);
    }

    @Test
    void tuningIsReadOffThreadWhileTheClientTunes() throws InterruptedException {
        List<Throwable> bad = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread reader = new Thread(() -> {
            try {
                for (int i = 0; i < 200_000; i++) if (!(Tuning.get("walk.farXZ") > 0)) bad.add(new IllegalStateException("read " + Tuning.get("walk.farXZ")));
            } catch (RuntimeException e) {
                bad.add(e);
            }
        });
        reader.start();
        for (int i = 0; i < 2_000; i++) { Tuning.set("walk.farXZ", 100 + i % 50); if (i % 100 == 0) Tuning.reset(); }
        reader.join(30_000);
        assertTrue(bad.isEmpty(), "every read saw a whole value: " + bad);
    }

    @Test
    void aNewerRequestSupersedesAQueuedOne() throws InterruptedException {
        CountDownLatch hold = new CountDownLatch(1);
        List<Long> ran = new java.util.concurrent.CopyOnWriteArrayList<>();
        PlannerWorker worker = new PlannerWorker(j -> {
            ran.add(j.version());
            if (j.version() == 1) {
                try {
                    assertTrue(hold.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return new PlannerWorker.Done(j, null, NeedChain.Read.NONE, 0, null);
        });
        worker.submit(new PlannerWorker.Job("b1", 1, null, Set.of(), null));
        while (ran.isEmpty()) Thread.sleep(1);                                                // 1 runs (held)
        worker.submit(new PlannerWorker.Job("b1", 2, null, Set.of(), null));                  // queued
        worker.submit(new PlannerWorker.Job("b1", 3, null, Set.of(), null));                  // replaces 2
        assertTrue(worker.busy("b1"));
        hold.countDown();
        while (worker.landed() < 2) Thread.sleep(1);
        assertEquals(List.of(1L, 3L), ran, "2 was superseded while queued, never resolved");
        assertEquals(3L, worker.poll("b1").job().version(), "the newest result is the one kept");
        assertFalse(worker.busy("b1"));
    }
}
