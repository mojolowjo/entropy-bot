package io.github.mojolowjo.entropybot.aa;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.StripBand;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.GenWhere;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.LootBook;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 D6 (design "Replay file"): the gate runs' recorded facts ({@code tools/run_facts.ps1}: the orders, the feet,
 * the priced walks, the sources seen, the stations, the holds, the bag changes the log names) fed in order; at every plan
 * point the open orders' trip (Playbook.derive + resolvePlaybookLegs) and the first open order's own chain are resolved by
 * the old resolve (PlanNodes.old) and by the incremental one (one node cache across the whole run, verify mode on: every
 * reuse resolved again and compared), and must be identical (deep-equal: steps, prices, why, dropped, uses, leg starts).
 * The recipes are the tests' TestData (the log has none) and the bag is what the events imply, so the chains are not the
 * runs' own; what is proved is old == incremental on the runs' sequences of fact changes. The counts are printed
 * ("D6 replay runDJ-z78h: ...").
 * <p>The baseline is the exact arithmetic (manager 2026-10-27): the old resolve here sums seconds on the same 2^-20 s grid
 * (NeedChain.grid), so true ties go by choose's declared order (work, tier, way index), not by float noise. Against the
 * pre-grid sums, run DJ's picks differ at such ties (40 of 263 trips; DK, DL, DQ none); that is accepted, not a regression.
 */
class D6ReplayTest {
    @AfterEach
    void off() {
        PlanNodes.verify(false);
        PlanNodes.defaultOn();
        PlanNodes.forgetMine();
        TestData.clear();
        Tuning.reset();
        BatchWay.clear();
    }

    @Test
    void runDJ_theOldAndTheIncrementalResolveAgreeAtEveryPlanPoint() { replay("runDJ-z78h"); }

    @Test
    void runDK_theOldAndTheIncrementalResolveAgreeAtEveryPlanPoint() { replay("runDK-z78h"); }

    @Test
    void runDL_theOldAndTheIncrementalResolveAgreeAtEveryPlanPoint() { replay("runDL-z78h"); }

    @Test
    void runDQ_theOldAndTheIncrementalResolveAgreeAtEveryPlanPoint() { replay("runDQ-z78"); }

    /**
     * Run DQ diagnosis (b): the facts one trip resolve reads, by field - the live check asks each once on the client tick.
     * Printed ("D6 facts runDQ-z78: ..."), the largest trip's census; nothing tuned.
     */
    @Test
    void runDQ_theFactsATripReads_byField() {
        Z77PlaybookTest.data();
        PlanNodes.forgetMine();
        PlanNodes.on(true);
        LootBook book = book();
        Facts f = new Facts();
        int trips = 0, most = 0;
        Map<String, Integer> census = Map.of();
        for (JsonObject e : events("runDQ-z78")) {
            f.apply(e);
            if (!"plan".equals(e.get("k").getAsString()) || f.orders.isEmpty()) continue;
            World w = f.world(book);
            List<Playbook.Target> targets = new ArrayList<>();
            for (var o : f.orders.entrySet()) targets.add(new Playbook.Target(o.getKey(), o.getValue()));
            Playbook pb = Playbook.derive(targets, Playbook.facts(w, null));
            if (!pb.ok()) continue;
            PlanNodes.forgetMine();                      // a full resolve: every fact the trip reads
            NeedChain.resolvePlaybookLegs(pb, w);
            trips++;
            var facts = NeedChain.lastRead().facts();
            if (facts.size() <= most) continue;
            most = facts.size();
            Map<String, Integer> c = new java.util.TreeMap<>();
            for (PlanNodes.Fact k : facts.keySet()) c.merge(k.field(), 1, Integer::sum);
            census = c;
        }
        assertTrue(trips > 50, "trips " + trips);
        System.out.printf("D6 facts runDQ-z78: %d trips; the largest reads %d facts: %s%n", trips, most, census);
    }

    /**
     * The worker end to end: at every plan point of run DL the trip is asked of the planner port (the snapshot copied on
     * "the client tick", resolved on the worker thread, checked against the facts then, applied or dropped and asked again)
     * until it is applied; each applied trip must equal the old resolve on the same facts.
     */
    @Test
    void runDL_throughTheWorker_everyAppliedTripIsTheOldResolveOnItsFacts() throws InterruptedException {
        Z77PlaybookTest.data();
        PlanNodes.forgetMine();
        PlanNodes.on(true);
        LootBook book = book();
        Facts f = new Facts();
        var worker = new io.github.mojolowjo.entropybot.commands.PlannerWorker();
        List<String> log = new ArrayList<>();
        var port = new io.github.mojolowjo.entropybot.commands.PlannerPort(worker, () -> 0, log::add);
        int applied = 0, asks = 0;
        for (JsonObject e : events("runDL-z78h")) {
            f.apply(e);
            if (!"plan".equals(e.get("k").getAsString()) || f.orders.isEmpty()) continue;
            List<Playbook.Target> targets = new ArrayList<>();
            for (var o : f.orders.entrySet()) targets.add(new Playbook.Target(o.getKey(), o.getValue()));
            Playbook pb = Playbook.derive(targets, Playbook.facts(f.world(book), null));
            if (!pb.ok()) continue;
            NeedChain.Legs l = null;
            long until = System.nanoTime() + 30_000_000_000L;
            while (System.nanoTime() < until) {
                asks++;
                long seen = worker.landed();                 // read before the ask: a fast answer is not missed (the wait ran out at 30 s)
                l = port.legs("b1", pb, java.util.Set.of(), f.world(book));
                if (!NeedChain.thinking(l.chain())) break;
                while (worker.landed() == seen && System.nanoTime() < until) Thread.sleep(1);
            }
            assertNotNull(l);
            assertFalse(NeedChain.thinking(l.chain()), "the planner answered within 30 s at " + e.get("t").getAsString() + ": " + l.chain().why() + " | "
                    + port.probe() + " | log " + log.subList(Math.max(0, log.size() - 6), log.size()));
            World w = f.world(book);
            NeedChain.Legs old = PlanNodes.old(() -> NeedChain.resolvePlaybookLegs(pb, w));
            assertTrue(Deep.equal(old, l), "runDL " + e.get("t").getAsString() + ": the applied trip differs: " + D6NodesTest.diff(old, l));
            applied++;
        }
        assertTrue(applied > 50, "applied " + applied);
        System.out.printf("D6 replay runDL-z78h through the worker: %d trips applied, %d asks, each the old resolve on its facts; %s%n", applied, asks, port.probe());
    }

    static final String STONE = "minecraft:stone";

    /** The run's facts so far. */
    static final class Facts {
        int[] feet = {0, 64, 0};
        final Map<String, JsonObject> paths = new HashMap<>();          // target "x,y,z" -> its last priced walk
        final List<int[]> trees = new ArrayList<>();
        final List<JsonObject> ores = new ArrayList<>();
        final Map<String, int[]> stations = new LinkedHashMap<>();      // crafting_table / furnace -> cell
        final Map<String, String> holds = new LinkedHashMap<>();
        final Map<String, Integer> orders = new LinkedHashMap<>();
        final Map<String, Integer> bag = new LinkedHashMap<>();

        void apply(JsonObject e) {
            switch (e.get("k").getAsString()) {
                case "order" -> {
                    for (var it : e.getAsJsonArray("items")) {
                        JsonArray a = it.getAsJsonArray();
                        orders.put(TestData.id(a.get(0).getAsString()), a.get(1).getAsInt());
                    }
                }
                case "got" -> orders.remove(TestData.id(e.get("item").getAsString()));
                case "feet" -> feet = new int[]{e.get("x").getAsInt(), e.get("y").getAsInt(), e.get("z").getAsInt()};
                case "path" -> paths.put(e.get("x").getAsInt() + "," + e.get("y").getAsInt() + "," + e.get("z").getAsInt(), e);
                case "seen" -> {
                    if ("tree".equals(e.get("what").getAsString())) trees.add(new int[]{e.get("x").getAsInt(), e.get("y").getAsInt(), e.get("z").getAsInt(), e.get("n").getAsInt()});
                    else ores.add(e);
                }
                case "station" -> stations.put(e.get("kind").getAsString(), new int[]{e.get("x").getAsInt(), e.get("y").getAsInt(), e.get("z").getAsInt()});
                case "hold" -> holds.put(e.get("key").getAsString(), e.get("why").getAsString());
                case "bag" -> bag.merge(e.get("item").getAsString(), e.get("n").getAsInt(), (a, b) -> Math.max(0, a + b));
                default -> { }
            }
        }

        double dist(int x, int y, int z) { return Math.sqrt(Math.pow(x - feet[0], 2) + Math.pow(y - feet[1], 2) + Math.pow(z - feet[2], 2)); }

        /** A walk to x y z: the run's own answer when it was priced from about here, else by the straight metres (the tests' fake). */
        PathPrice.Answer walk(int x, int y, int z, double dist) {
            JsonObject p = paths.get(x + "," + y + "," + z);
            if (p != null && Math.abs(p.get("fx").getAsInt() - feet[0]) + Math.abs(p.get("fy").getAsInt() - feet[1]) + Math.abs(p.get("fz").getAsInt() - feet[2]) <= 3) {
                if (p.get("segs").getAsInt() <= 0) return PathPrice.Answer.unreachable("no path (the run's answer)");
                return PathPrice.Answer.priced(new PathPrice.Walk(TickPrice.walkS(p.get("ticks").getAsDouble())));
            }
            return PathPrice.Answer.priced(new PathPrice.Walk(FakePaths.walkS(Double.isNaN(dist) ? dist(x, y, z) : dist)));
        }

        World world(LootBook book) {
            Map<String, List<Spot>> seen = new HashMap<>();
            List<Spot> logs = new ArrayList<>();
            for (int[] t : trees) logs.add(new Spot(t[0], t[1], t[2], dist(t[0], t[1], t[2]), "minecraft:oak_log", t[3]));
            if (!logs.isEmpty()) seen.put("minecraft:oak_log", logs);
            List<Spot> iron = new ArrayList<>();
            for (JsonObject o : ores) {
                if (!"iron".equals(o.get("what").getAsString())) continue;
                int x = o.get("x").getAsInt(), y = o.get("y").getAsInt(), z = o.get("z").getAsInt();
                iron.add(new Spot(x, y, z, dist(x, y, z), o.get("block").getAsString(), 3, "minecraft:raw_iron"));
            }
            if (!iron.isEmpty()) seen.put("minecraft:raw_iron", iron);
            Map<String, Integer> b = new LinkedHashMap<>();
            for (var e : bag.entrySet()) if (e.getValue() > 0 && ItemWorth.facts(e.getKey()) != null) b.put(e.getKey(), e.getValue());
            int[] table = stations.get("crafting_table"), furnace = stations.get("furnace");
            int[] at = feet.clone();
            PathPrice paths = new PathPrice() {
                @Override public Answer price(int x, int y, int z, double dist) { return walk(x, y, z, dist); }
                @Override public Answer priceInto(int x, int y, int z, double dist) { return walk(x, y, z, dist); }
            };
            World w = new World(b, table != null, furnace != null, word -> ScenariosTest.lookup(seen, word), x -> List.of(), book, null, 0, paths,
                    kind -> {
                        int[] c = ItemWorth.CRAFT.equals(kind) ? table : ItemWorth.SMELT.equals(kind) ? furnace : null;
                        return c == null ? null : new Spot(c[0], c[1], c[2], dist(c[0], c[1], c[2]), ItemWorth.stationOf(kind));
                    })
                    .withStrip(word -> strip(word, at))
                    .withWhere(new NeedChain.WhereFacts(Z77PlaybookTest::where,
                            () -> at[1] >= 63 ? new NeedChain.Surface(0, at[0], at[1], at[2], 0) : new NeedChain.Surface(64 - at[1], at[0], 64, at[2], 64 - at[1]),
                            () -> at.clone(), () -> new GenWhere.Base(STONE, Integer.MIN_VALUE, null, "replay")));
            return w.withoutSources(holds);
        }
    }

    /** The tests' strip facts at the feet: the iron words strip iron ore, the coal words coal ore, at y 16 (Z77PlaybookTest's band). */
    static StripBand strip(String word, int[] feet) {
        for (String p : word.split("\\|")) {
            if (p.equals("minecraft:raw_iron") || p.equals("minecraft:iron_ore"))
                return new StripBand(List.of("minecraft:iron_ore"), 0, 31, 16, 256, feet[0], feet[2], feet[1], "east", STONE);
            if (p.equals("minecraft:coal") || p.equals("#minecraft:coals"))
                return new StripBand(List.of("minecraft:coal_ore"), 0, 31, 16, 256, feet[0], feet[2], feet[1], "east", STONE);
        }
        return null;
    }

    static LootBook book() {
        return new LootBook(List.of(Z77PlaybookTest.src("minecraft:iron_ore", "minecraft:raw_iron"), Z77PlaybookTest.src("minecraft:deepslate_iron_ore", "minecraft:raw_iron"),
                Z77PlaybookTest.src("minecraft:coal_ore", "minecraft:coal"), Z77PlaybookTest.src(STONE, "minecraft:cobblestone"),
                Z77PlaybookTest.src("minecraft:oak_log", "minecraft:oak_log")), "replay");
    }

    static List<JsonObject> events(String run) {
        List<JsonObject> out = new ArrayList<>();
        var in = D6ReplayTest.class.getResourceAsStream("/d6/" + run + "-facts.jsonl");
        assertNotNull(in, "the replay file of " + run);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) if (!line.isBlank()) out.add(JsonParser.parseString(line).getAsJsonObject());
        } catch (java.io.IOException e) {
            fail("the replay file of " + run + ": " + e);
        }
        return out;
    }

    private static void replay(String run) {
        Z77PlaybookTest.data();
        PlanNodes.forgetMine();
        PlanNodes.on(true);
        PlanNodes.verify(true);
        LootBook book = book();
        Facts f = new Facts();
        int points = 0, trips = 0, chains = 0, nodes = 0, resolved = 0, reused = 0;
        long oldNs = 0, newNs = 0;
        List<JsonObject> evs = events(run);
        for (JsonObject e : evs) {
            f.apply(e);
            if (!"plan".equals(e.get("k").getAsString()) || f.orders.isEmpty()) continue;
            points++;
            World w = f.world(book);
            List<Playbook.Target> targets = new ArrayList<>();
            for (var o : f.orders.entrySet()) targets.add(new Playbook.Target(o.getKey(), o.getValue()));
            Playbook pb = Playbook.derive(targets, Playbook.facts(w, null));
            String where = run + " plan point " + points + " (" + e.get("t").getAsString() + ", feet " + java.util.Arrays.toString(f.feet) + ", orders " + f.orders + ")";
            if (pb.ok()) {
                long t0 = System.nanoTime();
                NeedChain.Legs old = PlanNodes.old(() -> NeedChain.resolvePlaybookLegs(pb, w));
                long t1 = System.nanoTime();
                NeedChain.Legs now = NeedChain.resolvePlaybookLegs(pb, w);
                long t2 = System.nanoTime();
                oldNs += t1 - t0;
                newNs += t2 - t1;
                PlanNodes.Stats st = PlanNodes.last();
                nodes += st.nodes();
                resolved += st.resolved();
                reused += st.reused();
                assertTrue(Deep.equal(old, now), where + ": the trip differs: " + D6NodesTest.diff(old, now));
                trips++;
            }
            var first = f.orders.entrySet().iterator().next();
            Chain old = PlanNodes.old(() -> NeedChain.resolve(first.getKey(), first.getValue(), w));
            Chain now = NeedChain.resolve(first.getKey(), first.getValue(), w);
            assertTrue(Deep.equal(old, now), where + ": the chain of " + first.getKey() + " differs: " + D6NodesTest.diff(old, now));
            chains++;
        }
        assertTrue(points > 50, run + ": plan points " + points);
        assertTrue(trips > 0, run + ": trips " + trips);
        System.out.printf("D6 replay %s: %d events, %d plan points, %d trips and %d chains identical; trip nodes %d, re-resolved %d, reused %d (%.0f%% of the nodes"
                        + " not resolved again); old resolve %.0f ms, incremental (verify mode, each reuse resolved again) %.0f ms%n",
                run, evs.size(), points, trips, chains, nodes, resolved, reused, nodes == 0 ? 0 : 100.0 * (nodes - resolved) / nodes, oldNs / 1e6, newNs / 1e6);
    }
}
