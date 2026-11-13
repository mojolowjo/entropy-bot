package io.github.mojolowjo.entropybot.sim;

import io.github.mojolowjo.entropybot.aa.AaDesires;
import io.github.mojolowjo.entropybot.aa.FakePaths;
import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.Brain;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.LineDeposits;
import io.github.mojolowjo.entropybot.aa.brain.Only;
import io.github.mojolowjo.entropybot.aa.brain.OrderEnds;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Thing;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.JobPort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.moves.Step;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * sim step 2 (to-do/simulator-plan.md; owner 2026-11-13: "give in information, it does calculations, spits out an answer.
 * then we know and see where it failed"): an order line played on a fake world with no game. The REAL brain, planner,
 * order-end pass and deposit weighing (as {@code aa.OrderLineSim}); what is new here: the planner's facts are worked out
 * from the blocks (what is in sight of the feet), and each step of a run the brain starts is APPLIED to the world by
 * {@link SimSteps} - blocks dug, the bag filled, the feet moved, the clock advanced. Out: {@link #timeline} and
 * {@link #end}.
 *
 * <p>What it is not (each a later slice or a game run): the body's physics, Baritone's search (walks are straight lines at
 * the tests' walk price until step 3), the step runner's own re-plan between steps, mobs, chunk loading. A step kind
 * with no actor ends the game with "not simulated: step" (rule 7), never skipped.
 */
final class SimGame {
    static final int SIGHT = 32;                 // sim: how far the fake senses see on each axis (the game's is the loaded chunks)
    static final long IDLE_TICK_MS = 1000;       // sim: the clock's pace while no run is out
    static final long STALL_MS = 120_000;        // sim: this long with no run started and orders open ends the game "stalled"

    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final long t0 = clock.now();
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());
    final Brain brain = new Brain("brain", "sim");
    final LineDeposits deposits = new LineDeposits();

    final SimWorld world;
    /** The bot: the feet (x y z) and the bag by full item id. */
    final int[] feet;
    final Map<String, Integer> bag = new TreeMap<>();
    /** The main chest's cell ("x y z"); null: none set. */
    String mainAt;
    /** The cells of the blocks the bot placed ("x y z"): its own stations. */
    final Set<String> mine = new HashSet<>();
    /** What the main chest holds, by full item id. */
    final Map<String, Integer> chest = new TreeMap<>();
    private final List<Map<String, Integer>> due = new ArrayList<>();

    final List<String> timeline = new ArrayList<>();
    /** How the game ended: "done", or the reason; null while it runs. */
    String end;
    private int handled, logRead;
    private final TestData data;
    private final Thing.Catalog catalog;

    SimGame(SimWorld world, int[] feet) {
        this.world = world;
        this.feet = feet.clone();
        Only.set(Only.ORDERS);
        BatchWay.clear();
        data = TestData.planner();
        vanillaWood(data);
        data.install();
        catalog = catalog(data);
        bb.fact(NeedChain.WORLD_FACT, (Supplier<World>) this::facts);
        sense();
    }

    /** The tests' item data has sticks, tables and tools from oak planks only: every wood's planks stand in, as the game's tag does. */
    private static void vanillaWood(TestData d) {
        d.recipes.remove(TestData.id("stick"));
        d.recipes.remove(TestData.id("crafting_table"));
        d.recipe("stick", 4, "#minecraft:planks", "#minecraft:planks");
        d.recipe("crafting_table", 1, "#minecraft:planks", "#minecraft:planks", "#minecraft:planks", "#minecraft:planks");
        for (String m : List.of("wooden", "stone", "iron", "golden", "diamond"))       // the game's tool tags (as aa.ScenariosTest.toolWorld)
            d.tag(m + "_pickaxe", "minecraft:pickaxes").tag(m + "_axe", "minecraft:axes").tag(m + "_hoe", "minecraft:hoes")
                    .tag(m + "_sword", "minecraft:swords").tag(m + "_shovel", "minecraft:shovels");
        String p = "#minecraft:planks";
        for (String[] t : new String[][]{{"pickaxe", p, p, p, "stick", "stick"}, {"axe", p, p, p, "stick", "stick"}, {"shovel", p, "stick", "stick"},
                {"sword", p, p, "stick"}, {"hoe", p, p, "stick", "stick"}}) {
            d.recipes.remove(TestData.id("wooden_" + t[0]));
            d.recipe("wooden_" + t[0], 1, java.util.Arrays.copyOfRange(t, 1, t.length));
        }
    }

    private static Thing.Catalog catalog(TestData d) {
        Set<String> items = new HashSet<>(d.facts.keySet());
        items.addAll(d.recipes.keySet());
        items.addAll(d.tags.keySet());
        items.addAll(d.oreDrops.keySet());
        Set<String> blocks = new HashSet<>();
        for (var e : d.facts.entrySet()) if (e.getValue().hardness() > 0) blocks.add(e.getKey());
        blocks.addAll(d.oreDrops.values());
        return new Thing.Catalog(items, blocks, Map.of(), d.tiers, null, b -> {
            String g = SimWorld.drop(b);
            return g == null ? List.of() : List.of(g);
        }, i -> {
            List<String> out = new ArrayList<>();
            for (String b : blocks) if (i.equals(SimWorld.drop(b))) out.add(b);
            return out;
        });
    }

    /** The planner's facts, worked out from the blocks in sight and the bag. */
    private World facts() {
        return FakePaths.paths(new World(new HashMap<>(bag), stationNear("minecraft:crafting_table"), stationNear("minecraft:furnace"), this::seen, null, null, null, 0));
    }

    boolean stationNear(String block) {
        for (int dy = -1; dy <= 2; dy++) for (int dz = -4; dz <= 4; dz++) for (int dx = -4; dx <= 4; dx++)       // structural: within reach of the feet
            if (block.equals(world.at(feet[0] + dx, feet[1] + dy, feet[2] + dz))) return true;
        return false;
    }

    /** The sources in sight that give {@code word} (an item id, a #tag, or several joined by |): one spot per group of touching blocks. */
    private List<Spot> seen(String word) {
        List<Spot> out = new ArrayList<>();
        String[] parts = word.split("\\|");
        for (SimWorld.Group g : world.groups(feet, SIGHT, item -> matches(item, parts) != null)) {
            int[] c = g.cells().get(0);
            String gives = SimWorld.drop(g.block());
            Spot s = new Spot(c[0], c[1], c[2], SimWorld.dist(c, feet), g.block(), g.cells().size());
            out.add(gives.equals(g.block()) ? s : s.giving(gives));
        }
        return out;
    }

    static String matches(String item, String[] parts) {
        for (String p : parts) {
            if (p.startsWith("#") ? ItemWorth.isIn(item, p.substring(1)) : item.equals(p.contains(":") ? p : "minecraft:" + p)) return p;
        }
        return null;
    }

    private void sense() {
        Map<String, Integer> paths = new HashMap<>();
        for (var e : bag.entrySet()) paths.put(e.getKey().startsWith("minecraft:") ? e.getKey().substring("minecraft:".length()) : e.getKey(), e.getValue());
        bb.in.bag = Map.copyOf(paths);
        bb.in.pos = feet.clone();
    }

    /** aa want: one order of the line {@code batch} (null: an order alone); {@code deposit}: the deposit word. */
    Goals.Goal order(String word, int n, String batch, boolean deposit) {
        var r = Thing.resolve(word, catalog);
        if (r.err() != null) throw new IllegalArgumentException(word + ": " + r.err());
        Goals.Goal g = Goals.add(bb, r, n, 0, clock.now());
        g.batch = batch;
        deposits.add(g, batch, deposit);
        bb.fact(LineDeposits.FACT, deposits);          // deposit-kept: as the runtime's aa want
        brain.failures.forget(g.id());
        brain.add(AaDesires.ownerGoal(g));
        say("order: " + word + " " + n);
        return g;
    }

    double seconds() { return (clock.now() - t0) / 1000.0; }       // structural: milliseconds a second

    void say(String line) { timeline.add(String.format(Locale.ROOT, "%7.1f s  %s", seconds(), line)); }

    private void tick() {
        sense();
        brain.tick(bb);
        OrderEnds.tick(bb, brain, deposits, mainAt, false, "goal reached", new OrderEnds.Hooks() {
            @Override public boolean carriesOn(Goals.Goal g) { return false; }

            @Override public void got(String msg) { say(msg); }

            @Override public void deposit(LineDeposits.Out out) {
                if (out == null || out.text() == null) return;
                say(out.text());
                if (out.kind() == LineDeposits.Kind.DUE) due.add(out.items());
            }
        });
        for (; logRead < port.log.size(); logRead++) say("  log: " + port.log.get(logRead));
    }

    /** Plays until every order is done or the game cannot go on; {@code limitS}: the game seconds allowed. Returns {@link #end}. */
    String play(int limitS) {
        long idleSince = clock.now();
        while (end == null) {
            tick();
            while (!due.isEmpty() && end == null) { deposits.gone(); deposit(due.remove(0)); }
            if (end != null) break;
            if (Goals.of(bb).isEmpty()) { end = "done"; break; }
            if (seconds() > limitS) { end = "time limit: " + limitS + " s; orders left: " + orders(); break; }
            if (port.started.size() > handled) {
                run(port.started.get(handled), port.nextId - 1);
                handled = port.started.size();
                idleSince = clock.now();
                continue;
            }
            if (clock.now() - idleSince > STALL_MS) { end = "stalled: no run started in " + STALL_MS / 1000 + " s; " + brain.whyLines(3); break; }   // structural: ms a second
            clock.advance(IDLE_TICK_MS);
        }
        say("END: " + end);
        return end;
    }

    private String orders() {
        List<String> out = new ArrayList<>();
        for (Goals.Goal g : Goals.of(bb)) out.add(g.r.key() + " " + Goals.have(bb.in.bag, g) + "/" + g.n);
        return out.toString();
    }

    /** One run the brain started: its steps applied in order; the brain ticks between them and may cancel it. */
    private void run(JobPort.TaskSpec t, long id) {
        say("run " + id + " (" + t.source() + "): " + t.steps().size() + " steps");
        for (Step s : t.steps()) {
            SimSteps.Result r = SimSteps.apply(this, s);
            if (r.notSimulated()) {
                end = "not simulated: " + s.text();
                return;
            }
            clock.advance(Math.round(r.seconds() * 1000));       // structural: milliseconds a second
            say("  " + s.text() + " -> " + r.text() + String.format(Locale.ROOT, " (%.1f s)", r.seconds()));
            if (!r.ok()) {
                port.fail(id, r.text(), s);
                return;
            }
            port.step(id);
            tick();
            if (!port.states.containsKey(id)) {
                say("  run " + id + " cancelled: " + (port.cancelled.isEmpty() ? "?" : port.cancelled.get(port.cancelled.size() - 1)));
                return;
            }
            if (port.started.size() > handled + 1) return;      // the brain started another run meanwhile
        }
        port.done(id, "done");
    }

    /** The line's deposit came due: the walk to the main chest and exactly those items into it (the game's deposit job). */
    private void deposit(Map<String, Integer> items) {
        if (mainAt == null) { end = "deposit: no main chest set"; return; }
        String[] p = mainAt.split(" ");
        int[] c = {Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
        if (!"minecraft:chest".equals(world.at(c[0], c[1], c[2]))) { end = "deposit: no chest at " + mainAt + " (" + world.at(c[0], c[1], c[2]) + ")"; return; }
        SimSteps.Result w = SimSteps.apply(this, new Step.Walk(c[0], c[1], c[2], 2));
        clock.advance(Math.round(w.seconds() * 1000));       // structural: milliseconds a second
        List<String> put = new ArrayList<>(), short_ = new ArrayList<>();
        for (var e : items.entrySet()) {
            String id = e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey();
            int n = Math.min(e.getValue(), bag.getOrDefault(id, 0));
            if (n > 0) { take(id, n); chest.merge(id, n, Integer::sum); }
            put.add(n + " " + e.getKey());
            if (n < e.getValue()) short_.add(n + " of " + e.getValue() + " " + e.getKey());
        }
        say("deposit at " + mainAt + ": " + put + (short_.isEmpty() ? "" : "; SHORT: " + short_) + String.format(Locale.ROOT, " (walk %.1f s)", w.seconds()));
    }

    void give(String item, int n) { bag.merge(item, n, Integer::sum); }

    /** Takes {@code n} of {@code item} from the bag; false (nothing taken) when it holds fewer. */
    boolean take(String item, int n) {
        int have = bag.getOrDefault(item, 0);
        if (have < n) return false;
        if (have == n) bag.remove(item); else bag.put(item, have - n);
        return true;
    }

    String timelineText() { return String.join("\n", timeline); }
}
