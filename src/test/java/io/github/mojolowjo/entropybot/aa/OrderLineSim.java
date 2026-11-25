package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.Brain;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.LineDeposits;
import io.github.mojolowjo.entropybot.aa.brain.OrderEnds;
import io.github.mojolowjo.entropybot.aa.brain.Only;
import io.github.mojolowjo.entropybot.aa.brain.Thing;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * sim (owner 2026-11-09, "we should be able to simulate the parts we need"): an order line with no game. The REAL brain
 * (Brain, AaDesires.ownerGoal, Goals: the park, the retries, the done check), the REAL chain planner on the tests' recipe
 * data, and the REAL order-end pass and deposit weighing (OrderEnds, LineDeposits) run against what the test scripts: the
 * clock, the bag, the sources seen, and how each started run ends (done, or failed with a reason taken from a run's log).
 *
 * <p>What it is not: no blocks, no Baritone, no step runner - a run is one job the test ends by hand. A scenario is as true
 * as the ends it scripts, so each takes its reasons and times from a real run's log (named in the scenario).
 */
final class OrderLineSim {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());
    final Brain brain = new Brain("brain", "sim");
    final LineDeposits deposits = new LineDeposits();

    /** The main chest's cell; null: none set. */
    String mainAt = "-53 107 -8";
    /** The bag by item path ("cobblestone"). */
    final Map<String, Integer> bag = new HashMap<>();
    /** The sources seen, by item path. */
    final Map<String, List<Spot>> seen = new HashMap<>();
    boolean table, furnace, underGround;

    /** What the pass said: the "got" lines and the deposit lines, in order. */
    final List<String> said = new ArrayList<>();
    /** Every deposit that came due: the items of each. */
    final List<Map<String, Integer>> due = new ArrayList<>();

    /** The catalog of the scenarios' items (a block that drops another item names it). */
    static final Thing.Catalog CATALOG = new Thing.Catalog(
            List.of("minecraft:cobblestone", "minecraft:oak_log", "minecraft:stick", "minecraft:oak_planks"),
            List.of("minecraft:stone", "minecraft:oak_log"), Map.of(), Map.of("minecraft:stone", 1), null,
            b -> b.equals("minecraft:stone") ? List.of("minecraft:cobblestone") : List.of(),
            i -> i.equals("minecraft:cobblestone") ? List.of("minecraft:stone") : List.of());

    OrderLineSim() {
        Only.set(Only.ORDERS);
        io.github.mojolowjo.entropybot.aa.brain.BatchWay.clear();      // the trips and own chains kept by an earlier test
        ScenariosTest.toolWorld();
        bb.fact(NeedChain.WORLD_FACT, (Supplier<World>) this::world);
        bb.fact(LineDeposits.FACT, deposits);          // deposit-kept: the one store, as the runtime's (the deposit goal reads owed from it)
        bb.fact(io.github.mojolowjo.entropybot.aa.brain.MainChest.FACT, (Supplier<io.github.mojolowjo.entropybot.aa.brain.MainChest.Answer>) () -> io.github.mojolowjo.entropybot.aa.brain.MainChest.ofText(mainAt, null));   // deposit-goal piece 3: the one reader
        sense();
    }

    private World world() {
        Map<String, Integer> ns = new HashMap<>();
        for (var e : bag.entrySet()) ns.put("minecraft:" + e.getKey(), e.getValue());
        World w = ScenariosTest.w(ns, table, furnace, seen).withWhere(new NeedChain.WhereFacts(Z77ExploreTest::where, () -> surface, () -> feet.clone()));
        return path == null ? w : w.withPath(path);
    }

    /** deposit-goal stage 3: the feet (x y z) and the surface over them (null: not known); on the surface by default. */
    final int[] feet = {0, 64, 0};
    NeedChain.Surface surface = new NeedChain.Surface(0, 0, 64, 0, 0);
    /** deposit-goal stage 3: the walks' prices when a test sets them (null: the fake straight-line price). */
    PathPrice path;

    /** Under ground {@code depth} blocks, the walk up's cell straight over the feet. */
    void under(int depth) {
        underGround = true;
        surface = new NeedChain.Surface(depth, feet[0], feet[1] + depth, feet[2], depth);
    }

    /**
     * The planner worker as the game has it (BatchWay.Planner: an order's chain is resolved off the tick): every ask
     * answers "thinking" - the order's run is then the stand ("stand while paths compute") - until {@link #plannerAnswers}
     * runs (the worker's answer landed), then the resolve itself. {@link #plannerOnTheTick} takes it away again.
     */
    void plannerThinks() {
        boolean[] thinking = {true};
        plannerAnswers = () -> thinking[0] = false;
        int[] left = {0};
        bb.fact(io.github.mojolowjo.entropybot.aa.brain.BatchWay.PLANNER_FACT, new io.github.mojolowjo.entropybot.aa.brain.BatchWay.Planner() {
            @Override public NeedChain.Legs legs(String key, Playbook pb, java.util.Set<String> keptFuel, World w) { return NeedChain.thinking("thinking: 1 node dirty"); }

            @Override public boolean ready(String key) { return !thinking[0]; }

            // job-own-aside-worker (worlds run 4): the landed answer is the worker path's (SyncPlanner), as in the game
            @Override public NeedChain.Chain own(String key, io.github.mojolowjo.entropybot.aa.brain.BatchWay.OwnAsk ask, World w) {
                return thinking[0] ? NeedChain.thinking("thinking: 2 nodes dirty").chain() : sync.own(key, ask, w);
            }
        });
    }

    /** The planner worker's path, synchronously (job-own-aside-worker); its drops are a planner fault. */
    final io.github.mojolowjo.entropybot.commands.SyncPlanner sync = new io.github.mojolowjo.entropybot.commands.SyncPlanner(line -> port.log(line));

    /** The worker's answer landed: from now on every ask is the resolve itself. */
    Runnable plannerAnswers = () -> { };

    void plannerOnTheTick() { bb.fact(io.github.mojolowjo.entropybot.aa.brain.BatchWay.PLANNER_FACT, null); }

    /** The bag as the senses give it to the brain. */
    private void sense() { bb.in.bag = Map.copyOf(bag); bb.in.pos = feet.clone(); }

    /** aa want: {@code batch} null = an order alone; {@code deposit}: the -d word. */
    Goals.Goal order(String word, int n, String batch, boolean deposit) {
        var r = Thing.resolve(word, CATALOG);
        if (r.err() != null) throw new IllegalArgumentException(word + ": " + r.err());
        // as the runtime's aa want: an order of the same item that is replaced leaves its line first
        Goals.Goal old = Goals.find(bb, r.key());
        if (old != null && old.deposit > 0) {
            LineDeposits.Out out = deposits.replaced(old, bag, mainAt);
            if (out != null && out.text() != null) { said.add(out.text()); if (out.kind() == LineDeposits.Kind.DUE) due.add(out.items()); }
        }
        Goals.Goal g = Goals.add(bb, r, n, 0, clock.now());
        g.batch = batch;
        deposits.add(g, batch, deposit);
        brain.failures.forget(g.id());
        brain.add(AaDesires.ownerGoal(g));
        return g;
    }

    /** parked-holds: the park of {@code g} keeps nothing it waits on any more (as a park only the owner's word changes): it cannot wake by itself. */
    void stoppedForGood(Goals.Goal g) {
        g.park = null;
        g.loopPark = null;
    }

    /** One pass: the senses, the brain, then the order-end pass (as the game's tick does). */
    void tick() {
        sense();
        brain.tick(bb);
        OrderEnds.Hooks hooks = new OrderEnds.Hooks() {
            @Override public boolean carriesOn(Goals.Goal g) { return false; }

            @Override public void got(String msg) { said.add(msg); }

            @Override public void deposit(LineDeposits.Out out) {
                if (out == null || out.text() == null) return;
                said.add(out.text());
                if (out.kind() == LineDeposits.Kind.DUE) due.add(out.items());       // what the weighing answered (the goal's owed items)
                if (out.kind() == LineDeposits.Kind.WOKE_TAKEN_BACK) takenBack.add(out.text());
            }
        };
        OrderEnds.tick(bb, brain, deposits, mainAt, underGround, "goal reached", hooks);
        // deposit-goal stage 3: the line's deposit is a goal (OrderEnds.depositSync), as the sim's game
        OrderEnds.depositSync(bb, brain, deposits, hooks);
    }

    /** deposit-goal stage 3: the deposit goal among the goals, null: none. */
    Goals.Goal depositGoal() { return Goals.depositGoal(bb); }

    /** The owner's orders still among the goals (the deposit goal left out). */
    List<Goals.Goal> ordersLeft() {
        List<Goals.Goal> out = new ArrayList<>();
        for (Goals.Goal g : Goals.of(bb)) if (!g.depositGoal) out.add(g);
        return out;
    }

    /** The due deposits taken back by a wake before their run started (the line said for each). */
    final List<String> takenBack = new ArrayList<>();

    /**
     * The deposit goal's put (SimSteps' Deposit actor in short): it starts (a wake leaves it alone from here), then it
     * ends reached. The bag is the test's to set.
     */
    void depositRuns() {
        deposits.putStarted();
        deposits.putEnded(true);
    }

    /** deposit-goal stage 3: the put started and failed (the chest not opened): what it took is owed again. */
    void depositFails() {
        deposits.putStarted();
        deposits.putEnded(false);
    }

    /** {@code n} passes, {@code ms} apart. */
    void run(int n, long ms) {
        for (int i = 0; i < n; i++) { clock.advance(ms); tick(); }
    }

    /** The id of the running order's desire ("goal.cobblestone"), null: nothing runs. */
    String running() {
        var d = brain.runningDesire();
        return d == null ? null : d.id;
    }

    /** How many runs were started so far. */
    int starts() { return port.started.size(); }

    /** The run started last ends failed with {@code why} (a reason from a run's log). */
    void failRun(String why) { port.fail(port.nextId - 1, why); }

    /** The run started last ends done. */
    void endRun(String text) { port.done(port.nextId - 1, text); }
}
