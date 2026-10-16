package io.github.mojolowjo.entropybot.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.IPlayerController;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.api.BotAPI;
import io.github.mojolowjo.entropybot.clear.Bot;
import io.github.mojolowjo.entropybot.clear.ClearBox;
import io.github.mojolowjo.entropybot.clear.ClearEngine;
import io.github.mojolowjo.entropybot.clear.ClearJob;
import io.github.mojolowjo.entropybot.clear.ClearRules;
import io.github.mojolowjo.entropybot.clear.ClearRun;
import io.github.mojolowjo.entropybot.clear.ClearWorld;
import io.github.mojolowjo.entropybot.clear.LeaseSet;
import io.github.mojolowjo.entropybot.clear.McClearWorld;
import io.github.mojolowjo.entropybot.clear.OreBook;
import io.github.mojolowjo.entropybot.clear.OreTally;
import io.github.mojolowjo.entropybot.clear.PlaceRules;
import io.github.mojolowjo.entropybot.clear.Pos;
import io.github.mojolowjo.entropybot.clear.Tools;
import io.github.mojolowjo.entropybot.engine.Hotbar;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.gui.GuiCore;
import io.github.mojolowjo.entropybot.storage.StorageRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * B7d contract (2026-10-03). D1 (dig/build/place) implements this class; D2 (strip mine) and D3 (caves, explore,
 * mine) only build steps with the two factories and read {@link Outcome} from the step after it ran. The public
 * surface below (factory signatures, Step fields {@code clear}/{@code cleared}/{@code id}/{@code pos}, the Outcome
 * record) is fixed; D1 may add to it but not change it.
 *
 * <p>Step semantics: a "clear" step runs one careful clear of {@code opts.box} (or {@code opts.only}) the way
 * startClear did (leases, reach, sight, torches, drops, pickaxe restock by a spliced craft, fights and meals hold it),
 * stores the result in {@code step.cleared} and returns "next" when it ended normally (everything cleared, or what is
 * left was left for a reason the report names); it returns an error text (the Seq ends with it) only when it could not
 * run at all or {@code opts.mustFinish} was set and blocks are left. A "placeblock" step places one {@code step.id}
 * at {@code step.pos} (walking into reach first, taking a place lease, never breaking anything) and returns "next", or
 * an error text.
 *
 * <p>D1's additions (2026-10-03): {@link #placeStep(int[], String, int[], boolean)} (a stand spot and optional, the
 * strip mine's hole fills), {@link #oreTally()} / {@link #talliedSince} (the bridge's global oreTally, for "mine ...
 * until N ores"), {@link #oreBook()} (the ores listed for players, in the knowledge files), {@link #placeAt} (the
 * bridge's placeAt). The game-free run is {@link ClearRun}; the rules for placing and the table are {@link PlaceRules}.
 * A clear step is stepped every tick (Seq.everyTick); a step a clear spliced in (pickaxe craft, deposit trip, the table)
 * that fails comes back to the clear (Seq.tick -> {@link #caught}), which reports it the bridge's way; the job's end
 * however it comes (stop, an error, a fight) releases the clear's leases ({@link #ended}).
 */
public final class Clearing {
    private static final Logger LOG = LogUtils.getLogger();

    private Clearing() {}

    /** What a clear step did; set on the step when it ends. */
    public record Outcome(boolean ok, int broken, int left, Map<String, Integer> mined, List<String> ores,
            String message) {}

    public static Seq.Step clearStep(ClearJob.Options opts) {
        Seq.Step s = new Seq.Step("clear");
        s.clear = opts;
        return s;
    }

    public static Seq.Step placeStep(int[] pos, String item) {
        Seq.Step s = new Seq.Step("placeblock");
        s.pos = pos;
        s.id = item;
        return s;
    }

    /**
     * D1: a placement from a given stand spot (null: wherever reaches, walking there when nothing does); optional: a
     * placement that can't be done (no item, nothing to click, it didn't stay) is skipped instead of failing the job.
     */
    public static Seq.Step placeStep(int[] pos, String item, int[] from, boolean optional) {
        Seq.Step s = placeStep(pos, item);
        PlaceState ps = new PlaceState();
        ps.from = from;
        ps.optional = optional;
        s.optional = optional;
        s.state = ps;
        return s;
    }

    /** Step kinds (in {@code Step.kind}) only this class reads. */
    static final String KIND_FINISH = "finish", KIND_BUILD = "build", KIND_TABLE = "tablepickup", KIND_PLACED_TABLE = "placedtable";

    /** A clear step whose end ends the job with the clear's own report (the dig and build clear verbs). */
    static Seq.Step finishingClearStep(ClearJob.Options opts) {
        Seq.Step s = clearStep(opts);
        s.kind = KIND_FINISH;
        return s;
    }

    // ---- the ore tally and the listed ores ----

    /** The bridge's oreTally: ore block id -> mined this session by every collecting clear (a copy; {@link OreTally}). */
    public static Map<String, Integer> oreTally() {
        return OreTally.copy();
    }

    /** talliedSince: ores matching {@code match} mined since {@code start} (a copy of {@link #oreTally()}). */
    public static int talliedSince(Map<String, Integer> start, java.util.function.Predicate<String> match) {
        return OreTally.since(start, match);
    }

    static void addTally(Map<String, Integer> mined) {
        OreTally.add(mined);
    }

    /**
     * Where a clear lists the ores it leaves and forgets the ones it mines: D3's book (cave/MineNotes, ores.json, the
     * PM "ores" list) - the one owner of ores.json since the B7d merge (D1's Knowledge copy was dropped there).
     */
    public static volatile java.util.function.Supplier<OreBook> ORE_BOOK = () -> Mining.get().oreBook();

    /** The ores left in place for players (whichever book {@link #ORE_BOOK} names). */
    public static OreBook oreBook() {
        return ORE_BOOK.get();
    }

    /** The book, plus the ores this clear listed (Outcome.ores). */
    static final class KnowledgeOres implements OreBook {
        final List<String> noted = new ArrayList<>();
        final OreBook book = oreBook();

        @Override public boolean note(int x, int y, int z, String name) {
            boolean added = book.note(x, y, z, name);
            if (added) noted.add(Pos.key(x, y, z));
            return added;
        }

        @Override public boolean forget(String key) {
            return book.forget(key);
        }
    }

    // ---- the per-step state ----

    /** A clear step's state (st.state). */
    static final class ClearState {
        ClearRun run;
        ClearJob job;
        final LiveWorld world = new LiveWorld();
        KnowledgeOres ores;
        LeaseSet leases = newLeases();
        boolean done;
        /** A trip runs in the steps before this one (spliced by this clear, or the restock before a big dig). */
        boolean inTrip;
        String tripKind, caught;
        boolean caughtSet;
        long lastBeat = -1000;
        /** the job's label for the trips' whispers, the requester */
        String note;
        /** table pickups a failed trip took out of the Seq (B7d review 3): the job's end names a table still there */
        final List<Seq.Step> tablesLeft = new ArrayList<>();
        /** B7e F: where the bot stood (x, z) when the clear began: "junk drop" throws toward it (the dug side) */
        double[] start;
    }

    /** A placeblock step's state. */
    static final class PlaceState {
        int[] from;
        boolean optional, clicked;
        String stage;
        long stageTick;
        int tries;
        /** 0.30.6: walks to a fresh stand spot after a "nothing in reach" click (once) */
        int walks;
        /** 0.30.7: the tick a mob was first found in the cell (-1: none); it is waited for PlaceRules.MOB_WAIT_TICKS */
        long mobSince = -1;
        LeaseSet leases;
        /** the table pickup to arm once the table really went down (18a) */
        Seq.Step pickup;
        /** water plan: a block sealing water off at the edge of this dig box (a seal lease when the cell is outside the areas) */
        ClearBox seal;
    }

    /** A Baritone build step's state (build floor|walls|fill|shell). */
    static final class BuildState {
        final LeaseSet leases = newLeases();
        long start;
        String status;
        boolean done;
        // 0.30.1 (package 4): the gave-up watch and the rim placer
        String shape, id, area;
        List<io.github.mojolowjo.entropybot.clear.Pos> cells = List.of();
        int startMissing, lastMissing, mine;
        long lastChange;
        boolean rim;
        Boolean savedRemain;
        /** 0.30.4: Baritone's maxFallHeightNoWater before the build (raised to BUILD_FALL so the bot gets off a wall top). */
        Integer savedFall;
        Double savedRandom, savedRandom113;
        final Map<io.github.mojolowjo.entropybot.clear.Pos, Integer> tries = new HashMap<>();
        final Map<io.github.mojolowjo.entropybot.clear.Pos, io.github.mojolowjo.entropybot.clear.RimPlan.Why> gaveUp = new HashMap<>();
        io.github.mojolowjo.entropybot.clear.RimPlan.Pick pick;
        String stage;
        long stageTick, layerStart;
        int layerY = Integer.MIN_VALUE;
        // review 1: the stall clock runs from the first placement or once I am near the zone (the walk there never counts)
        ClearBox box;
        boolean clockOn;
        // review 4: the missing cells, recounted in pick/check and every 20 ticks otherwise
        List<Pos> missing;
        long missingAt = -1;
        // (8) the block for the current pick (a filler under a hole, else the build block); (9) the not-moving watch
        String placeId;
        long lastFeet = Long.MIN_VALUE, stillSince;
        // 0.30.2 (trial 8 snow): a refused click into a cell holding a no-collision block (a snow layer) breaks it first
        boolean clearing, clearStarted;
    }

    /** True while a mod job runs Baritone's builder (the bridge's safety net leaves placing on then). */
    static volatile boolean placingOwned;

    public static boolean placingOwned() { return placingOwned; }

    /** McClearWorld over the current level, swapped each tick (a respawn or a new level never leaves a stale one). */
    static final class LiveWorld implements ClearWorld {
        McClearWorld cur;

        void set(LocalPlayer p) {
            Minecraft mc = Minecraft.getInstance();
            cur = new McClearWorld(mc.level, p, avoidPredicate());
        }

        @Override public String id(int x, int y, int z) { return cur.id(x, y, z); }
        @Override public String name(int x, int y, int z) { return cur.name(x, y, z); }
        @Override public boolean air(int x, int y, int z) { return cur.air(x, y, z); }
        @Override public boolean fluid(int x, int y, int z) { return cur.fluid(x, y, z); }
        @Override public String fluidKind(int x, int y, int z) { return cur.fluidKind(x, y, z); }
        @Override public FluidCell fluidCell(int x, int y, int z) { return cur.fluidCell(x, y, z); }
        @Override public boolean replaceable(int x, int y, int z) { return cur.replaceable(x, y, z); }
        @Override public boolean blockEntity(int x, int y, int z) { return cur.blockEntity(x, y, z); }
        @Override public boolean unbreakable(int x, int y, int z) { return cur.unbreakable(x, y, z); }
        @Override public boolean builtBlock(int x, int y, int z) { return cur.builtBlock(x, y, z); }
        @Override public boolean avoided(int x, int y, int z) { return cur.avoided(x, y, z); }
        @Override public boolean ore(int x, int y, int z) { return cur.ore(x, y, z); }
        @Override public boolean noCollision(int x, int y, int z) { return cur.noCollision(x, y, z); }
        @Override public boolean loaded(int x, int y, int z) { return cur.loaded(x, y, z); }
        @Override public boolean fullBlock(int x, int y, int z) { return cur.fullBlock(x, y, z); }
        @Override public double collisionHeight(int x, int y, int z) { return cur.collisionHeight(x, y, z); }
        @Override public int blockLight(int x, int y, int z) { return cur.blockLight(x, y, z); }
        @Override public Hit clip(double fx, double fy, double fz, double tx, double ty, double tz) { return cur.clip(fx, fy, fz, tx, ty, tz); }
    }

    @SuppressWarnings("unchecked")
    static java.util.function.Predicate<Block> avoidPredicate() {
        try {
            List<Block> l = BaritoneAPI.getSettings().blocksToAvoidBreaking.value;
            return l == null || l.isEmpty() ? null : l::contains;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- Seq hooks ----

    /** Seq.everyTick: a clear breaks every tick. */
    static boolean everyTick(Seq.Step st) {
        return st.type.equals("clear") && !KIND_BUILD.equals(st.kind);
    }

    /**
     * Seq.tick, before an error ends the job: a step of a trip a clear spliced in failed. The rest of the trip goes,
     * the clear hears how it ended and carries on (the bridge's finishJob -> resume). False when no clear waits on it.
     */
    static boolean caught(Seq seq, String r) {
        if (BuilderDig.caught(seq, r)) return true;             // 0.29.0: a trip of a dig via baritone
        for (int j = seq.idx + 1; j < seq.steps.size(); j++) {
            Seq.Step c = seq.steps.get(j);
            if (!c.type.equals("clear") || !(c.state instanceof ClearState s) || !s.inTrip) continue;
            List<Seq.Step> removed = new ArrayList<>(seq.steps.subList(seq.idx, j));
            // B7d review 3: the armed table pickup stays (the bot takes its table back before the clear goes on)
            List<Seq.Step> keep = PlaceRules.keptOnCatch(removed, Clearing::armedPickup);
            for (int k = seq.idx; k < j; k++) seq.steps.remove(seq.idx);
            seq.steps.addAll(seq.idx, keep);
            LocalPlayer p = Minecraft.getInstance().player;
            for (Seq.Step g : removed) {
                if (keep.contains(g)) continue;
                // a removed armed pickup (the one that failed), or a table that went down after a failed placement's
                // click: the job's end reports it when it is still there
                if (armedPickup(g)) s.tablesLeft.add(g);
                if (g.state instanceof PlaceState ps && ps.pickup != null && ps.clicked) s.tablesLeft.add(ps.pickup);
                if (g.state instanceof ClearState gs && !gs.done) {
                    gs.done = true;
                    if (gs.run != null && p != null) {
                        try { gs.run.stop(new McBody(seq, gs, p)); } catch (RuntimeException ignored) {}
                    }
                    gs.leases.releaseAll();
                }
            }
            if (!keep.isEmpty()) LOG.info("[entropybot] clear: picking up my crafting table at {} first", Jobs.fmt(keep.get(0).pos));
            // B7d review 6: a walk the failed trip left running stops before the clear breaks again
            IBaritone b = Jobs.baritone();
            if (b != null) Jobs.cancel(b);
            s.caught = r;
            s.caughtSet = true;
            seq.stage = null;
            seq.stepStart = seq.now();
            if (p != null && (Gui.open(p) || Minecraft.getInstance().screen != null)) Gui.close(p);
            LOG.info("[entropybot] clear: the {} trip failed ({}), back to the clear", s.tripKind, r);
            return true;
        }
        return false;
    }

    /**
     * Jobs.finish: the job ended (any way): breaking stops, the leases go, the tally counts. A crafting table the bot put
     * down and hasn't picked up yet (B7d review 3) is named in the end message: msg plus " - left my crafting table at x y
     * z". It is never broken here: a table takes several ticks of hitting even with the best axe, and the job must not wait
     * or walk once it has ended. Returns the end message.
     */
    static String ended(Seq seq, String msg) {
        LocalPlayer p = Minecraft.getInstance().player;
        List<Seq.Step> tables = new ArrayList<>();
        for (Seq.Step st : seq.steps) {
            if (st.state instanceof ClearState s && !s.done) {
                s.done = true;
                if (s.run != null && p != null) {
                    try { s.run.stop(new McBody(seq, s, p)); } catch (RuntimeException ignored) {}
                }
                if (s.job != null) addTally(s.job.oreTally);
                s.leases.releaseAll();
            } else if (st.state instanceof PlaceState ps) {
                if (ps.leases != null) ps.leases.releaseAll();
                // clicked on a free cell, then the job ended before the table showed up (or before the step saw it)
                if (ps.pickup != null && ps.clicked) tables.add(ps.pickup);
            } else if (st.state instanceof BuildState bs && !bs.done) {
                endBuild(bs);
            } else if (st.state instanceof BuilderDig.State ds) {
                BuilderDig.ended(ds);                   // 0.29.0: the builder off, its settings back, its leases
            } else if (st.state instanceof FloorSteps.FloorState fs) {
                FloorSteps.ended(fs);                   // B7e F: the fill's place leases
            }
            if (st.state instanceof ClearState s) tables.addAll(s.tablesLeft);
            if (armedPickup(st)) tables.add(st);
        }
        Set<String> left = new LinkedHashSet<>();
        for (Seq.Step t : tables) {
            if (t.pos == null) continue;
            String id = blockId(t.pos);
            t.got = null;                            // disarmed: nothing picks it up after the job
            // gone (picked up, broken by someone) or something else now: not ours to mention
            if (id != null && !PlaceRules.TABLE_ID.equals(id)) continue;
            left.add(Jobs.fmt(t.pos));
            if (WHY_OWN_TABLE.equals(t.why)) LEFT_OWN_TABLES.add(Jobs.fmt(t.pos));       // 0.28.2: a check finding
        }
        if (left.isEmpty()) return msg;
        String where = String.join(", ", left);
        LOG.info("[entropybot] clear: the job ended ({}) and left my crafting table at {}", msg, where);
        return msg + " - left my crafting table at " + where;
    }

    private static String join(String a, String b) {
        return a == null || a.isEmpty() ? b : a + "; " + b;
    }

    /** A table pickup step the place step armed (the bot's own table went down there). */
    static boolean armedPickup(Seq.Step st) {
        return KIND_TABLE.equals(st.kind) && KIND_PLACED_TABLE.equals(st.got);
    }

    /** The block id at pos ("minecraft:crafting_table"), or null when no world (or not that chunk) is loaded. */
    static String blockId(int[] pos) {
        var level = Minecraft.getInstance().level;
        if (level == null || pos == null) return null;
        BlockPos bp = new BlockPos(pos[0], pos[1], pos[2]);
        if (!level.isLoaded(bp)) return null;
        BlockState bs = level.getBlockState(bp);
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString();
    }

    // ---- the steps ----

    /** Seq hands "clear" and "placeblock" steps here. */
    static String step(Seq seq, Seq.Step st, LocalPlayer p, long elapsed) {
        if (st.type.equals("placeblock")) return placeRun(seq, st, p, elapsed);
        if (KIND_BUILD.equals(st.kind)) return buildRun(seq, st, p, elapsed);
        if (FloorSteps.KIND_FLOOR.equals(st.kind)) return FloorSteps.floorRun(seq, st, p);       // B7e F
        if (FloorSteps.KIND_JUNK.equals(st.kind)) return FloorSteps.junkRun(seq, st, p);
        if (WaterSteps.KIND_WATER.equals(st.kind)) return WaterSteps.waterRun(seq, st, p);          // water plan
        if (WaterSteps.KIND_DRAIN.equals(st.kind)) return WaterSteps.drainRun(seq, st, p);
        if (BuilderDig.KIND.equals(st.kind)) return BuilderDig.run(seq, st, p);                  // 0.29.0: dig ... via baritone
        return clearRun(seq, st, p);
    }

    /**
     * startClear: the run and its leases; the zone when no box is given. Null when it can start, else the error
     * ("error: ..."). The dig and build verbs call it at once (so the reply can say why not); a clear step from D2/D3
     * at its first tick.
     */
    static String prepare(Seq.Step st, LocalPlayer p, Commands c) {
        ClearJob.Options opts = st.clear;
        IBaritone b = Jobs.baritone();
        if (b == null) return "error: baritone not loaded";
        Jobs.cancel(b);
        Jobs.safeSettings();                // Baritone only walks: no breaking, no placing
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || Gui.open(p)) Gui.close(p);
        ClearBox zone = null;
        if (opts.box == null && (opts.only == null || opts.only.isEmpty())) {
            zone = DigCommands.zoneBox(c);
            if (zone == null) return DigCommands.NO_ZONE;
        }
        ClearState s = st.state instanceof ClearState cs ? cs : new ClearState();
        s.world.set(p);
        if (s.start == null) s.start = new double[]{p.getX(), p.getZ()};
        s.ores = new KnowledgeOres();
        s.job = ClearJob.start(opts, zone, s.ores);
        s.job.standOk = standCheck(c);
        s.run = new ClearRun(s.world, s.job);
        s.leases.releaseAll();
        s.leases = newLeases();
        String le = takeLeases(s);
        if (le != null) {
            s.leases.releaseAll();
            return le;
        }
        st.state = s;
        return null;
    }

    private static String clearRun(Seq seq, Seq.Step st, LocalPlayer p) {
        Commands c = seq.jobs.core().commands;
        // the table pickup (18a): only the exact table it put down, and only while it is still there
        if (KIND_TABLE.equals(st.kind) && !(st.state instanceof ClearState) && !tablePickupWanted(st, p)) {
            st.cleared = new Outcome(true, 0, 0, Map.of(), List.of(), "ok: no crafting table of mine to pick up");
            return "next";
        }
        if (!(st.state instanceof ClearState s0) || s0.run == null) {
            String err = prepare(st, p, c);
            if (err != null) return err.replaceFirst("^error: ", "");
            ClearState s = (ClearState) st.state;
            seq.setStatus(s.job.status("starting"));
            return "wait";
        }
        ClearState s = s0;
        if (s.done) return "next";
        s.world.set(p);
        McBody body = new McBody(seq, s, p);
        if (KIND_TABLE.equals(st.kind)) {
            // B7d review 4a: every tick of the pickup (walks, holds and restarts included) it is still our table
            String lost = pickupLost(st);
            if (lost != null) {
                LOG.info("[entropybot] clear: dropped the crafting table pickup: {}", lost);
                st.got = null;
                return finish(seq, st, s, body, "ok: " + lost);
            }
        }
        ClearRun.Out out;
        if (s.inTrip) {
            // back from a trip (the steps before this one ran)
            String how = s.caughtSet ? s.caught : null;
            String kind = s.tripKind;
            s.inTrip = false;
            s.caught = null;
            s.caughtSet = false;
            s.tripKind = null;
            String le = checkLeases(s, true);
            if (le != null) return finish(seq, st, s, body, ClearEngine.finishMessage(s.world, s.job, "stopped: " + le.replaceFirst("^error: ", "")));
            if ("restock".equals(kind)) {
                if (how != null) {
                    LOG.info("[entropybot] clear: restocking pickaxes failed ({}), digging with what I have", how);
                    body.whisper("couldn't restock pickaxes (" + how.replaceFirst("^error: ", "") + ") - digging with what I have");
                }
                out = ClearRun.Out.RUN;
            } else {
                out = s.run.resumed(body, how);
            }
            if (out.kind() == ClearRun.Kind.END) return finish(seq, st, s, body, out.text());
            return "wait";
        }
        long now = seq.now();
        if (now - s.lastBeat >= 20) {
            s.lastBeat = now;
            String le = checkLeases(s, true);
            if (le != null) return finish(seq, st, s, body, ClearEngine.finishMessage(s.world, s.job, "stopped: " + le.replaceFirst("^error: ", "")));
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || Gui.open(p)) Gui.close(p);
        out = s.run.tick(body);
        switch (out.kind()) {
            case RUN: return "wait";
            case END: return finish(seq, st, s, body, out.text());
            case CRAFT:
            case STONE: return craftTrip(seq, st, s, body, p, out);
            case DEPOSIT: return depositTrip(seq, st, s, body, p, out);
            default: return "wait";
        }
    }

    /** The clear ended: the outcome on the step, the leases released, the report (the verb's job ends with it). */
    private static String finish(Seq seq, Seq.Step st, ClearState s, McBody body, String msg) {
        s.done = true;
        s.run.stop(body);
        s.leases.releaseAll();
        addTally(s.job.oreTally);
        int left = s.job.lastScanLeft != null ? s.job.lastScanLeft : 0;
        // water plan: "dig ... water" stopped by water: a water round comes next (it ends the job itself when it can't)
        if (WaterSteps.wants(st, msg)) {
            st.cleared = new Outcome(false, s.job.broken, left, new LinkedHashMap<>(s.job.oreTally), new ArrayList<>(s.ores.noted), msg);
            LOG.info("[entropybot] clear: {} - looking at the water", msg);
            seq.splice(seq.idx + 1, List.of(WaterSteps.step(st, s.job)));
            return "next";
        }
        msg = WaterSteps.endText(st, s.job, msg);
        boolean ok = msg.startsWith("ok");
        st.cleared = new Outcome(ok, s.job.broken, left, new LinkedHashMap<>(s.job.oreTally), new ArrayList<>(s.ores.noted), msg);
        LOG.info("[entropybot] clear: {}", msg);
        if (KIND_TABLE.equals(st.kind)) {
            // picked up (or gone): disarmed; still standing there: stays armed, so the job's end names it
            String id = blockId(st.pos);
            if (id != null && !PlaceRules.TABLE_ID.equals(id)) {
                if (WHY_OWN_TABLE.equals(st.why) && st.got != null) seq.note = join(seq.note, io.github.mojolowjo.entropybot.craft.OwnTable.takenBack(Jobs.fmt(st.pos)));
                st.got = null;
            } else if (armedPickup(st)) {
                LOG.info("[entropybot] clear: couldn't pick up my crafting table at {}", Jobs.fmt(st.pos));
                if (WHY_OWN_TABLE.equals(st.why)) {
                    LEFT_OWN_TABLES.add(Jobs.fmt(st.pos));
                    seq.note = join(seq.note, io.github.mojolowjo.entropybot.craft.OwnTable.leftAt(Jobs.fmt(st.pos)));
                    st.got = null;              // named in the note already
                }
            }
            if (WHY_OWN_TABLE.equals(st.why)) return "next";
        }
        if (KIND_FINISH.equals(st.kind)) {
            seq.jobs.finish(msg);
            return "wait";
        }
        if (!ok && s.job.mustFinish) return msg.replaceFirst("^stopped: ", "");
        return "next";
    }

    // ---- trips ----

    /** Pickaxes (noTool) or the stone ones (package B): the first craft text that can start, with a table if needed. */
    private static String craftTrip(Seq seq, Seq.Step st, ClearState s, McBody body, LocalPlayer p, ClearRun.Out out) {
        Crafting crafting = seq.storage.crafting;
        String lastErr = "", chosen = null;
        List<Seq.Step> add = null;
        for (String text : out.crafts()) {
            Crafting.Prepared r = crafting.prepareCraft(p, text, null);
            if (r.err() != null) {
                lastErr = r.err();
                continue;
            }
            chosen = text;
            add = new ArrayList<>(r.steps());
            for (Seq.Step a : add) a.direct = false;
            break;
        }
        if (chosen == null) {
            if (out.kind() == ClearRun.Kind.STONE) {
                LOG.info("[entropybot] clear: no stone pickaxes and making them failed ({}), going on with what I have", lastErr);
                return "wait";
            }
            ClearRun.Out end = s.run.craftNotStarted(body, lastErr);
            return finish(seq, st, s, body, end.text());
        }
        // 18a: the 3x3 recipe wants a table: put one down here when the nearest is far away and it has wood
        List<Seq.Step> withTable = tableTrip(seq, s, p, chosen);
        if (withTable != null) add = withTable;
        splice(seq, s, add, out.kind() == ClearRun.Kind.STONE ? "stone" : "craft");
        s.run.tripStarted(out.kind());
        body.whisper(out.kind() == ClearRun.Kind.STONE ? out.text() : Tools.craftingWhisper(chosen));
        LOG.info("[entropybot] clear: {} - making {}", out.kind() == ClearRun.Kind.STONE ? "keeping the iron pickaxe for ores" : "out of pickaxes", chosen);
        return "wait";
    }

    /**
     * 18a: the steps when the bot puts its own table down: [craft a table] [place it next to itself] [craft the
     * pickaxes, planned when it gets there] [pick the table up again]. Null: the craft walks to a table itself (one
     * within 24 blocks, or no wood to make one).
     */
    static List<Seq.Step> tableTrip(Seq seq, ClearState s, LocalPlayer p, String craftText) {
        Crafting crafting = seq.storage.crafting;
        int[] t = crafting.findTable(p);
        Double dist = t == null ? null : p.getEyePosition().distanceTo(new Vec3(t[0] + 0.5, t[1] + 0.5, t[2] + 0.5));
        Map<String, Integer> inv = Gui.inventory(p);
        boolean carries = inv.getOrDefault("minecraft:crafting_table", 0) > 0, wood = false;
        for (String id : inv.keySet()) wood |= PlaceRules.wood(id);
        PlaceRules.TableChoice choice = PlaceRules.tableChoice(dist, carries, wood);
        if (choice == PlaceRules.TableChoice.USE_NEAR || choice == PlaceRules.TableChoice.WALK_FAR) return null;
        // T2: only where the guard lets it place (inside an area, outside protect boxes, with the fence on) and at the
        // side of the walkway; the pickup below takes it back after the craft
        Pos spot = PlaceRules.tableSpot(s.world, McClearWorld.botOf(p), s.job.box, Clearing::placeCellAllowed);
        if (spot == null) {
            LOG.info("[entropybot] clear: no spot next to me for a crafting table, the craft walks to one");
            return null;
        }
        Seq.Step craft = new Seq.Step("craftitem");
        craft.text = craftText;
        List<Seq.Step> out = tableAround(spot, choice == PlaceRules.TableChoice.CRAFT_AND_PLACE, List.of(craft), null);
        LOG.info("[entropybot] clear: no crafting table within {} blocks - putting one down at {} ({})", PlaceRules.TABLE_NEAR, spot.key(), choice);
        return out;
    }

    /** 0.28.2: the pickup steps of a craft verb's own table carry this in {@code why} (their end makes the job's note). */
    static final String WHY_OWN_TABLE = "owntable";

    /**
     * The one table placer (18a, shared by the clear's trips and, since 0.28.2, the craft verb): [craft a table]
     * [place it at spot] [middle] [the armed pickup]. The pickup only ever takes that exact cell, only while it is still a
     * crafting table and only after our own click put it there.
     */
    static List<Seq.Step> tableAround(Pos spot, boolean craftTable, List<Seq.Step> middle, String why) {
        List<Seq.Step> out = new ArrayList<>();
        if (craftTable) {
            Seq.Step mk = new Seq.Step("craftitem");
            mk.text = "minecraft:crafting_table 1";
            out.add(mk);
        }
        int[] at = {spot.x(), spot.y(), spot.z()};
        // force (a table is a built block), but only exactly a crafting table and only while armed, on every attempt
        ClearJob.Options po = new ClearJob.Options().only(List.of(spot)).force(true).soft(true).keepOres(false)
                .label(PlaceRules.pickupLabel(spot)).exactId(PlaceRules.TABLE_ID);
        Seq.Step pickup = clearStep(po);
        po.armed(() -> armedPickup(pickup));
        pickup.kind = KIND_TABLE;
        pickup.pos = at;
        pickup.why = why;
        Seq.Step place = placeStep(at, "minecraft:crafting_table", null, false);
        ((PlaceState) place.state).pickup = pickup;
        out.add(place);
        out.addAll(middle);
        out.add(pickup);
        return out;
    }

    /**
     * 0.28.2 (A): the craft verb's own table. Null when the craft uses a known table as before (or there is no spot);
     * else the steps around {@code craftSteps}. {@code choice[0]} gets what was decided (for the started reply).
     */
    static List<Seq.Step> ownTableSteps(LocalPlayer p, int[] known, List<Seq.Step> craftSteps, io.github.mojolowjo.entropybot.craft.OwnTable.Choice[] choice) {
        Double dist = known == null ? null : p.getEyePosition().distanceTo(new Vec3(known[0] + 0.5, known[1] + 0.5, known[2] + 0.5));
        int dy = known == null ? 0 : known[1] - (int) Math.floor(p.getY() + 0.01);
        Map<String, Integer> inv = Gui.inventory(p);
        boolean carries = inv.getOrDefault(PlaceRules.TABLE_ID, 0) > 0, wood = false;
        for (String id : inv.keySet()) wood |= PlaceRules.wood(id);
        var c = io.github.mojolowjo.entropybot.craft.OwnTable.choose(dist, dy, carries, wood);
        choice[0] = c;
        if (c != io.github.mojolowjo.entropybot.craft.OwnTable.Choice.PLACE_CARRIED
                && c != io.github.mojolowjo.entropybot.craft.OwnTable.Choice.CRAFT_AND_PLACE) return null;
        LiveWorld w = new LiveWorld();
        w.set(p);
        Pos spot = PlaceRules.tableSpot(w, McClearWorld.botOf(p), null, Clearing::placeCellAllowed);
        if (spot == null) {
            LOG.info("[entropybot] craft: no spot next to me for my own crafting table");
            choice[0] = known != null ? io.github.mojolowjo.entropybot.craft.OwnTable.Choice.WALK_FAR : io.github.mojolowjo.entropybot.craft.OwnTable.Choice.NONE;
            return null;
        }
        LOG.info("[entropybot] craft: no reachable crafting table ({}) - putting my own down at {} ({})", known == null ? "none" : Jobs.fmt(known), spot.key(), c);
        return tableAround(spot, c == io.github.mojolowjo.entropybot.craft.OwnTable.Choice.CRAFT_AND_PLACE, craftSteps, WHY_OWN_TABLE);
    }

    /** 0.28.2: own tables a job could not take back (x y z), for check; pruned once the cell is no crafting table. */
    static final Set<String> LEFT_OWN_TABLES = new LinkedHashSet<>();

    /** check's findings for {@link #LEFT_OWN_TABLES} (those still standing). */
    static List<String> leftOwnTableFindings() {
        List<String> out = new ArrayList<>();
        for (java.util.Iterator<String> it = LEFT_OWN_TABLES.iterator(); it.hasNext(); ) {
            String k = it.next();
            String[] a = k.split(" ");
            String id = blockId(new int[]{Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2])});
            if (id != null && !PlaceRules.TABLE_ID.equals(id)) {
                it.remove();
                continue;
            }
            out.add(io.github.mojolowjo.entropybot.craft.OwnTable.leftAt(k));
        }
        return out;
    }

    /**
     * 0.28.2 (A, the owner's rule): a craft job with its own table down failed: run the pickup before the job ends.
     * True when it jumped to the pickup (the job ends after it with {@code r}).
     */
    static boolean ownTableCaught(Seq seq, String r) {
        if (seq.afterPickupError != null) return false;
        for (int i = seq.idx + 1; i < seq.steps.size(); i++) {
            Seq.Step st = seq.steps.get(i);
            if (WHY_OWN_TABLE.equals(st.why) && armedPickup(st)) {
                seq.afterPickupError = r;
                seq.idx = i;
                seq.stage = null;
                seq.stepStart = seq.now();
                LocalPlayer p = Minecraft.getInstance().player;
                if (p != null && (Gui.open(p) || Minecraft.getInstance().screen != null)) Gui.close(p);
                LOG.info("[entropybot] craft: {} - taking my own table back first", r);
                return true;
            }
        }
        return false;
    }

    /** T3: with the fence on, stand spots and drops only inside an area of this dimension (the guard's policy, read live); else null. */
    static ClearJob.StandCheck standCheck(Commands c) {
        if (!c.fenceOn()) return null;
        String dim = Minecraft.getInstance().level != null ? Storage.dim() : "minecraft:overworld";
        GuardCore g = Core.INSTANCE.guard.core;
        return (x, y, z) -> g.policy().areaAt(dim, x, y, z) != null;
    }

    /** T2: the guard's box rules would let a place lease cover this cell (strict mode; log mode: always). */
    static boolean placeCellAllowed(Pos c) {
        GuardCore g = Core.INSTANCE.guard.core;
        if (g.mode() != GuardCore.Mode.STRICT) return true;
        String dim = Minecraft.getInstance().level != null ? Storage.dim() : "minecraft:overworld";
        return GuardCore.cellLeasable(g.policy(), dim, c.x(), c.y(), c.z());
    }

    /**
     * B7e F: a floor cell or a cave step the fill may place: inside an area and outside protect boxes in every guard mode
     * (log mode only records, so this is the fill's own fence).
     */
    static boolean floorCellAllowed(Pos c) {
        GuardCore g = Core.INSTANCE.guard.core;
        String dim = Minecraft.getInstance().level != null ? Storage.dim() : "minecraft:overworld";
        return GuardCore.cellLeasable(g.policy(), dim, c.x(), c.y(), c.z());
    }

    /** The pickup only takes the table it put down (the place step armed it) and only while it is still a crafting table. */
    static boolean tablePickupWanted(Seq.Step st, LocalPlayer p) {
        return st.pos != null && PlaceRules.pickupMayBreak(armedPickup(st), blockId(st.pos));
    }

    /**
     * B7d review 4a, each tick of a running pickup: null while it may go on (armed, and the cell still our crafting table,
     * or already open: it was broken and the drop is being picked up), else why it is dropped.
     */
    static String pickupLost(Seq.Step st) {
        if (!armedPickup(st)) return "no crafting table of mine to pick up at " + Jobs.fmt(st.pos);
        String id = blockId(st.pos);
        if (id == null || PlaceRules.TABLE_ID.equals(id)) return null;
        var level = Minecraft.getInstance().level;
        if (level.getBlockState(new BlockPos(st.pos[0], st.pos[1], st.pos[2])).isAir()) return null;
        return "the block at " + Jobs.fmt(st.pos) + " is " + id + " now, not my crafting table - left it alone";
    }

    /** The bag is full: the base chests (or the clear's dump chests), valuables kept when collecting. */
    private static String depositTrip(Seq seq, Seq.Step st, ClearState s, McBody body, LocalPlayer p, ClearRun.Out out) {
        if (s.job.junkDrop) {
            // B7e F: far from the base: throw the plain junk away here and dig on (no /home trip)
            splice(seq, s, List.of(FloorSteps.junkStep(s.job, s.start)), "deposit");
            s.run.tripStarted(ClearRun.Kind.DEPOSIT);
            LOG.info("[entropybot] clear: inventory full, throwing junk blocks away");
            return "wait";
        }
        List<Seq.Step> steps = depositSteps(seq.storage, p, s.job);
        if (steps == null) {
            s.run.depositNotStarted();
            return "wait";
        }
        splice(seq, s, steps, "deposit");
        s.run.tripStarted(ClearRun.Kind.DEPOSIT);
        body.whisper(out.text());
        LOG.info("[entropybot] clear: inventory full, putting things away");
        return "wait";
    }

    /** clearMaybeDeposit's trip (the bridge's depositSteps with targets or the base chests); null when there is nothing to do. */
    static List<Seq.Step> depositSteps(Storage storage, LocalPlayer p, ClearJob job) {
        Map<String, Integer> items = StorageRules.depositables(Storage.held(p), "", job.collect, null, storage.keeps());
        if (items.isEmpty()) return null;
        List<Seq.Step> steps = new ArrayList<>();
        if (job.dump != null && !job.dump.isEmpty()) {
            Pos a = job.dump.get(0);
            int[] pa = {a.x(), a.y(), a.z()};
            steps.add(Seq.Step.walk(pa, false));
            steps.add(Seq.Step.open(pa, "no"));
            Seq.Step put = new Seq.Step("put");
            put.items = items;
            if (job.dump.size() > 1) put.fallback = new int[]{job.dump.get(1).x(), job.dump.get(1).y(), job.dump.get(1).z()};
            steps.add(put);
            steps.add(Seq.Step.close());
            return steps;
        }
        // deviation (wave 1 item 5's rule): from far away the base chests too, the walk teleports home first
        boolean atBase = StorageRules.depositAtBase(Core.INSTANCE.knowledge.places().get("base"), Jobs.here(p), Storage.dim());
        StorageRules.Plan plan = StorageRules.depositPlan(items, storage.baseChests(p, atBase), Jobs.here(p), "");
        if (plan.err() != null) {
            LOG.info("[entropybot] clear: can't put things away ({})", plan.err());
            return null;
        }
        for (StorageRules.Stop stop : plan.stops()) {
            steps.add(Seq.Step.walk(stop.chest().pos(), false));
            steps.add(Seq.Step.open(stop.chest().pos(), "no"));
            Seq.Step put = new Seq.Step("put");
            put.items = stop.items();
            put.fallback = stop.fallback();
            steps.add(put);
            steps.add(Seq.Step.close());
        }
        return steps;
    }

    /** The trip goes in before the clear step; the clear waits for it (and hears how it ended). */
    static void splice(Seq seq, ClearState s, List<Seq.Step> add, String kind) {
        seq.splice(seq.idx, add);
        s.inTrip = true;
        s.tripKind = kind;
        s.caught = null;
        s.caughtSet = false;
        seq.stage = null;
        seq.stepStart = seq.now();
    }

    // ---- leases ----

    /** A lease set over the guard with the mod's token, in the current dimension; each lease is logged as an event. */
    static LeaseSet newLeases() {
        Core core = Core.INSTANCE;
        String dim = Minecraft.getInstance().level != null ? Storage.dim() : "minecraft:overworld";
        return new LeaseSet(core.guard.core, core.token, dim, line -> {
            if (line.startsWith("lease refused")) LOG.info("[entropybot] {}", line);
            else core.events.push("guard", line, null);
        });
    }

    private static String takeLeases(ClearState s) {
        String r = s.job.destroyArea != null ? s.leases.takeDestroy(s.job.label, s.job.breakLeaseBox(), s.job.destroyArea)
                : s.leases.take(s.job.label, s.job.breakLeaseBox(), false, s.job.force);
        if (r == null && s.job.torchLeaseBox() != null) r = s.leases.take(s.job.torchLeaseTask(), s.job.torchLeaseBox(), true, false);
        return r;
    }

    /** The leases still held? (A trip longer than 5 s without a heartbeat, a bridge reload, a new policy end them.) */
    private static String checkLeases(ClearState s, boolean beat) {
        if (beat) s.leases.beat();
        return s.leases.ensure();
    }

    // ---- placing (the bridge's placeAt and seq "place" step) ----

    /** holdItem: the item in hand (its laid-out hotbar slot when it has one); false when the bot has none. */
    static boolean holdItem(LocalPlayer p, String id) {
        var inv = p.getInventory();
        if (Gui.itemId(inv.getItem(inv.selected)).equals(id)) return true;
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).isEmpty() || !Gui.itemId(inv.getItem(i)).equals(id)) continue;
            Hotbar.toHand(Minecraft.getInstance(), p, i);
            return true;
        }
        return false;
    }

    /** T1: the item goes down through BlockItem.place (the guard checks the cell alone); false for a bucket, an egg... */
    static boolean isBlockItem(String id) {
        try {
            net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(id);
            if (rl == null) return true;
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl) instanceof net.minecraft.world.item.BlockItem;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * placeAt: puts block item {@code id} into the empty cell x y z by clicking a solid neighbour (the floor first,
     * then the walls, then the ceiling), never a chest or a table. The place lease covers what the guard checks (T1:
     * the cell; for a bucket also the clicked block) and goes into {@code leases} (once per box). "ok: ..." or "error: ...".
     */
    public static String placeAt(LocalPlayer p, String id, int x, int y, int z, LeaseSet leases) {
        return placeAt(p, id, x, y, z, leases, null);
    }

    /** As above; seal: the dig box a water seal belongs to (its lease may lie just outside the areas, LeaseSet.sealLease). */
    static String placeAt(LocalPlayer p, String id, int x, int y, int z, LeaseSet leases, ClearBox seal) {
        return placeAt(p, id, x, y, z, leases, seal, false);
    }

    /** restore (P1): the cell is one the bot broke itself; its lease is LeaseSet.restoreLease (any mode, never a protect box). */
    static String placeAt(LocalPlayer p, String id, int x, int y, int z, LeaseSet leases, ClearBox seal, boolean restore) {
        return placeAt(p, id, x, y, z, leases, seal, restore, null);
    }

    /**
     * 0.30.2 (package 5): place against the given neighbour (side: the offset from the cell to the clicked block; the
     * clicked face is side.face(), the anchor's face that borders the cell, so the block goes into x y z). The lease
     * logic is placeAt's. The anchor must still be clickable and its face in reach.
     */
    public static String placeAgainst(LocalPlayer p, String id, int x, int y, int z, PlaceRules.Side side, LeaseSet leases) {
        return placeAt(p, id, x, y, z, leases, null, false, side);
    }

    private static String placeAt(LocalPlayer p, String id, int x, int y, int z, LeaseSet leases, ClearBox seal, boolean restore, PlaceRules.Side forced) {
        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = new BlockPos(x, y, z);
        BlockState target = mc.level.getBlockState(pos);
        if (!target.canBeReplaced()) {
            return target.getBlock().getDescriptionId().contains(GuiCore.bareId(id)) ? PlaceRules.ALREADY_THERE : "error: something is in the way at " + x + " " + y + " " + z;
        }
        String inWay = entityInWay(p, x, y, z);
        if (inWay != null) return inWay;
        LiveWorld w = new LiveWorld();
        w.set(p);
        PlaceRules.Side s = forced == null ? PlaceRules.placeSide(w, x, y, z, p.getX(), p.getEyeY(), p.getZ())
                : PlaceRules.clickable(w, x + forced.dx(), y + forced.dy(), z + forced.dz())
                        && io.github.mojolowjo.entropybot.clear.RimPlan.clickableFrom(w, new Pos(x, y, z), forced, p.getX(), p.getEyeY(), p.getZ())
                        ? forced : null;
        if (s == null) return "error: nothing in reach to place " + GuiCore.shortId(id) + " against at " + x + " " + y + " " + z;
        // T1: lease only what the guard checks for this item (a block item: the cell; a bucket: the clicked block too)
        String le = restore ? leases.restoreLease(x, y, z, "putting back " + GuiCore.shortId(id) + " at " + Pos.key(x, y, z))
                : seal != null && isBlockItem(id)
                ? leases.sealLease(x, y, z, seal, "sealing water with " + GuiCore.shortId(id) + " at " + Pos.key(x, y, z))
                : leases.placeLease(PlaceRules.placeLeaseBox(x, y, z, s, isBlockItem(id)), "placing " + GuiCore.shortId(id) + " at " + Pos.key(x, y, z));
        if (le != null) return le;
        if (!holdItem(p, id)) return "error: I have no " + GuiCore.shortId(id);
        Vec3 hit = new Vec3(x + 0.5 + s.dx() * 0.5, y + 0.5 + s.dy() * 0.5, z + 0.5 + s.dz() * 0.5);
        try { p.lookAt(EntityAnchorArgument.Anchor.EYES, hit); } catch (RuntimeException ignored) {}
        InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, McClearWorld.direction(s.face()), pos.offset(s.dx(), s.dy(), s.dz()), false));
        p.swing(InteractionHand.MAIN_HAND);
        // 0.23.1: the game refusing the placement (no room for a bed's other half, the guard) is an error, not "ok: FAIL"
        if (r == InteractionResult.FAIL) return "error: the game refused placing " + GuiCore.shortId(id) + " at " + x + " " + y + " " + z + " (no room for it, or the guard said no)";
        return "ok: " + r;
    }

    /**
     * 0.30.7 (live, shed3: the bot's corpse in the furnace cell, three "the game refused" tries): the first entity that
     * blocks building in cell x y z (not the bot, not item drops), as PlaceRules.entityText; null when free. Vanilla:
     * Level.getEntities(Entity, AABB), Entity.blocksBuilding, BuiltInRegistries.ENTITY_TYPE (loader-neutral); the
     * corpse's owner via Storage.ownsCorpse (reflection on the Corpse mod).
     */
    static String entityInWay(LocalPlayer p, int x, int y, int z) {
        try {
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(x, y, z, x + 1, y + 1, z + 1);
            for (net.minecraft.world.entity.Entity e : Minecraft.getInstance().level.getEntities(p, box)) {
                if (!e.isAlive() || e.isSpectator() || e instanceof net.minecraft.world.entity.item.ItemEntity
                        || e instanceof net.minecraft.world.entity.ExperienceOrb) continue;
                String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
                boolean corpse = type.equals("corpse:corpse");
                // only what the game's own check refuses (Level.isUnobstructed: Entity.blocksBuilding); a corpse is logged
                // either way (live probe: does an emptied one still block?)
                if (corpse) LOG.info("[entropybot] place: a corpse at {} {} {} (blocks building: {}, emptied: {})", x, y, z, e.blocksBuilding, Storage.EMPTIED.contains(e.getUUID()));
                if (!e.blocksBuilding) continue;
                PlaceRules.InWay kind = PlaceRules.inWayKind(type, corpse && Storage.ownsCorpse(e, p),
                        e instanceof net.minecraft.world.entity.LivingEntity);
                // live probe (shed3): an emptied corpse is gone on the server ("execute if entity" fails) while the client
                // still lists it, blocking building, for a minute or more: no reason to stop; the click goes ahead
                if (kind == PlaceRules.InWay.MY_CORPSE && Storage.EMPTIED.contains(e.getUUID())) continue;
                String text = PlaceRules.entityText(kind, type.substring(type.indexOf(':') + 1).replace('_', ' '), x, y, z);
                LOG.info("[entropybot] place: {}", text);
                return text;
            }
        } catch (RuntimeException ex) {
            LOG.warn("[entropybot] place: entity check at {} {} {}: {}", x, y, z, ex.toString());
        }
        return null;
    }

    private static boolean isThere(int[] pos, String id) {
        BlockState st = Minecraft.getInstance().level.getBlockState(new BlockPos(pos[0], pos[1], pos[2]));
        return st.getBlock().getDescriptionId().contains(GuiCore.bareId(id));
    }

    /** The seq "place" step, walking into reach first (a stand spot, or the given one). */
    private static String placeRun(Seq seq, Seq.Step st, LocalPlayer p, long elapsed) {
        PlaceState ps = st.state instanceof PlaceState x ? x : new PlaceState();
        st.state = ps;
        ps.optional |= st.optional;          // D2's hole fills and D3's torches set Step.optional on a plain placeStep
        if (ps.leases == null) ps.leases = newLeases();
        String id = GuiCore.normId(st.id);
        String sid = GuiCore.shortId(id);
        String r;
        try {
            r = placeTick(seq, st, ps, p, id, sid, elapsed);
        } catch (RuntimeException e) {
            r = String.valueOf(e);
        }
        if (!r.equals("wait")) ps.leases.releaseAll();
        // an optional placement never ends the job: it is skipped, and the log says why
        if (ps.optional && !r.equals("wait") && !r.equals("next")) {
            LOG.info("[entropybot] place: skipped the optional {} at {} ({})", sid, Jobs.fmt(st.pos), r);
            return "next";
        }
        return r;
    }

    private static String placeTick(Seq seq, Seq.Step st, PlaceState ps, LocalPlayer p, String id, String sid, long elapsed) {
        int[] pos = st.pos;
        long now = seq.now();
        if (isThere(pos, id)) {
            if (ps.pickup != null) {
                // B7d review 4b: armed only after our own click on a free cell, with exactly a crafting table there now
                if (PlaceRules.armsTable(ps.clicked, blockId(pos))) {
                    ps.pickup.got = KIND_PLACED_TABLE;      // it went down: the pickup may take it
                    LOG.info("[entropybot] place: my crafting table is down at {}", Jobs.fmt(pos));
                } else {
                    LOG.info("[entropybot] place: a table at {} I didn't just put down - I won't pick it up", Jobs.fmt(pos));
                }
            }
            return "next";
        }
        if (ps.stage == null) {
            ps.tries = 0;
            if (Gui.inventory(p).getOrDefault(id, 0) <= 0) return ps.optional ? "next" : "I have no " + sid + " to place";
            IBaritone b = Jobs.baritone();
            int[] stand = ps.from;
            if (stand == null) {
                LiveWorld w = new LiveWorld();
                w.set(p);
                if (PlaceRules.placeSide(w, pos[0], pos[1], pos[2], p.getX(), p.getEyeY(), p.getZ()) == null) {
                    var spot = PlaceRules.standFor(w, new Pos(pos[0], pos[1], pos[2]), McClearWorld.botOf(p), standCheck(seq.jobs.core().commands));
                    if (spot == null) return ps.optional ? "next" : "nowhere to stand within reach of " + Jobs.fmt(pos) + " to place the " + sid;
                    stand = new int[]{spot.x(), spot.y(), spot.z()};
                }
            }
            if (stand != null && b != null) {
                String fence = seq.jobs.goalAllowed(stand[0], stand[1], stand[2]);
                if (fence != null) return Jobs.withAreaHint(fence + " (" + Jobs.fmt(stand) + ")");
                Jobs.safeSettings();
                b.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(stand[0], stand[1], stand[2])));
                ps.stage = "walking";
                ps.stageTick = now;
                seq.setStatus(seq.label + " - going to place a " + sid);
                return "wait";
            }
            ps.stage = "place";
        }
        if (ps.stage.equals("walking")) {
            if (now - ps.stageTick < 20) return "wait";
            IBaritone b = Jobs.baritone();
            if (b != null && !Jobs.idle(b)) return now - ps.stageTick > 20 * 60 ? "took too long walking to place the " + sid + " at " + Jobs.fmt(pos) : "wait";
            ps.stage = "place";
        }
        if (ps.stage.equals("retry")) {
            if (now - ps.stageTick < 10) return "wait";
            ps.stage = "place";
        }
        if (ps.stage.equals("place")) {
            // 4b: only a click into a cell that was free just before it makes the block ours
            boolean freeBefore = Minecraft.getInstance().level.getBlockState(new BlockPos(pos[0], pos[1], pos[2])).canBeReplaced();
            String r = placeAt(p, id, pos[0], pos[1], pos[2], ps.leases, ps.seal);
            // 28c: a later try finding the block already there (it showed up after the 6-tick wait) must not undo our first click
            ps.clicked = ps.clicked || PlaceRules.ourClick(freeBefore, r);
            if (!r.startsWith("ok")) {
                // 0.30.7 (live, shed3): a mob in the cell is waited for (10 s), not counted as a try; a corpse or
                // another entity ends the step at once with its own next verb (three clicks would change nothing)
                if (r.contains(" is in the way at ")) {
                    if (r.endsWith("(waiting)")) {
                        if (ps.mobSince < 0) ps.mobSince = now;
                        if (now - ps.mobSince < PlaceRules.MOB_WAIT_TICKS) {
                            seq.setStatus(seq.label + " - " + r.replaceFirst("^error: ", ""));
                            ps.stage = "retry";
                            ps.stageTick = now;
                            return "wait";
                        }
                        return ps.optional ? "next" : "couldn't place the " + sid + ": " + PlaceRules.mobStayedText(PlaceRules.waitedFor(r), pos[0], pos[1], pos[2]);
                    }
                    return ps.optional ? "next" : "couldn't place the " + sid + " at " + Jobs.fmt(pos) + ": " + r.replaceFirst("^error: ", "");
                }
                ps.mobSince = -1;
                // 0.30.6 (live, shed2: "place chest" right after the door dig, the bot still outside): nothing in reach
                // from where the walk ended (or from where the bot stood when the step began and then moved on): walk to
                // a stand spot picked afresh, once, before the three tries count
                if (r.contains("nothing in reach") && ps.walks < 1) {
                    ps.walks++;
                    ps.stage = null;
                    ps.from = null;
                    LOG.info("[entropybot] place: {} at {} - nothing in reach from {}: walking to a stand spot", sid, Jobs.fmt(pos), Jobs.fmt(Jobs.here(p)));
                    return "wait";
                }
                // a placement that fails 3 times ends the step and says why (it never stands there for good)
                ps.tries++;
                if (ps.tries >= 3) return ps.optional ? "next" : "couldn't place the " + sid + " at " + Jobs.fmt(pos) + ": " + r.replaceFirst("^error: ", "");
                ps.stage = "retry";
                ps.stageTick = now;
                return "wait";
            }
            ps.stage = "check";
            ps.stageTick = now;
            return "wait";
        }
        // placed blocks show up a tick or two later; try up to 3 times
        if (now - ps.stageTick < 6) return "wait";
        ps.tries++;
        if (ps.tries >= 3) return ps.optional ? "next" : "couldn't place the " + sid + " at " + Jobs.fmt(pos) + ": it didn't stay there (3 tries)";
        ps.stage = "place";
        return "wait";
    }

    // ---- build floor|walls|fill|shell: Baritone's builder, placing only, inside the zone ----

    /** The build step; the state (leases, the selection) was set up by DigCommands.build. */
    private static String buildRun(Seq seq, Seq.Step st, LocalPlayer p, long elapsed) {
        if (!(st.state instanceof BuildState bs)) return "the build was not set up";
        if (bs.done) return "next";
        long now = seq.now();
        if (now % 20 == 0) bs.leases.beat();
        // B7d review 7: a reflex hold longer than 100 ticks lets the guard drop the zone's place lease: take it again at
        // once (as the clear step does), or every Baritone placement after the hold is vetoed
        String le = bs.leases.ensure();
        if (le != null) {
            endBuild(bs);
            seq.jobs.finish("stopped: " + bs.status + " - " + le.replaceFirst("^error: ", "") + "; placing off again");
            return "wait";
        }
        if (bs.rim) return rimTick(seq, bs, p, now);
        if (now - bs.start < 60 || now % 20 != 0) return "wait";
        IBaritone b = Jobs.baritone();
        // 0.30.1: the dig step's watch: no cell placed for 60 s, or the builder paused itself = Baritone's part is over
        LiveWorld w = new LiveWorld();
        w.set(p);
        int miss = io.github.mojolowjo.entropybot.clear.RimPlan.missing(w, bs.cells).size();
        if (!bs.clockOn && (miss < bs.lastMissing || bs.box == null || bs.box.distTo(p.getX(), p.getY(), p.getZ()) <= 8)) {
            bs.clockOn = true;
            bs.lastChange = now;
        }
        if (miss < bs.lastMissing) bs.lastChange = now;
        bs.lastMissing = miss;
        if (!bs.clockOn) bs.lastChange = now;
        io.github.mojolowjo.entropybot.clear.BuilderReport.End e = b == null ? io.github.mojolowjo.entropybot.clear.BuilderReport.End.IDLE
                : io.github.mojolowjo.entropybot.clear.BuilderReport.watch(!Jobs.idle(b) && b.getBuilderProcess().isActive(),
                b.getBuilderProcess().isPaused(), now - bs.lastChange);
        if (miss == 0 && e == null) e = io.github.mojolowjo.entropybot.clear.BuilderReport.End.IDLE;
        // (9) the fast path: the builder has goals but no path and I have not moved a block for 10 s = nothing it can do
        long feet = p.blockPosition().asLong();
        if (feet != bs.lastFeet) {
            bs.lastFeet = feet;
            bs.stillSince = now;
        }
        if (e == null && b != null && bs.clockOn) {
            try {
                var pb = b.getPathingBehavior();
                if (b.getBuilderProcess().isActive() && !pb.isPathing() && pb.getGoal() != null && now - bs.stillSince >= 200) {
                    LOG.info("[entropybot] build: Baritone has goals but no path and I stood still 10 s - placing the rest myself");
                    e = io.github.mojolowjo.entropybot.clear.BuilderReport.End.STALLED;
                }
            } catch (RuntimeException ex) {
                LOG.warn("[entropybot] build: the not-moving watch failed: {}", ex.toString());
            }
        }
        if (e == null) return "wait";
        LOG.info("[entropybot] build: Baritone's part ended ({}), {} of {} cells missing", e, miss, bs.cells.size());
        stopBuilderPart(bs);
        bs.rim = true;
        bs.stage = "pick";
        bs.stageTick = now;
        return "wait";
    }

    /** Baritone's builder off (the selection cleared, the look settings back); the zone's place lease stays for the rim. */
    static void stopBuilderPart(BuildState bs) {
        placingOwned = false;
        IBaritone b = Jobs.baritone();
        if (b != null) {
            Jobs.cancel(b);
            try { b.getCommandManager().execute("sel clear"); } catch (Throwable ignored) {}
        }
        Jobs.safeSettings();
        restoreLook(bs);
    }

    /** The dig job's look settings for the build (saved first): no kept look direction, no aim jitter. */
    static void applyLook(BuildState bs) {
        try {
            baritone.api.Settings s = BaritoneAPI.getSettings();
            if (bs.savedRemain == null) {
                bs.savedRemain = s.remainWithExistingLookDirection.value;
                bs.savedRandom = s.randomLooking.value;
                bs.savedRandom113 = s.randomLooking113.value;
            }
            s.remainWithExistingLookDirection.value = false;
            s.randomLooking.value = 0d;
            s.randomLooking113.value = 0d;
        } catch (Throwable t) {
            LOG.warn("[entropybot] build: couldn't set the look settings: {}", t.toString());
        }
    }

    /** 0.30.4 (live: the bot left on a wall top 4 above the floor, Baritone's limit 3): a build may drop 4 (half a heart). */
    static final int BUILD_FALL = 4;

    static void fallOn(BuildState bs) {
        try {
            baritone.api.Settings s = BaritoneAPI.getSettings();
            if (bs.savedFall == null) bs.savedFall = s.maxFallHeightNoWater.value;
            s.maxFallHeightNoWater.value = Math.max(bs.savedFall, BUILD_FALL);
        } catch (Throwable t) {
            LOG.warn("[entropybot] build: couldn't raise the fall height: {}", t.toString());
        }
    }

    static void fallOff(BuildState bs) {
        if (bs.savedFall == null) return;
        try {
            BaritoneAPI.getSettings().maxFallHeightNoWater.value = bs.savedFall;
        } catch (Throwable t) {
            LOG.warn("[entropybot] build: couldn't restore the fall height: {}", t.toString());
        }
        bs.savedFall = null;
    }

    static void restoreLook(BuildState bs) {
        if (bs.savedRemain == null) return;
        try {
            baritone.api.Settings s = BaritoneAPI.getSettings();
            s.remainWithExistingLookDirection.value = bs.savedRemain;
            s.randomLooking.value = bs.savedRandom;
            s.randomLooking113.value = bs.savedRandom113;
        } catch (Throwable t) {
            LOG.warn("[entropybot] build: couldn't restore the look settings: {}", t.toString());
        }
        bs.savedRemain = null;
    }

    /** The rim placer: one missing cell at a time, from on top (RimPlan decides, placeAt clicks). */
    private static String rimTick(Seq seq, BuildState bs, LocalPlayer p, long now) {
        LiveWorld w = new LiveWorld();
        w.set(p);
        boolean recount = bs.missing == null || bs.stage.equals("pick") || bs.stage.equals("check") || now - bs.missingAt >= 20;
        if (recount) {
            bs.missing = io.github.mojolowjo.entropybot.clear.RimPlan.missing(w, bs.cells);
            bs.missingAt = now;
        }
        List<Pos> missing = bs.missing;
        if (bs.layerStart == 0) bs.layerStart = now;
        String blk = GuiCore.shortId(bs.id);
        int baritonePlaced = Math.max(0, bs.startMissing - missing.size() - bs.mine);
        if (missing.isEmpty()) return rimEnd(seq, bs, io.github.mojolowjo.entropybot.clear.RimPlan.doneText(bs.shape, blk, baritonePlaced, bs.mine));
        // review 3: 90 s without a placement of mine ends it, in every stage (a walk that never ends too)
        if (now - bs.layerStart > io.github.mojolowjo.entropybot.clear.RimPlan.LAYER_CAP_TICKS) {
            IBaritone wb = Jobs.baritone();
            if (wb != null && !Jobs.idle(wb)) Jobs.cancel(wb);
            return rimStop(seq, bs, w, missing, baritonePlaced, null);
        }
        switch (bs.stage) {
            case "pick" -> {
                if (Gui.inventory(p).getOrDefault(bs.id, 0) <= 0) return rimStop(seq, bs, w, missing, baritonePlaced, io.github.mojolowjo.entropybot.clear.RimPlan.Why.NO_BLOCKS);
                var pk = io.github.mojolowjo.entropybot.clear.RimPlan.next(w, missing, bs.tries, p.getX(), p.getY(), p.getZ());
                if (pk == null) return rimStop(seq, bs, w, missing, baritonePlaced, null);
                // the 90 s cap counts per layer
                if (pk.cell().y() != bs.layerY) {
                    bs.layerY = pk.cell().y();
                    bs.layerStart = now;
                }
                bs.pick = pk;
                bs.clearing = false;
                bs.placeId = bs.id;
                if (pk.filler()) {
                    String fid = io.github.mojolowjo.entropybot.clear.RimPlan.filler(Gui.inventory(p), bs.id);
                    if (fid == null) {
                        giveUpCell(bs, pk.cell(), io.github.mojolowjo.entropybot.clear.RimPlan.Why.NO_SUPPORT);
                        return "wait";
                    }
                    bs.placeId = fid;
                }
                if (pk.why() != null) {
                    giveUpCell(bs, pk.cell(), pk.why());
                    return "wait";
                }
                seq.setStatus(bs.status + " - placing the rest myself (" + missing.size() + " left)");
                if (pk.stand() == null) {
                    bs.stage = "place";
                    return "wait";
                }
                Pos s = pk.stand();
                String fence = seq.jobs.goalAllowed(s.x(), s.y(), s.z());
                IBaritone b = Jobs.baritone();
                if (fence != null || b == null) {
                    giveUpCell(bs, pk.cell(), io.github.mojolowjo.entropybot.clear.RimPlan.Why.NO_STAND);
                    return "wait";
                }
                Jobs.safeSettings();
                b.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(s.x(), s.y(), s.z())));
                bs.stage = "walking";
                bs.stageTick = now;
                return "wait";
            }
            case "walking" -> {
                if (now - bs.stageTick < 20) return "wait";
                IBaritone b = Jobs.baritone();
                if (b != null && !Jobs.idle(b) && now - bs.stageTick < 30 * 20) return "wait";
                if (b != null && !Jobs.idle(b)) Jobs.cancel(b);
                bs.stage = "place";
                return "wait";
            }
            case "place" -> {
                Pos c = bs.pick.cell();
                if (!PlaceRules.free(w, c.x(), c.y(), c.z())) {        // it went in meanwhile
                    bs.stage = "pick";
                    return "wait";
                }
                String r;
                try {
                    String pid = bs.placeId != null ? bs.placeId : bs.id;
                    // the side stance clicks its anchor's face (seen from the feet cell); the top stance lets placeAt pick
                    PlaceRules.Side side = bs.pick.side();
                    if (side != null) {
                        PlaceRules.Side seen = io.github.mojolowjo.entropybot.clear.RimPlan.seenFrom(w, c, p.getX(), p.getEyeY(), p.getZ());
                        if (seen != null) side = seen;
                    }
                    r = side != null ? placeAgainst(p, pid, c.x(), c.y(), c.z(), side, bs.leases)
                            : placeAt(p, pid, c.x(), c.y(), c.z(), bs.leases);
                } catch (RuntimeException ex) {
                    r = "error: " + ex;
                }
                // review a: only a replaceable cover (snow layer, grass) is broken, never a built block or a block
                // entity (torch, button, rail, crop...), and only in reach; else the click counts as a try
                boolean coverOk = io.github.mojolowjo.entropybot.clear.RimPlan.replaceable(w, c.x(), c.y(), c.z())
                        && ClearEngine.eyeDistSq(p.getX(), p.getEyeY(), p.getZ(), c.x(), c.y(), c.z()) <= PlaceRules.PLACE_REACH * PlaceRules.PLACE_REACH;
                if (!r.startsWith("ok") && !bs.clearing && coverOk && !r.contains("nothing in reach")) {
                    // a snow layer (grass...) in the cell: break it by hand first, then place (once per pick)
                    LOG.info("[entropybot] build rim: {} at {}: {} - breaking the {} in it first", blk, c.key(), r, w.name(c.x(), c.y(), c.z()));
                    String le = bs.leases.take("clearing " + c.key() + " for " + blk, ClearBox.of(c.x(), c.y(), c.z(), c.x(), c.y(), c.z()), false, false);
                    if (le == null) {
                        bs.clearing = true;
                        bs.clearStarted = false;
                        bs.stage = "clearcell";
                        bs.stageTick = now;
                        return "wait";
                    }
                    r = le;
                }
                if (!r.startsWith("ok")) {
                    LOG.info("[entropybot] build rim: {} at {}: {}", blk, c.key(), r);
                    bs.clearing = false;
                    // only a click with nothing in reach is "nowhere to stand"; a refused click counts as a try
                    triedCell(bs, c, r.contains("nothing in reach") ? io.github.mojolowjo.entropybot.clear.RimPlan.Why.NO_STAND
                            : io.github.mojolowjo.entropybot.clear.RimPlan.Why.TRIES);
                    bs.stage = "pick";
                    return "wait";
                }
                bs.clearing = false;
                bs.stage = "check";
                bs.stageTick = now;
                return "wait";
            }
            case "clearcell" -> {
                Pos c = bs.pick.cell();
                Minecraft mc = Minecraft.getInstance();
                if (w.air(c.x(), c.y(), c.z()) || now - bs.stageTick > 5 * 20) {   // gone (or 5 s): place again
                    try { mc.gameMode.stopDestroyBlock(); } catch (RuntimeException ignored) {}
                    bs.stage = "place";
                    return "wait";
                }
                BlockPos bp = new BlockPos(c.x(), c.y(), c.z());
                Vec3 centre = Vec3.atCenterOf(bp);
                try { p.lookAt(EntityAnchorArgument.Anchor.EYES, centre); } catch (RuntimeException ignored) {}
                Vec3 eye = p.getEyePosition();
                net.minecraft.core.Direction face = net.minecraft.core.Direction.getNearest((float) (eye.x - centre.x), (float) (eye.y - centre.y), (float) (eye.z - centre.z));
                if (!bs.clearStarted) {
                    mc.gameMode.startDestroyBlock(bp, face);
                    bs.clearStarted = true;
                } else mc.gameMode.continueDestroyBlock(bp, face);
                p.swing(InteractionHand.MAIN_HAND);
                return "wait";
            }
            default -> {                                            // check: placed blocks show up a tick or two later
                if (now - bs.stageTick < 6) return "wait";
                Pos c = bs.pick.cell();
                if (PlaceRules.free(w, c.x(), c.y(), c.z())) triedCell(bs, c, io.github.mojolowjo.entropybot.clear.RimPlan.Why.TRIES);
                else {
                    if (!bs.pick.filler()) bs.mine++;
                    bs.layerStart = now;                            // progress: the 90 s count again
                }
                bs.stage = "pick";
                return "wait";
            }
        }
    }

    private static void triedCell(BuildState bs, Pos c, io.github.mojolowjo.entropybot.clear.RimPlan.Why why) {
        bs.tries.merge(c, 1, Integer::sum);
        bs.gaveUp.put(c, why);
    }

    private static void giveUpCell(BuildState bs, Pos c, io.github.mojolowjo.entropybot.clear.RimPlan.Why why) {
        bs.tries.put(c, io.github.mojolowjo.entropybot.clear.RimPlan.MAX_TRIES);
        bs.gaveUp.put(c, why);
    }

    private static String rimStop(Seq seq, BuildState bs, ClearWorld w, List<Pos> missing, int baritonePlaced, io.github.mojolowjo.entropybot.clear.RimPlan.Why why) {
        // the example: a cell given up for a reason of its own first, else the first one left
        Pos ex = missing.get(0);
        for (Pos c : missing) if (bs.gaveUp.containsKey(c) || (!io.github.mojolowjo.entropybot.clear.RimPlan.supported(w, c)
                && !io.github.mojolowjo.entropybot.clear.RimPlan.anchored(w, c))) { ex = c; break; }
        List<Pos> left = new ArrayList<>(missing);
        left.remove(ex);
        left.add(0, ex);
        io.github.mojolowjo.entropybot.clear.RimPlan.Why y = why != null ? why : io.github.mojolowjo.entropybot.clear.RimPlan.whyLeft(w, ex, bs.gaveUp);
        return rimEnd(seq, bs, io.github.mojolowjo.entropybot.clear.RimPlan.stoppedText(bs.shape, GuiCore.shortId(bs.id), baritonePlaced, bs.mine, left, y, bs.area));
    }

    private static String rimEnd(Seq seq, BuildState bs, String text) {
        if (bs.clearing) {                                   // review a: never leave a half-broken cover clicked
            try { Minecraft.getInstance().gameMode.stopDestroyBlock(); } catch (RuntimeException ignored) {}
            bs.clearing = false;
        }
        endBuild(bs);
        seq.jobs.finish(text + "; placing off again");
        return "wait";
    }

    static void endBuild(BuildState bs) {
        bs.done = true;
        restoreLook(bs);
        fallOff(bs);
        placingOwned = false;
        IBaritone b = Jobs.baritone();
        if (b != null) {
            Jobs.cancel(b);
            try { b.getCommandManager().execute("sel clear"); } catch (Throwable ignored) {}
        }
        Jobs.safeSettings();
        // B7d review: the build's own settings back too (the bridge's restoreSafeSettings reset all four)
        try {
            baritone.api.Settings s = BaritoneAPI.getSettings();
            s.allowInventory.value = false;
            s.buildIgnoreExisting.value = false;
        } catch (Throwable ignored) {}
        bs.leases.releaseAll();
    }

    // ---- the game side of a clear ----

    /** {@link ClearRun.Body} over the client: Baritone walks, the player controller breaks, the hotbar keeper's hand. */
    static final class McBody implements ClearRun.Body {
        final Seq seq;
        final ClearState s;
        final LocalPlayer p;
        final Minecraft mc = Minecraft.getInstance();

        McBody(Seq seq, ClearState s, LocalPlayer p) {
            this.seq = seq;
            this.s = s;
            this.p = p;
        }

        private static BlockPos bp(Pos t) { return new BlockPos(t.x(), t.y(), t.z()); }

        @Override public Bot bot() { return McClearWorld.botOf(p); }

        @Override public long now() { return seq.now(); }

        @Override public ClearRun.Held hold(ClearJob job, Pos t) {
            McClearWorld w = s.world.cur;
            List<Tools.Slot> slots = w.toolSlots(p, t.x(), t.y(), t.z());
            boolean need = w.needsCorrectTool(t.x(), t.y(), t.z());
            int i = Tools.choose(slots, need, w.ore(t.x(), t.y(), t.z()), Hotbar.toolOres());
            if (i < 0) return new ClearRun.Held(Tools.okWithout(need, w.name(t.x(), t.y(), t.z())), null);     // 0.28.1: snow by hand
            String id = Gui.itemId(p.getInventory().getItem(i));
            if (Tools.isPickaxe(id)) job.lastPick = id;
            Hotbar.toHand(mc, p, i);
            return new ClearRun.Held(true, id);
        }

        @Override public boolean hasPickaxe() {
            for (int i = 0; i < 36; i++) {
                if (!p.getInventory().getItem(i).isEmpty() && Tools.isPickaxe(Gui.itemId(p.getInventory().getItem(i)))) return true;
            }
            return false;
        }

        @Override public boolean stoneCanBreak(Pos t) { return s.world.cur.stoneCanBreak(t.x(), t.y(), t.z()); }

        @Override public String toolOres() { return Hotbar.toolOres(); }

        @Override public double progress(Pos t) { return s.world.cur.destroyProgress(p, t.x(), t.y(), t.z()); }

        private IPlayerController pc() {
            IBaritone b = Jobs.baritone();
            return b == null ? null : b.getPlayerContext().playerController();
        }

        @Override public void hit(Pos t, ClearEngine.Sight sight, boolean first) {
            IPlayerController pc = pc();
            if (pc == null) return;
            try { p.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(sight.x(), sight.y(), sight.z())); } catch (RuntimeException ignored) {}
            var dir = McClearWorld.direction(sight.face());
            if (first) {
                pc.syncHeldItem();
                pc.clickBlock(bp(t), dir);
            } else {
                pc.onPlayerDamageBlock(bp(t), dir);
            }
            p.swing(InteractionHand.MAIN_HAND);
            // Baritone's trick: tell the game it isn't "hitting" any more, or its own tick cancels the progress
            pc.setHittingBlock(false);
        }

        @Override public void stopBreaking() {
            IPlayerController pc = pc();
            if (pc == null) return;
            try {
                pc.setHittingBlock(true);
                pc.resetBlockRemoving();
            } catch (RuntimeException ignored) {}
        }

        @Override public boolean haveTorches() { return Gui.inventory(p).getOrDefault("minecraft:torch", 0) > 0; }

        @Override public String placeTorch(Pos t) { return placeAt(p, "minecraft:torch", t.x(), t.y(), t.z(), s.leases); }

        @Override public void walkBlock(int x, int y, int z) {
            IBaritone b = Jobs.baritone();
            if (b == null) return;
            Jobs.safeSettings();
            b.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(x, y, z)));
        }

        @Override public void walkNear(int x, int y, int z, int r) {
            IBaritone b = Jobs.baritone();
            if (b == null) return;
            Jobs.safeSettings();
            b.getCustomGoalProcess().setGoalAndPath(new GoalNear(new BlockPos(x, y, z), r));
        }

        @Override public boolean walkIdle() {
            IBaritone b = Jobs.baritone();
            return b == null || Jobs.idle(b);
        }

        @Override public void cancelWalk() {
            IBaritone b = Jobs.baritone();
            if (b != null) Jobs.cancel(b);
        }

        @Override public List<ClearRun.Drop> drops(ClearJob job) {
            List<ClearRun.Drop> out = new ArrayList<>();
            for (Entity e : mc.level.entitiesForRendering()) {
                if (!(e instanceof ItemEntity) || !e.isAlive()) continue;
                int x = (int) Math.floor(e.getX()), y = (int) Math.floor(e.getY() + 0.01), z = (int) Math.floor(e.getZ());
                // near the box, not down a hole under the walkway, and (T3) never outside the areas with the fence on
                if (!job.dropWanted(x, y, z)) continue;
                // B7e F: with "junk drop", junk drops stay where they lie (but the floor's block while it has few)
                if (job.junkDrop && !io.github.mojolowjo.entropybot.clear.JunkDrop.chase(Gui.itemId(((ItemEntity) e).getItem()), job, inv(), x, y, z, seq.now())) continue;
                double d = e.distanceTo(p);
                if (d > 1.2 && d < 8) out.add(new ClearRun.Drop(String.valueOf(e.getId()), x, y, z, d));
            }
            out.sort((a, b) -> Double.compare(a.d(), b.d()));
            return out;
        }

        private Map<String, Integer> invCache;

        private Map<String, Integer> inv() {
            if (invCache == null) invCache = Gui.inventory(p);
            return invCache;
        }

        private ItemEntity drop(ClearRun.Drop d) {
            try {
                Entity e = mc.level.getEntity(Integer.parseInt(d.id()));
                return e instanceof ItemEntity ie ? ie : null;
            } catch (RuntimeException ex) {
                return null;
            }
        }

        @Override public boolean dropAlive(ClearRun.Drop d) {
            ItemEntity e = drop(d);
            return e != null && e.isAlive() && !e.isRemoved();
        }

        @Override public boolean roomFor(ClearRun.Drop d) {
            var inv = p.getInventory();
            if (inv.getFreeSlot() >= 0) return true;
            ItemEntity e = drop(d);
            return e != null && inv.getSlotWithRemainingSpace(e.getItem()) >= 0;
        }

        @Override public boolean bagFull() { return p.getInventory().getFreeSlot() < 0; }

        @Override public void status(String text) { seq.setStatus(text); }

        private String requester() {
            Jobs.Job j = seq.job();
            return j != null && j.req != null ? j.req.from : null;
        }

        @Override public void whisper(String text) {
            String to = requester();
            if (to != null) seq.jobs.core().commands.whisper(to, text);
        }

        @Override public void warnFull(String text) {
            String to = requester();
            Commands c = seq.jobs.core().commands;
            c.whisper(to != null ? to : c.owner(), text);
        }

        @Override public void log(String text) { LOG.info("[entropybot] clear: {}", text); }
    }

    /** The dimension check for a box the verbs are given: the guard's notion of "inside one of my areas" (x/z). */
    static boolean boxInAreas(ClearBox b) {
        var pol = Core.INSTANCE.guard.core.policy();
        String dim = Storage.dim();
        for (var a : pol.areas) {
            if (a.dim.equals(dim) && a.columnsInside(b.x1(), b.z1(), b.x2(), b.z2())) return true;     // a round area: its circle
        }
        return false;
    }
}
