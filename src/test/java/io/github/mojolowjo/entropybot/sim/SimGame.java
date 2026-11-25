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
    static final int SIGHT = 96;                 // sim: how far the fake senses see on each axis (the game's is the loaded chunks: 6 chunks at the tests' render distance)
    static final long IDLE_TICK_MS = 1000;       // sim: the clock's pace while no run is out
    static final long STALL_MS = 120_000;        // sim: this long with no run started and orders open ends the game "stalled"

    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final long t0 = clock.now();
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());
    final Brain brain = new Brain("brain", "sim");
    final LineDeposits deposits = new LineDeposits();
    /** Review 6: the put's ends go through the runtime's one reader (PutWatch), as StepHands and AaRuntime drive it. */
    final io.github.mojolowjo.entropybot.aa.brain.PutWatch put = new io.github.mojolowjo.entropybot.aa.brain.PutWatch(deposits);
    /** The task ids of this game's puts (SimSteps.deposit; any distinct ids). */
    long putTask;

    final SimWorld world;
    /** How far the fake senses see on each axis (an endless map sets it lower: the explore then has something to find). */
    int sight = SIGHT;
    /** Slice 4: the spots an explore has looked from ("i j" of the sight-wide grid). */
    final Set<String> looked = new HashSet<>();
    /** The bot: the feet (x y z) and the bag by full item id. */
    final int[] feet;
    final Map<String, Integer> bag = new TreeMap<>();
    /** The main chest's cell ("x y z"); null: none set. */
    String mainAt;
    /** The cells of the blocks the bot placed ("x y z"): its own stations. */
    final Set<String> mine = new HashSet<>();
    /** What the main chest holds, by full item id. */
    final Map<String, Integer> chest = new TreeMap<>();
    /** Review 4: the items the main chest still takes (null: no end, the sim's default). */
    Integer chestRoom;
    /** Review 4 (point 1): the main chest's hold, as the runtime's (AaRuntime.chestHold). */
    final io.github.mojolowjo.entropybot.aa.brain.MainChestHold chestHold = new io.github.mojolowjo.entropybot.aa.brain.MainChestHold();

    final List<String> timeline = new ArrayList<>();
    /** How the game ended: "done", or the reason; null while it runs. */
    String end;
    private int handled, logRead;
    /** Step 5: the chance a step of each kind ({@link #kind}) fails before it does anything; empty: no troubles. */
    final Map<String, Double> troubleRates = new HashMap<>();
    /** Step 5: the troubles' dice (a seed of the test's own: the same game twice). */
    java.util.Random troubleDice = new java.util.Random(1);
    /** deposit-goal stage 3: how many of the deposit goal's next walk-ups (Step.ToSurface) fail, injected. */
    int walkUpFailures;
    /** Step 5: the injected troubles so far. */
    int troubles;
    static final double TROUBLE_S = 10;          // sim: the seconds a failing step takes before it gives up (not measured)
    /** The rates seen in 16 recorded runs (to-do/simulator-plan.md): collect 7 of 25, climb 6 of 12, cut 4 of 56, deposits 4 of 15 (run 2; the planned put since the deposit goal, stage 3). Digs (5 of 26) have no brain-run step here. */
    static final Map<String, Double> MEASURED = Map.of("collect", 7 / 25.0, "climb", 6 / 12.0, "cut", 4 / 56.0, "deposit", 4 / 15.0);

    /** The trouble kind of a step; null: a kind no trouble was measured for. */
    static String kind(Step s) {
        if (s instanceof Step.Collect) return "collect";
        if (s instanceof Step.ChopTree) return "cut";
        if (s instanceof Step.Climb || s instanceof Step.ToSurface) return "climb";
        if (s instanceof Step.Deposit d && d.chest() != null) return "deposit";       // deposit-goal stage 3: the put (run 2: 4 of 15)
        return null;
    }

    /** The injected failure of this step, or null: it runs. */
    private String trouble(Step s) {
        // deposit-goal stage 3: the next walk-ups fail by the test's word (the game's ToSurface fault, its fact below)
        if (s instanceof Step.ToSurface && walkUpFailures > 0 && ("goal." + Goals.DEPOSIT_KEY).equals(runDesire)) { walkUpFailures--; troubles++; return "TROUBLE (injected, climb): the walk up gave up"; }
        Double p = troubleRates.get(kind(s));
        if (p == null || troubleDice.nextDouble() >= p) return null;
        troubles++;
        // deposit-goal stage 3: the put fails with the deposit's own words (the walk-up and the climb give their facts below)
        if (s instanceof Step.Deposit d) return "deposit: TROUBLE (injected): the chest at " + d.chestText() + " was not opened";
        return "TROUBLE (injected, " + kind(s) + "): the step gave up";
    }
    private final TestData data;
    private final Thing.Catalog catalog;

    SimGame(SimWorld world, int[] feet) {
        this.world = world;
        this.feet = feet.clone();
        Only.set(Only.ORDERS);
        BatchWay.clear();
        io.github.mojolowjo.entropybot.aa.brain.StationHolds.INSTANCE.reset();      // a new world: its held stations go (as AaRuntime's)
        data = TestData.planner();
        vanillaWood(data);
        data.install();
        catalog = catalog(data);
        bb.fact(NeedChain.WORLD_FACT, (Supplier<World>) this::facts);
        // review 4 (point 1): a cell that is no chest block answers held (gone), as the game's reader; the hold store as the runtime's
        bb.fact(io.github.mojolowjo.entropybot.aa.brain.MainChest.FACT, (Supplier<io.github.mojolowjo.entropybot.aa.brain.MainChest.Answer>) () -> io.github.mojolowjo.entropybot.aa.brain.MainChest.ofText(mainAt, null,
                c -> "minecraft:chest".equals(world.at(c[0], c[1], c[2]))));   // deposit-goal piece 3: the one reader
        bb.fact(io.github.mojolowjo.entropybot.aa.brain.MainChestHold.FACT, chestHold);
        // job-own-aside-worker (worlds run 4): the trips' legs and the orders' own chains through the planner worker's path,
        // as the game resolves them (snapshot, PlannerWorker.resolve, the port's live check), synchronously
        bb.fact(BatchWay.PLANNER_FACT, planner);
        sense();
    }

    /** The planner worker's path, synchronously; its drops (a planner fault: the sim's world stands still) go to the timeline. */
    final io.github.mojolowjo.entropybot.commands.SyncPlanner planner = new io.github.mojolowjo.entropybot.commands.SyncPlanner(line -> timeline.add(line));

    /** One order read from a scenario's words. */
    record Asked(String item, int n, boolean deposit) {}

    private static final java.util.regex.Pattern OPEN = java.util.regex.Pattern.compile("^(\\S+) \\d+/(\\d+)( \\(deposit\\))?:");
    private static final java.util.regex.Pattern MAIN_CHEST = java.util.regex.Pattern.compile("chest chest (-?\\d+ -?\\d+ -?\\d+)");

    /**
     * The orders a scenario's {@code orders} words hold (aa goals, as the mod said them): each open order "item have/n
     * (deposit): ...", and the finished ones of the line ("finished, ...: cobblestone 32"). A finished one's deposit word
     * is not in the words: it is taken as the open orders' (a line is all deposit or none in the runs so far).
     */
    static List<Asked> asked(String orders) {
        List<Asked> open = new ArrayList<>(), fin = new ArrayList<>();
        if (orders == null) return open;
        boolean dep = false;
        for (String line : orders.split("\\R")) {
            var m = OPEN.matcher(line);
            if (m.find()) {
                open.add(new Asked(m.group(1), Integer.parseInt(m.group(2)), m.group(3) != null));
                dep |= m.group(3) != null;
            } else if (line.startsWith("finished")) {
                for (String p : line.substring(line.lastIndexOf(": ") + 2).split(", ")) {
                    String[] w = p.trim().split(" ");
                    if (w.length == 2 && w[1].matches("\\d+")) fin.add(new Asked(w[0], Integer.parseInt(w[1]), false));
                }
            }
        }
        List<Asked> out = new ArrayList<>();
        for (Asked a : fin) out.add(new Asked(a.item(), a.n(), dep));
        out.addAll(open);
        return out;
    }

    /**
     * Slice 3: a game from a scenario file - its cube as the map, its feet and bag, the main chest of its stations words,
     * and its orders as one line (finished ones first, as they were given first).
     */
    static SimGame of(Scenario s) {
        SimGame g = new SimGame(SimWorld.of(s), s.feet);
        for (var e : s.bagCounts().entrySet()) g.give(e.getKey(), e.getValue());
        var m = MAIN_CHEST.matcher(s.stations == null ? "" : s.stations);
        if (m.find()) g.mainAt = m.group(1);
        g.say("scenario: " + s.summary() + "; main chest " + g.mainAt + "; bag " + g.bag);
        for (Asked a : asked(s.orders)) g.order(a.item(), a.n(), "b1", a.deposit());
        return g;
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
        return FakePaths.paths(new World(new HashMap<>(bag), stationNear("minecraft:crafting_table"), stationNear("minecraft:furnace"), this::seen, null, null, null, 0))
                .withWhere(new NeedChain.WhereFacts(world.where, this::surface, () -> feet.clone()));
    }

    /**
     * Slice 4b: the surface over the feet, as the game's WhereFacts gives it: the blocks over the head (0: open sky) and the
     * cell to walk up to. The game's own pure rules (SurfaceHeight: the hole round the feet, the ring's top) on the
     * sim's columns; no ring top: the nearest cell under open sky, else the top of the feet's own column.
     */
    private NeedChain.Surface surface() {
        int rock = world.over(feet[0], feet[1], feet[2]), hole = world.holeDepth(feet[0], feet[1], feet[2]);
        int depth = io.github.mojolowjo.entropybot.world.SurfaceHeight.belowSurface(rock, hole);
        if (depth == 0) return new NeedChain.Surface(0, feet[0], feet[1], feet[2], 0);
        int[] c = world.ringTop(feet);
        if (c == null) c = world.skyCell(feet, SimSteps.SKY_R);
        if (c == null) c = new int[]{feet[0], Math.max(feet[1], world.surfaceY(feet[0], feet[2])), feet[2]};
        return new NeedChain.Surface(depth, c[0], c[1], c[2], SimWorld.dist(c, feet), rock == 0, c, SimWorld.dist(c, feet));
    }
    boolean stationNear(String block) {
        for (int dy = -1; dy <= 2; dy++) for (int dz = -4; dz <= 4; dz++) for (int dx = -4; dx <= 4; dx++)       // structural: within reach of the feet
            if (block.equals(world.at(feet[0] + dx, feet[1] + dy, feet[2] + dz))) return true;
        return false;
    }

    /** The sources in sight that give {@code word} (an item id, a #tag, or several joined by |): one spot per group of touching blocks. */
    private List<Spot> seen(String word) {
        String stamp = world.version + " " + feet[0] + " " + feet[1] + " " + feet[2] + " " + sight;
        if (!stamp.equals(seenStamp)) { seenStamp = stamp; seenKept.clear(); }       // the same blocks from the same spot: the same answer
        return seenKept.computeIfAbsent(word, this::look);
    }

    private String seenStamp;
    private final Map<String, List<Spot>> seenKept = new HashMap<>();

    private List<Spot> look(String word) {
        List<Spot> out = new ArrayList<>();
        String[] parts = word.split("\\|");
        for (SimWorld.Group g : world.groups(feet, sight, item -> matches(item, parts) != null)) {
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
        // as the runtime's aa want: an order of the same item that is replaced leaves its line first
        Goals.Goal old = Goals.find(bb, r.key());
        if (old != null && old.deposit > 0) {
            sense();
            LineDeposits.Out out = deposits.replaced(old, bb.in.bag, mainAt);
            if (out != null && out.text() != null) say(out.text());
        }
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

    static final int TIMELINE_MAX = 100_000;     // sim: a game that says more lines than this is in a loop (ended, said)

    void say(String line) {
        if (timeline.size() >= TIMELINE_MAX) {
            if (end == null) end = "sim: over " + TIMELINE_MAX + " lines said (a loop); the last: " + timeline.get(timeline.size() - 1);
            return;
        }
        timeline.add(String.format(Locale.ROOT, "%7.1f s  %s", seconds(), line)); }

    /** aa cancel: the order leaves the goals and its line (the runtime's drop). */
    void drop(Goals.Goal g) {
        Goals.of(bb).remove(g);
        brain.remove(g.id(), "aa cancel");
        say("drop: " + g.r.key());
        sense();
        LineDeposits.Out out = deposits.dropped(g, bb.in.bag, mainAt);
        if (out == null || out.text() == null) return;
        say(out.text());
    }

    void tick() {
        sense();
        brain.tick(bb);
        OrderEnds.Hooks hooks = new OrderEnds.Hooks() {
            @Override public boolean carriesOn(Goals.Goal g) { return false; }

            @Override public void got(String msg) { say(msg); }

            @Override public void deposit(LineDeposits.Out out) {
                if (out == null || out.text() == null) return;
                say(out.text());
            }
        };
        OrderEnds.tick(bb, brain, deposits, mainAt, false, "goal reached", hooks);
        // deposit-goal stage 3: the line's deposit is a goal (its walk, its ways up, the put: SimSteps' Deposit actor)
        OrderEnds.depositSync(bb, brain, deposits, hooks);
        for (; logRead < port.log.size(); logRead++) say("  log: " + port.log.get(logRead));
    }

    /** Plays until every order is done or the game cannot go on; {@code limitS}: the game seconds allowed. Returns {@link #end}. */
    String play(int limitS) {
        long idleSince = clock.now();
        while (end == null) {
            tick();
            if (end != null) break;
            if (Goals.of(bb).isEmpty()) { end = "done"; break; }
            // deposit-goal stage 3: only the line's deposit is left, stopped, and nothing it waits on can change: the end names it
            var dg = Goals.depositGoal(bb);
            if (dg != null && dg.parked != null && Goals.of(bb).size() == 1 && port.started.size() == handled
                    && Goals.stillChanges(Goals.of(bb), dg, false, false, false) == null) {
                end = "deposit parked: " + dg.parked + "; owed " + deposits.owed();
                break;
            }
            if (seconds() > limitS) { end = "time limit: " + limitS + " s; orders left: " + orders(); break; }
            if (port.started.size() > handled) {
                // the newest run (an older one not handled was cancelled by it); counted before it runs, so a run the brain
                // starts while this one runs is handled next, never skipped (deposit-goal stage 3: the deposit goal's run
                // started in the tick that cancelled a dropped order's run)
                handled = port.started.size();
                run(port.started.get(handled - 1), handled);
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

    static final long TICK_MS = 50;              // structural: a game tick's milliseconds
    static final int RUN_TICKS = 20_000;         // sim: a run that asks its steps this often is ended (a loop, said)

    /**
     * One run the brain started, driven as the game drives it: the mod's own {@link io.github.mojolowjo.entropybot.aa.moves.Steps}
     * (the dry run, the re-plan after a failed step, the park on the same try twice) with the sim's step actors as its hands.
     * The brain ticks between the steps and may cancel the run.
     */
    /** The reserves the mod's Steps last sent to the lease (the fork's IRunThrowaway.reserve in the game); empty: none. */
    List<baritone.api.process.Reserves.Reserve<String>> lease = List.of();

    /**
     * How many of {@code id} (a full item id) Baritone may place now: 0 for a block that is no filler (AaEscape.pillarBlock,
     * the game's RunThrowaway.bagBlocks), else the fork's own count over the lease ({@code Reserves.spare}: what is left
     * after every reserve is met).
     */
    int spare(String id) {
        if (!io.github.mojolowjo.entropybot.aa.AaEscape.pillarBlock(id) || bag.getOrDefault(id, 0) <= 0) return 0;
        return Math.max(0, baritone.api.process.Reserves.spare(lease, bag, id));
    }

    /** Step 3: the moves (walk, climb, the way to a block) are Baritone's real search on these blocks; false: the sim's own dug-stair search. */
    boolean realSearch = true;

    /** The filler block with the most to spare ({@link #spare}), null: none. */
    String bestSpare() {
        String best = null;
        int most = 0;
        for (String id : bag.keySet()) { int sp = spare(id); if (sp > most) { most = sp; best = id; } }
        return best;
    }

    /** deposit-goal stage 3: the deposit goal's steps as they ran ("walk", "walk up", "climb", "put", other: its text; "!": it failed). */
    final List<String> depositLegs = new ArrayList<>();
    /** deposit-goal stage 3: the deposit goal's runs started (more than one: re-planned). */
    int depositRuns;

    private static String leg(Step s) {
        if (s instanceof Step.ToStation) return "walk";
        if (s instanceof Step.ToSurface) return "walk up";
        if (s instanceof Step.Climb) return "climb";
        if (s instanceof Step.Deposit) return "put";
        return s.text();
    }

    /** deposit-goal stage 3: the deposit column of the worlds' table - the legs taken and the re-plans; "none": no deposit goal ran. */
    String depositColumn() {
        return depositLegs.isEmpty() ? "none" : String.join(" > ", depositLegs) + " (runs " + depositRuns + ")";
    }

    /** The running run's order (its desire id; null: no order's run), as AaRuntime's taskReplan. */
    private String runDesire;

    /** The bag when the running run started (AaRuntime's typedStartBag). */
    private Map<String, Integer> runStartBag;

    private void run(JobPort.TaskSpec t, long id) {
        say("run " + id + " (" + t.source() + "): " + t.steps().size() + " steps");
        runDesire = t.replan() == null ? null : t.replan().desire();
        if (("goal." + Goals.DEPOSIT_KEY).equals(runDesire)) depositRuns++;
        runStartBag = new HashMap<>(bag);                // as AaRuntime.runTyped's typedStartBag: the bag at the run's start
        var steps = new io.github.mojolowjo.entropybot.aa.moves.Steps(new Hands());
        steps.start(t.chain(), t.steps(), t.uses(), clock.now() / TICK_MS, t.replan() == null ? null : t.replan().planner());
        for (int n = 0; steps.running() && end == null; n++) {
            if (n > RUN_TICKS) { end = "sim: run " + id + " asked its steps " + RUN_TICKS + " times without ending (" + steps.why() + ")"; steps.cancel("sim: loop"); return; }
            long before = clock.now();
            steps.tick(clock.now() / TICK_MS);
            if (end != null) return;
            port.step(id);
            tick();
            if (!port.states.containsKey(id)) {
                steps.cancel("the brain cancelled the run");
                say("  run " + id + " cancelled: " + (port.cancelled.isEmpty() ? "?" : port.cancelled.get(port.cancelled.size() - 1)));
                return;
            }
            if (port.started.size() > handled) { steps.cancel("the brain started another run"); return; }
            if (clock.now() == before) clock.advance(TICK_MS);
        }
        if (end != null) return;
        if (steps.phase() == io.github.mojolowjo.entropybot.aa.moves.Steps.Phase.DONE) port.done(id, "done");
        else {
            say("  run " + id + " ended: " + steps.why());
            port.fail(id, steps.why(), steps.failedStep());
        }
    }

    /** The sim's hands for {@link io.github.mojolowjo.entropybot.aa.moves.Steps}: a step is applied whole when it starts; its status says how it went. */
    private final class Hands implements io.github.mojolowjo.entropybot.aa.moves.Steps.Hands {
        private SimSteps.Result last;
        private io.github.mojolowjo.entropybot.aa.moves.FactChange.Fact fact;

        @Override public String start(Step s) {
            String tr = trouble(s);
            last = tr != null ? SimSteps.Result.fail(TROUBLE_S, tr) : SimSteps.apply(SimGame.this, s);
            if (last.notSimulated()) { end = "not simulated: " + s.text(); return "not simulated"; }
            // no fact of the hands' own: the game's failed collect or cut mostly gives none either, and the mod's Steps then
            // makes the fact itself (StepsShort: a collect short NEAR the feet, a cut its tree). A made-up "source collect
            // <item>" here dropped the item for the order's life (the game's timeout fact only) and the order explored for ever
            fact = null;
            // the walk up and the climb do give one in the game (StepHands: FactChange.surface, FactChange.climb): the same here
            if (tr != null && s instanceof Step.ToSurface t) { io.github.mojolowjo.entropybot.aa.moves.FactChange.surface(t.x(), t.y(), t.z(), tr); fact = io.github.mojolowjo.entropybot.aa.moves.FactChange.take(); }
            if (tr != null && s instanceof Step.Climb) { io.github.mojolowjo.entropybot.aa.moves.FactChange.climb(tr); fact = io.github.mojolowjo.entropybot.aa.moves.FactChange.take(); }
            // job-deposit-park-wake: the walk to a station that ends short gives the game's station fact too (StepHands.toStation:
            // "<name> at x y z out for this order: the walk to it ended short: ...", held in StationHolds by Goals.replan)
            if (!last.ok() && s instanceof Step.ToStation t) {
                io.github.mojolowjo.entropybot.aa.moves.FactChange.stationWalk(t.name(), t.x(), t.y(), t.z(), "the walk to it ended short: " + last.text());
                fact = io.github.mojolowjo.entropybot.aa.moves.FactChange.take();
            }
            clock.advance(Math.round(last.seconds() * 1000));       // structural: milliseconds a second
            if (("goal." + Goals.DEPOSIT_KEY).equals(runDesire)) depositLegs.add(leg(s) + (last.ok() ? "" : "!"));
            say("  " + s.text() + " -> " + last.text() + String.format(Locale.ROOT, " (%.1f s)", last.seconds()));
            return null;
        }

        @Override public io.github.mojolowjo.entropybot.aa.moves.Car.Status status(Step s) {
            return last.ok() ? io.github.mojolowjo.entropybot.aa.moves.Car.Status.done(last.text()) : io.github.mojolowjo.entropybot.aa.moves.Car.Status.failed(last.text());
        }

        @Override public void cancel(String why) {}

        @Override public io.github.mojolowjo.entropybot.aa.moves.FactChange.Fact lastFact() { var f = fact; fact = null; return f; }

        @Override public int[] feet() { return feet.clone(); }

        @Override public void log(String line) { say("  steps: " + line); }

        /** The fork's lease in the game (RunThrowaway.reserve): the table Steps counted for the steps from here, kept for {@link #spare}. */
        @Override public void reserve(List<baritone.api.process.Reserves.Reserve<String>> reserves) { lease = reserves == null ? List.of() : List.copyOf(reserves); }

        /** As AaRuntime.orderReserves: the running order's, the finished ones' and every -d amount in the bag. */
        @Override public List<baritone.api.process.Reserves.Reserve<String>> orderReserves() {
            Goals.Goal running = null;
            if (runDesire != null) for (Goals.Goal g : Goals.of(bb)) if (g.id().equals(runDesire)) running = g;
            return Goals.orderReserves(running, Goals.finished(bb), bb.in.bag, Goals.keptPer(bb));
        }

        /** As the game's hands (StepHands.freeBag over AaRuntime.keptFromOthers): the bag less the other orders' -d amounts. */
        @Override public io.github.mojolowjo.entropybot.aa.moves.StepNeeds.Bag freeBag() {
            return io.github.mojolowjo.entropybot.aa.moves.StepNeeds.less(bag(), Goals.keptForRun(bb, runDesire, runDesire != null));
        }

        @Override public io.github.mojolowjo.entropybot.aa.moves.StepNeeds.Bag bag() {
            return new io.github.mojolowjo.entropybot.aa.moves.StepNeeds.Bag() {
                @Override public int bestTier(String kind) {
                    int best = 0;
                    for (var e : bag.entrySet()) if (e.getValue() > 0) best = Math.max(best, io.github.mojolowjo.entropybot.aa.brain.MiningTiers.toolTier(e.getKey(), kind));
                    return best;
                }

                @Override public String bestTool(String kind) {
                    String id = null;
                    int best = 0;
                    for (var e : bag.entrySet()) {
                        int tier = e.getValue() > 0 ? io.github.mojolowjo.entropybot.aa.brain.MiningTiers.toolTier(e.getKey(), kind) : 0;
                        if (tier > best) { best = tier; id = e.getKey(); }
                    }
                    return id;
                }

                @Override public int usesLeft(String id) { return io.github.mojolowjo.entropybot.aa.brain.ToolUses.UNLIMITED; }     // sim: tools do not wear

                @Override public int count(String id) { return bag.getOrDefault(id.contains(":") ? id : "minecraft:" + id, 0); }

                @Override public Map<String, Integer> items() { return new TreeMap<>(bag); }
            };
        }
    }
    /** The bag less every -d amount kept for a deposit (full item ids): what a step may use up first, as the planner counts it. */
    Map<String, Integer> freeItems() {
        Map<String, Integer> free = new HashMap<>(bag);
        for (var e : Goals.kept(bb, null, null).entrySet()) {
            String id = e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey();
            free.computeIfPresent(id, (k, v) -> Math.max(0, v - e.getValue()));
        }
        return free;
    }

    /**
     * reserves-once (b): as the game's craft and smelt bag (Crafting.planBag over AaRuntime.keptFromUse = Goals.keptForRun
     * with consume): the bag less what the running order's run may not use up (no order's run: every kept amount).
     */
    Map<String, Integer> useItems() {
        Map<String, Integer> free = new HashMap<>(bag);
        var kept = runDesire == null ? Goals.kept(bb, null, null) : Goals.keptForRun(bb, runDesire, true, true, runStartBag);
        for (var e : kept.entrySet()) {
            String id = e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey();
            free.computeIfPresent(id, (k, v) -> Math.max(0, v - e.getValue()));
        }
        return free;
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
