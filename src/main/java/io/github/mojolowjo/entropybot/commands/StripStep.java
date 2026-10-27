package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.process.IStripProcess;
import baritone.api.utils.BlockOptionalMetaLookup;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.Quiet;
import io.github.mojolowjo.entropybot.aa.brain.StripOrder;
import io.github.mojolowjo.entropybot.aa.brain.StripYield;
import io.github.mojolowjo.entropybot.aa.brain.Thing;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.cave.OreSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The strip order in the game: the words resolved against the game's data (ore tags, mining tiers, the catalog's
 * worldgen band, the bag's best pickaxe), and the step's hands: the fork's StripProcess gets only the ore set, the Y and
 * the heading; Baritone decides every stand, look and click. Its progress is saved per world (strip-progress.json, by
 * the order's key) so the same order resumes from the tunnel end after any pause or a relaunch. 0.39.z77 strip: a family
 * met leaves the strip (the fork's set without its ores) and the families left go on only while the strip is their
 * cheapest way ({@link StripYield}); a "no path" inside the tunnel says the rule of the cell Baritone's goal named.
 * Loader notes: Baritone's API (IStripProcess, IPathingBehavior.getGoal / GoalBlock); vanilla Minecraft.player / level.
 */
final class StripStep {
    private StripStep() {}

    private static final Gson GSON = new Gson();

    /** The ore blocks the game tags as ores (c:ores, *:*_ores): id -> mining tier (Thing.tier's scale). */
    static Map<String, Integer> oreTiers() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            BlockState st = b.defaultBlockState();
            boolean ore = st.is(net.neoforged.neoforge.common.Tags.Blocks.ORES)
                    || st.getTags().anyMatch(t -> t.location().getPath().endsWith("_ores"));
            if (!ore) continue;
            int tier = Thing.tier(st.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE), st.is(net.minecraft.tags.BlockTags.NEEDS_STONE_TOOL),
                    st.is(net.minecraft.tags.BlockTags.NEEDS_IRON_TOOL), st.is(net.minecraft.tags.BlockTags.NEEDS_DIAMOND_TOOL));
            out.put(BuiltInRegistries.BLOCK.getKey(b).toString(), Math.max(1, tier));
        }
        return out;
    }

    /** The order's words as a step, or the reason in {@code err[0]}. */
    static Step.Strip resolve(String arg, LocalPlayer p, Thing.Catalog cat, String[] err) {
        StripOrder.Args a = StripOrder.parse(arg);
        if (a.err() != null) { err[0] = a.err(); return null; }
        Map<String, Integer> tiers = oreTiers();
        if (tiers.isEmpty()) { err[0] = "error: the game tags no block as an ore (c:ores)"; return null; }
        List<String> ores;
        if (a.any()) {
            int pick = io.github.mojolowjo.entropybot.guard.PolicyRuntime.pickTier(p);
            if (pick <= 0) { err[0] = "error: strip any: no pickaxe in the bag"; return null; }
            ores = StripOrder.anyOres(tiers, pick);
            if (ores.isEmpty()) { err[0] = "error: strip any: no ore my pickaxe can take"; return null; }
        } else {
            ores = StripOrder.namedOres(a.ores(), tiers.keySet(), OreSpec::family);
            if (ores.size() == 1 && ores.get(0).startsWith("error:")) { err[0] = ores.get(0); return null; }
        }
        Map<String, Integer> amounts = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : a.amounts().entrySet()) amounts.merge(StripOrder.countKey(e.getKey(), OreSpec::family), e.getValue(), Integer::sum);
        Integer y = a.y();
        if (y == null && !a.any()) y = StripOrder.bandCentre(ores, cat == null ? null : cat.gen());
        if (y == null) y = p.blockPosition().getY();          // "any", or no worldgen band read (multiplayer): here
        String heading = a.heading() != null ? a.heading() : p.getDirection().getName();
        return new Step.Strip(ores, amounts, y, heading, System.currentTimeMillis());
    }

    // ---- the saved progress (per world) ----

    private static Path file() {
        var f = Core.INSTANCE.files();
        if (f == null) throw new IllegalStateException("no world files (not in a world)");
        return f.worldRoot().resolve("strip-progress.json");
    }

    private static JsonObject readAll() {
        try {
            Path f = file();
            if (!Files.exists(f)) return new JsonObject();
            return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("strip-progress.json unreadable: " + e, e);
        }
    }

    /** The saved progress of this order, null none. */
    static StripOrder.Saved load(Step.Strip s) {
        JsonObject all = readAll();
        return all.has(s.key()) ? GSON.fromJson(all.get(s.key()), StripOrder.Saved.class) : null;
    }

    /** 0.39.z62 item 1b: the saved file's keys as last read (null: not read since the last write). */
    private static java.util.Set<String> savedKeys;
    /** The world's file those keys were read from (a world change reads again). */
    private static String savedFile;

    /**
     * 0.39.z62 item 1b: the heading of a strip of exactly these ores at this Y whose progress is saved in this world (a
     * tunnel begun: the planner resumes it rather than start one where the bot happens to face); null: none, or no world
     * files. The keys are read once per write (the planner asks per chain).
     */
    static String startedHeading(List<String> ores, int y) {
        java.util.Set<String> keys = savedKeys;
        String where;
        try {
            where = file().toString();
            if (keys == null || !where.equals(savedFile)) {
                keys = new java.util.HashSet<>(readAll().keySet());
                savedKeys = keys;
                savedFile = where;
            }
        } catch (RuntimeException e) {
            Quiet.warn("StripStep.startedHeading: strip-progress.json not read (no tunnel to resume known)", e);
            return null;
        }
        return StripOrder.startedHeading(keys, ores, y);
    }

    private static void save(Step.Strip s, StripOrder.Saved p) {
        savedKeys = null;
        JsonObject all = readAll();
        if (p == null) all.remove(s.key());
        else all.add(s.key(), GSON.toJsonTree(p));
        try {
            Files.writeString(file(), GSON.toJson(all), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("strip-progress.json not written: " + e, e);
        }
    }

    static StripOrder.Saved of(IStripProcess.Progress p) {
        return p == null ? null : new StripOrder.Saved(p.started(), p.x(), p.y(), p.z(), p.heading(), p.step(), p.length(), p.branches(), p.blocks(), p.clusters(), p.picked());
    }

    static IStripProcess.Progress of(StripOrder.Saved s) {
        return s == null ? null : new IStripProcess.Progress(s.started(), s.x(), s.y(), s.z(), s.heading(), s.step(), s.length(), s.branches(), s.blocks(), s.clusters(), s.picked());
    }

    // ---- the step's hands ----

    private static StripOrder.Saved lastSaved;
    /** 0.39.z62 item 1b: the blocks the running strip's saved progress held when it started (0: a new tunnel). */
    private static int startBlocks;
    /** 0.39.z62 review 2: the step index the running strip's saved progress held when it started (0: a new tunnel). */
    private static int startStep;

    /** 0.39.z77 spares 3: the ore counts the running strip's saved progress held when it started (empty: a new tunnel). */
    private static Map<String, Integer> startPicked = Map.of();
    /** 0.39.z77 spares 3: the running strip's yield line was said (once per strip end). */
    private static boolean yieldSaid;

    /**
     * 0.39.z77 spares 3 (manager 2026-10-26: the factor between the band's density and what a strip finds is set later from
     * measured runs): once per strip end, the cells this run dug, the ore it found per family and the price's predicted ore
     * a cell ({@link StripOrder#yieldLine}), info level. Loader notes: none (the fork's Progress values).
     */
    private static void yieldFact(Step.Strip s, StripOrder.Saved now) {
        if (yieldSaid) return;
        yieldSaid = true;
        int cells = Math.max(0, (now == null ? 0 : now.blocks()) - startBlocks);
        Map<String, Integer> found = new java.util.TreeMap<>();
        if (now != null) for (var e : now.picked().entrySet()) {
            int n = e.getValue() - startPicked.getOrDefault(e.getKey(), 0);
            if (n > 0) found.put(e.getKey(), n);
        }
        Map<String, Double> pred = StripOrder.predicted(s.key());
        java.util.Set<String> fams = new java.util.TreeSet<>(s.amounts().keySet());
        fams.addAll(pred.keySet());
        LOG.info("[entropybot] {}", StripOrder.yieldLine(s.key(), cells, found, pred, fams));
    }

    /** Starts the fork's strip, resumed from this order's saved progress; null = started, else "error: ...". */
    static String start(Step.Strip s) {
        IBaritone b = Jobs.baritone();
        if (b == null) return "error: baritone not loaded";
        Direction d = Direction.byName(s.heading());
        if (d == null) return "error: no heading " + s.heading();
        StripOrder.Saved from;
        try {
            from = load(s);
        } catch (RuntimeException e) {
            return "error: " + e.getMessage();
        }
        lastSaved = from;
        startBlocks = from == null ? 0 : from.blocks();      // 0.39.z62 item 1b: what this start resumes from
        startStep = from == null ? 0 : from.step();
        startPicked = from == null ? Map.of() : from.picked();   // 0.39.z77 spares 3: the yield line counts this run's finds
        yieldSaid = false;
        checkedBlocks = -1;
        goalCell = null;
        onNote = null;
        // 0.39.z77 strip: a family already met leaves the strip from its start (its ores out of the fork's set)
        List<String> live = StripYield.liveOres(s.ores(), s.amounts(), from == null ? Map.of() : from.picked(), StripStep::family);
        running = live.isEmpty() ? s.ores() : live;
        b.getStripProcess().probe(WalkProbe.on());          // 0.39.z64 item 1: the "probe strip:" lines (debug.walkProbe)
        // 0.39.z77 mineintent (run DB 01:08:24: "travel may place" inside a strip): the strip's searches are priced as MINE,
        // not on the walk's TRAVEL left before it; the intent before it goes back at the strip's end
        SEARCH.begin(STORE, ProbeLines.stripRefuseOn());
        try {
            b.getStripProcess().strip(new BlockOptionalMetaLookup(running.toArray(String[]::new)), s.y(), d, of(from));
        } catch (RuntimeException e) {
            SEARCH.end(STORE);
            throw e;
        }
        if (!b.getStripProcess().isActive()) {
            SEARCH.end(STORE);
            return "error: the strip did not start: " + b.getStripProcess().lastEnd();
        }
        return null;
    }

    /** 0.39.z77 mineintent: the strip's own search intent (MINE while it runs) and its refuse probe. */
    private static final io.github.mojolowjo.entropybot.guard.StripSearch SEARCH = io.github.mojolowjo.entropybot.guard.StripSearch.INSTANCE;

    /** The job intent as the strip sets and puts it back (PolicyRuntime's). */
    private static final io.github.mojolowjo.entropybot.guard.StripSearch.Store STORE = new io.github.mojolowjo.entropybot.guard.StripSearch.Store() {
        @Override public io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent intent() { return io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.intent(); }
        @Override public String by() { return io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.intentBy(); }
        @Override public void set(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent i, String by) { io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(i, by); }
    };

    /** An ore id's count key (the fork's picked counts, the order's amounts). */
    private static String family(String ore) { return StripOrder.countKey(ore, OreSpec::family); }

    /** 0.39.z77 strip: the ores the fork's strip digs for now (the families not met yet). */
    private static List<String> running = List.of();
    /** 0.39.z77 strip: the tunnel's blocks at the last yield check (-1: none since the start). */
    private static int checkedBlocks = -1;
    /** 0.39.z77 strip (b): the cell Baritone's goal named last while the strip ran ({x, y, z}; null: none seen). */
    private static int[] goalCell;

    /**
     * 0.39.z77 strip (manager's ruling 2026-10-26; run CK 16:50:31-17:00:31: iron 27 of 3 mined in 5 clusters, the strip dug on
     * for the 1 coal to the 600 s cap): a family met while the strip runs leaves it - the fork's strip is started again from
     * its progress (the same tunnel, the counts carried on) with the ores of the families left. Null: nothing to change, or
     * changed; else the failed status (the fork did not take the new set).
     */
    private static Car.Status dropMet(Step.Strip s, IStripProcess sp, StripOrder.Saved now) {
        List<String> live = StripYield.liveOres(s.ores(), s.amounts(), now.picked(), StripStep::family);
        if (live.isEmpty() || live.size() >= running.size()) return null;
        java.util.Set<String> gone = new java.util.TreeSet<>();
        for (String o : running) if (!live.contains(o)) gone.add(family(o));
        Direction d = Direction.byName(s.heading());
        sp.cancel();
        SEARCH.hold(STORE, ProbeLines.stripRefuseOn());     // 0.39.z77 mineintent: the next segment's searches are MINE too
        sp.strip(new BlockOptionalMetaLookup(live.toArray(String[]::new)), s.y(), d, of(now));
        running = live;
        LOG.info("[entropybot] strip: {} met ({}): left the strip; it digs on for {}", String.join(", ", gone),
                StripOrder.counts(s.amounts(), now.picked()), String.join(", ", live.stream().map(StripStep::family).distinct().toList()));
        if (!sp.isActive()) return Car.Status.failed("strip: the strip did not go on without the met ores: " + sp.lastEnd() + " (" + sp.status() + ")");
        return null;
    }

    /**
     * 0.39.z77 strip: the per-family yield check on a new tunnel cell ({@link StripYield#check}, the planner's facts kept for
     * this strip's key): the cells and drops of the tunnel so far against the band's prediction, the other ways' prices,
     * and a tunnel from the feet in another heading priced by the strip way's own estimate (NeedChain.stripDig: the band x
     * the areas seen along it). Null: go on.
     */
    private static StripYield.End yieldCheck(Step.Strip s, StripOrder.Saved now) {
        if (now == null || !now.started() || now.blocks() == checkedBlocks) return null;
        checkedBlocks = now.blocks();
        Map<String, StripYield.Fact> facts = StripYield.facts(s.key());
        if (facts.isEmpty()) return null;                   // an owner's strip order, or not priced this session: runs as ordered
        LocalPlayer p = Minecraft.getInstance().player;
        int[] feet = p == null ? null : Jobs.here(p);
        StripYield.HeadingCells cells = feet == null ? null : (h, fam, left) -> {
            StripYield.Fact f = facts.get(fam);
            if (f == null || !(f.perSlab() > 0)) return -1;
            List<String> ores = s.ores().stream().filter(o -> family(o).equals(fam)).toList();
            var sd = io.github.mojolowjo.entropybot.aa.NeedChain.stripDig(f.perSlab(), left, new int[]{feet[0], feet[2]}, s.y(), h, ores);
            // 0.39.z77 strip 2 (run CS 19:32:56): the same band prior as this strip, never better but for ore seen there
            return StripYield.sameBand(sd.cells(), left, sd.band(), sd.seen().ores() > 0);
        };
        StripYield.Verdict v = StripYield.verdict(s.amounts(), now.picked(), now.blocks(), StripOrder.predicted(s.key()), facts, s.heading(), cells);
        String was = onNote;
        onNote = v == null ? null : v.on();
        if (onNote != null && was == null) LOG.info("[entropybot] strip {}: {}", s.key(), onNote);
        return v == null ? null : v.end();
    }

    /** 0.39.z77 strip 3: why a dry strip digs on to the step watch (no other way priced), in its running status; null: none. */
    private static String onNote;

    private static boolean bagFull(LocalPlayer p) {
        return p != null && p.getInventory().getFreeSlot() < 0;
    }

    /**
     * The step's status: the order's end rule first (amounts met, bag full), then the fork's own end; the progress is
     * saved whenever it moved (and at every end; an order done by its amounts or the tunnel's end clears it).
     * 0.39.z77 mineintent: while it runs and no reflex holds the bot, the search intent stays MINE (a next segment, an
     * intent set meanwhile); at any end the intent before the strip goes back.
     */
    static Car.Status status(Step.Strip s, boolean holding) {
        Car.Status st;
        try {
            st = statusOf(s, holding);
        } catch (RuntimeException e) {
            SEARCH.end(STORE);
            throw e;
        }
        if (st.state() != Car.State.RUNNING) SEARCH.end(STORE);
        else if (!holding) SEARCH.hold(STORE, ProbeLines.stripRefuseOn());
        return st;
    }

    private static Car.Status statusOf(Step.Strip s, boolean holding) {
        IBaritone b = Jobs.baritone();
        if (b == null) return Car.Status.failed("baritone not loaded");
        var sp = b.getStripProcess();
        probeLines(sp);
        StripOrder.Saved now = of(sp.progress());
        try {
            if (now != null && !now.equals(lastSaved)) {
                save(s, now);
                lastSaved = now;
            }
        } catch (RuntimeException e) {
            if (sp.isActive()) sp.cancel();
            return Car.Status.failed("strip: " + e.getMessage());
        }
        Map<String, Integer> picked = now == null ? Map.of() : now.picked();
        if (sp.isActive()) {
            Car.Status end = StripOrder.endRule(s.amounts(), picked, bagFull(Minecraft.getInstance().player));
            if (end != null) {
                sp.cancel();
                yieldFact(s, now);
                ended(s, end);
                return end;
            }
            if (now != null && !holding) {                  // a reflex holding the bot: nothing restarted under it
                Car.Status bad = dropMet(s, sp, now);
                if (bad != null) {
                    yieldFact(s, now);
                    io.github.mojolowjo.entropybot.aa.moves.FactChange.strip(s.key(), bad.why());
                    return bad;
                }
                // 0.39.z77 strip: the families left go on only while this strip is their cheapest way (the planner's prices)
                StripYield.End y = yieldCheck(s, now);
                if (y != null) {
                    sp.cancel();
                    yieldFact(s, now);
                    String why = "strip: " + y.why();
                    LOG.info("[entropybot] strip {}: {} (the re-plan holds {})", s.key(), why, y.heading() ? "the heading " + s.heading() + " at y " + s.y() : "this strip's ores");
                    if (y.heading()) io.github.mojolowjo.entropybot.aa.moves.FactChange.stripYield(s.y(), s.heading(), why);
                    else io.github.mojolowjo.entropybot.aa.moves.FactChange.strip(s.key(), why);
                    return Car.Status.failed(why);
                }
            }
            goalCell = goalOf(b);
            // 0.39.z77 strip 3: Steps' "timed out after N s (<this status>)" then says why the dry strip dug on to the cap
            return Car.Status.running(sp.status() + " - " + sp.why() + (onNote == null ? "" : "; " + onNote));
        }
        if (holding) return Car.Status.running(sp.status() + " - " + sp.why());
        Car.Status st = StripOrder.endOf(sp.lastEnd(), sp.status());
        yieldFact(s, now);
        ended(s, st);
        // 0.39.z46 (run E 22:46:11 "strip: no path" parked the order): a strip that ended short is the source fact with
        // Baritone's words; the order re-plans without this band way
        if (st.state() == Car.State.FAILED) {
            // 0.39.z62 item 1b (run S 04:57:30: "strip east feet y 16: 0 blocks ... no path", the new table on the tunnel's
            // first cell): no new block since this start = the heading is blocked here, a fact for that heading at that Y
            // z62 review 2: only the fork's "no path" at the first cell after it stood at the strip's Y - a broken pickaxe, lava,
            // a danger stop or no path down never burns the heading
            if (StripOrder.noPathAtFirstCell(sp.lastEnd(), now != null && now.started(), startStep, now == null ? 0 : now.step(),
                    startBlocks, now == null ? 0 : now.blocks())) io.github.mojolowjo.entropybot.aa.moves.FactChange.stripBlocked(s.y(), s.heading(), st.why() + "; " + firstCellsRule(s));
            // 0.39.z77 strip (b) (run CJ 17:25:25: "no path (strip east feet y 16: 102 blocks ...)" - the branch's next cell 6 16 25
            // was a dripstone_block the break policy denies outside the areas, read only later as a first cell at 17:31:05): a "no
            // path" inside the tunnel says the rule of the cell Baritone's goal named
            // 0.39.z77 explore (run DC 01:37:05: the held way key dropped every heading, the order climbed and explored): the
            // fact is about this heading at this Y (FactChange.stripNoPath); the re-plan takes the next open heading
            else if (StripOrder.NO_PATH.equals(sp.lastEnd())) {
                String cell = nextCellRule(goalCell);
                int cells = now == null ? 0 : now.blocks();
                // 0.39.z77 mineintent: the intent the strip's searches ran on, beside the next cell's MINE answer
                LOG.info("[entropybot] strip {}: no path after {} cells; {}; searched as {} (the re-plan holds {})", s.key(), cells, cell,
                        io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.intent().name().toLowerCase(java.util.Locale.ROOT),
                        s.from() == null ? "the heading " + s.heading() + " at y " + s.y() : "this strip from fresh ground");
                io.github.mojolowjo.entropybot.aa.moves.FactChange.stripNoPath(s, cells, st.why() + "; " + cell);
            }
            else io.github.mojolowjo.entropybot.aa.moves.FactChange.strip(s.key(), st.why());
        }
        return st;
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("entropybot");

    /**
     * 0.39.z76 sb4 review (run AV 02:40:45-02:41:36: six strips failed "no path (0 blocks)", the log had no reason): the break
     * policy's answer for the strip's first cells from the feet at the strip's Y - the verdict with its rule row and cost
     * multiplier, and the block facts ("fence why" without the rule-set line) - for the blocked-heading fact
     * ({@link StripOrder#firstCells}). Loader notes: vanilla Minecraft.player / level; the mod's PolicyRuntime.
     */
    static String firstCellsRule(Step.Strip s) {
        // sb4 review 2: the whole read is guarded (the feet, the dimension, the heading's direction can throw too), so the
        // blocked-heading fact is never lost for want of its detail
        return StripOrder.guardedFirstCells(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            var lv = mc.level;
            if (p == null || lv == null) return "first cells: the rule not known (no player or level)";
            int[] f = Jobs.here(p);
            String dim = io.github.mojolowjo.entropybot.guard.Guard.dimOf(lv);
            return StripOrder.firstCells(new int[]{f[0], s.y(), f[2]}, s.heading(), c -> {
                String w = io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.why(lv, dim, c[0], c[1], c[2],
                        io.github.mojolowjo.entropybot.guard.BreakPolicy.Action.BREAK, io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.MINE);
                return w == null ? null : w.replaceFirst(" \\| rule set .*? \\| block ", " | block ");
            });
        });
    }

    /** 0.39.z77 strip (b): the cell of Baritone's goal while the strip runs ({x, y, z}), the last one kept when none is set. */
    private static int[] goalOf(IBaritone b) {
        try {
            if (b.getPathingBehavior().getGoal() instanceof baritone.api.pathing.goals.GoalBlock g) {
                var at = g.getGoalPos();
                return new int[]{at.getX(), at.getY(), at.getZ()};
            }
        } catch (RuntimeException e) {
            Quiet.warn("StripStep.goalOf: Baritone's goal not read (the next cell of a no-path end not known)", e);
        }
        return goalCell;
    }

    /**
     * 0.39.z77 strip (b): the break policy's answer for the cell Baritone's goal named last and the one above it (feet and
     * head), as {@link #firstCellsRule} reads the first cells; "the next cell not known" when no goal cell was seen.
     * Loader notes: vanilla Minecraft.level; the mod's PolicyRuntime.
     */
    static String nextCellRule(int[] cell) {
        if (cell == null) return "the next cell not known (no goal cell seen while the strip ran)";
        return "the next cell (Baritone's goal) " + StripOrder.guardedFirstCells(() -> {
            Minecraft mc = Minecraft.getInstance();
            var lv = mc.level;
            if (lv == null) return "first cells: the rule not known (no level)";
            String dim = io.github.mojolowjo.entropybot.guard.Guard.dimOf(lv);
            List<String> out = new java.util.ArrayList<>();
            for (int dy = 0; dy < IStripProcess.TUNNEL_HEIGHT; dy++) {
                String w = io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.why(lv, dim, cell[0], cell[1] + dy, cell[2],
                        io.github.mojolowjo.entropybot.guard.BreakPolicy.Action.BREAK, io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.MINE);
                out.add(cell[0] + " " + (cell[1] + dy) + " " + cell[2] + ": " + (w == null ? "not known" : w.replaceFirst(" \\| rule set .*? \\| block ", " | block ")));
            }
            return "first cells: " + String.join("; ", out);
        }).replaceFirst("^first cells: ", "");
    }

    /**
     * 0.39.z64 item 1 (run U: 251 strip cells at y 16, "0 clusters mined", no "strip: cluster at" line): the fork's probe
     * lines, one "[entropybot] probe strip:" line per new tunnel cell (its solid faces, the ore-tagged ones and whether the
     * strip's ore set matched them) while debug.walkProbe is on; the probe follows the switch while the strip runs.
     */
    private static void probeLines(IStripProcess sp) {
        boolean on = WalkProbe.on();
        sp.probe(on);
        java.util.List<String> lines = sp.drainProbe();
        areaFacts(sp);
        if (!on) return;
        for (String l : lines) LOG.info("[entropybot] probe strip: {}", l);
    }

    // ---- 0.39.z64 item 4: the areas' seen faces and ores (StripAreas), per world in strip-areas.json ----

    /** The world file the facts were read from (a world change reads again). */
    private static String areasFile;

    private static Path areasPath() {
        var f = Core.INSTANCE.files();
        if (f == null) throw new IllegalStateException("no world files (not in a world)");
        return f.worldRoot().resolve("strip-areas.json");
    }

    /** z64 review 1: "no world files" was said for this stretch without a world (said once, again after a world was had). */
    private static boolean noFilesSaid;
    /** z64 review 2: this world's file was unreadable and kept as .bad: nothing is written over it this session. */
    private static boolean areasBad;

    /**
     * The facts of this world loaded into StripAreas (once per world); false: no world files. Review 1 (rule 15): with no
     * world files, or on a world change, the facts held are cleared first - a price never uses another world's areas; the
     * "no world files" warning is said once until a world is had again. Review 2: an unreadable strip-areas.json is renamed
     * to strip-areas.json.bad (a second one gets the time in its name) and this world's facts start empty; damaged entries
     * (null from GSON) are skipped.
     */
    static boolean areasLoaded() {
        Path p;
        try {
            p = areasPath();
        } catch (RuntimeException e) {
            if (areasFile != null) io.github.mojolowjo.entropybot.aa.brain.StripAreas.clear();
            areasFile = null;
            areasBad = false;
            if (!noFilesSaid) Quiet.warn("StripStep.areasLoaded: no world files: the strip areas' yield is not known (the band's estimate alone)", e);
            noFilesSaid = true;
            return false;
        }
        noFilesSaid = false;
        String where = p.toString();
        if (where.equals(areasFile)) return true;
        io.github.mojolowjo.entropybot.aa.brain.StripAreas.clear();      // a world change: the old world's facts go first
        areasBad = false;
        Map<String, io.github.mojolowjo.entropybot.aa.brain.StripAreas.Seen> m = new LinkedHashMap<>();
        try {
            if (Files.exists(p)) {
                JsonObject all = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
                int skipped = 0;
                for (var e : all.entrySet()) {
                    var s = e.getValue() == null || e.getValue().isJsonNull() ? null : GSON.fromJson(e.getValue(), io.github.mojolowjo.entropybot.aa.brain.StripAreas.Seen.class);
                    if (e.getKey() == null || s == null) { skipped++; continue; }
                    m.put(e.getKey(), new io.github.mojolowjo.entropybot.aa.brain.StripAreas.Seen(s.faces(), s.ores()));
                }
                if (skipped > 0) LOG.info("[entropybot] strip areas: {} empty entr(ies) in strip-areas.json skipped", skipped);
            }
        } catch (Exception e) {
            m.clear();
            areasBad = true;
            Path bad = p.resolveSibling("strip-areas.json.bad");
            if (Files.exists(bad)) bad = p.resolveSibling("strip-areas.json.bad-" + System.currentTimeMillis());
            try {
                Files.move(p, bad);
                Quiet.warn("StripStep.areasLoaded: strip-areas.json unreadable, kept as " + bad.getFileName() + ": this world's area facts start empty", e);
                areasBad = false;                                        // the bad file is out of the way: new facts may be written
            } catch (Exception moveFailed) {
                Quiet.warn("StripStep.areasLoaded: strip-areas.json unreadable and not renamed (" + moveFailed + "): it is not written over this session; the facts start empty", e);
            }
        }
        io.github.mojolowjo.entropybot.aa.brain.StripAreas.load(m);
        areasFile = where;
        LOG.info("[entropybot] strip areas: {} area(s) of seen strip faces read for this world", m.size());
        return true;
    }

    /** The fork's cell reads since the last tick into StripAreas, written when any came. */
    private static void areaFacts(IStripProcess sp) {
        java.util.List<IStripProcess.CellRead> reads = sp.drainCells();
        if (reads.isEmpty() || !areasLoaded()) return;
        for (var r : reads) io.github.mojolowjo.entropybot.aa.brain.StripAreas.add(r.x(), r.y(), r.z(), r.faces(), r.ores());
        if (areasBad) return;                                            // review 2: the unreadable file is never written over
        try {
            Files.writeString(areasPath(), GSON.toJson(io.github.mojolowjo.entropybot.aa.brain.StripAreas.all()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Quiet.warn("StripStep.areaFacts: strip-areas.json not written (the facts stay for this session)", e);
        }
    }

    /**
     * A strip job ended: met (amounts, the tunnel's end) -> the order is done, its progress and deposits cleared; the bag
     * full -> the progress kept and where the bag filled saved (the walk back's target); the deposit leg follows.
     */
    private static void ended(Step.Strip s, Car.Status st) {
        if (st.state() != Car.State.DONE) return;
        if (StripOrder.orderMet(st.why())) {
            io.github.mojolowjo.entropybot.aa.brain.Goals.stripDone(s);
            save(s, null);
            saveDeposits(s, null);
            return;
        }
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) saveDeposits(s, deposits(s).from(Jobs.here(p)));
    }

    // ---- the deposits (per world, beside the progress, key + "|deposits") ----

    static io.github.mojolowjo.entropybot.aa.brain.DepositRule.Deposits deposits(Step.Strip s) {
        JsonObject all = readAll();
        String k = s.key() + "|deposits";
        return all.has(k) ? GSON.fromJson(all.get(k), io.github.mojolowjo.entropybot.aa.brain.DepositRule.Deposits.class)
                : io.github.mojolowjo.entropybot.aa.brain.DepositRule.Deposits.NONE;
    }

    static void saveDeposits(Step.Strip s, io.github.mojolowjo.entropybot.aa.brain.DepositRule.Deposits d) {
        savedKeys = null;
        JsonObject all = readAll();
        String k = s.key() + "|deposits";
        if (d == null) all.remove(k);
        else all.add(k, GSON.toJsonTree(d));
        try {
            Files.writeString(file(), GSON.toJson(all), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("strip-progress.json not written: " + e, e);
        }
    }

    /** The order met (Goals' done list): StepHands' deposit leg and walk back pass. */
    static boolean met(Step.Strip s) {
        return io.github.mojolowjo.entropybot.aa.brain.Goals.stripMet(s);
    }

    /** aa why / aa status: the strip's line while it runs, else "". */
    static String liveLine() {
        IBaritone b = Jobs.baritone();
        if (b == null || !b.getStripProcess().isActive()) return "";
        return "\nstrip: " + b.getStripProcess().status() + " - " + b.getStripProcess().why();
    }

    static void cancel() {
        IBaritone b = Jobs.baritone();
        if (b != null && b.getStripProcess().isActive()) b.getStripProcess().cancel();
        SEARCH.end(STORE);                                  // 0.39.z77 mineintent: the intent before the strip back
    }

    /** Wires aa goals' progress lookup (Goals.line) to the saved file. */
    static void wire() {
        io.github.mojolowjo.entropybot.aa.brain.Goals.stripProgress = StripStep::load;
        io.github.mojolowjo.entropybot.aa.brain.Goals.stripDeposits = StripStep::deposits;
    }
}
