package io.github.mojolowjo.entropybot.commands;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.process.IBuilderProcess;
import baritone.api.schematic.FillSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.MaskSchematic;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.clear.BaritoneMask;
import io.github.mojolowjo.entropybot.clear.BuilderReport;
import io.github.mojolowjo.entropybot.clear.ClearBox;
import io.github.mojolowjo.entropybot.clear.ClearJob;
import io.github.mojolowjo.entropybot.guard.AreaType;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * 0.29.0 (docs/DIG_VIA_BARITONE_PLAN.md, P1): {@code dig x1 y1 z1 x2 y2 z2 [...] via baritone}. Baritone's builder walks,
 * picks where to stand and breaks; the mod decides per cell what may go ({@link BaritoneMask}, the only filter: the
 * builder's click ignores Baritone's own protected lists), takes the guard's break lease, throws junk or makes a base trip
 * when the bag is full, runs the water round when asked, and writes the end report ({@link BuilderReport}).
 *
 * <p>Baritone API only ({@code IBuilderProcess.build}, {@code MaskSchematic}, {@code FillSchematic}, {@code Settings}):
 * loader-neutral. Minecraft types: {@link BlockState}, {@link Blocks#AIR}, {@link BlockPos}. Error catching: the mask is
 * fail-closed and counted; the builder's own pause ("Unable to do it") or 60 s without a block change in the box is "gave
 * up" (the builder is cancelled, it would hold control forever); the settings it changes are put back on every exit
 * ({@link #endDig}, also from {@link Clearing#ended} for stop, death, errors), and the SafetyNet turns breaking off within
 * 10 s once {@link #breaking()} drops.
 */
public final class BuilderDig {
    private static final Logger LOG = LogUtils.getLogger();

    private BuilderDig() {}

    /** The step kind (a "clear" step that Clearing.step hands here). */
    static final String KIND = "builderdig";

    /** The SafetyNet's third owner of allowBreak: true while a builder dig runs Baritone's builder. */
    private static volatile boolean breaking;
    /** The running dig (block changes are counted against it), or null. */
    private static volatile State current;
    /** The last end line, for debug baritone. */
    private static volatile String lastEnd;

    public static boolean breaking() { return breaking; }

    /** Restarts after the builder went idle while blocks were left and it had broken some since its last start (a hold). */
    static final int MAX_RESTARTS = 5;

    static final class Saved {
        boolean buildInLayers, layerOrder, breakFromAbove, remainWithExistingLookDirection;
        int layerHeight;
        double randomLooking, randomLooking113;
    }

    /** The step's state. */
    static final class State {
        ClearJob job;
        BaritoneMask mask;
        final Clearing.LiveWorld world = new Clearing.LiveWorld();
        io.github.mojolowjo.entropybot.clear.LeaseSet leases;
        /** the cells the mask allowed at the start (what "broke N" counts); immutable, read from Baritone's event thread too */
        volatile Set<Long> startCells = Set.of();
        /** the protect-box cells of the box (taken once at the start) */
        Set<Long> fenced = new HashSet<>();
        boolean prepared, running, done, inTrip, caughtSet, noDeposit;
        String tripKind, caught;
        /** written by blockChanged (Baritone's event thread) and the step */
        volatile long lastChange;
        /** a start cell turned to air since the last snapshot: refresh it at the next tick */
        volatile boolean dirty;
        final java.util.concurrent.atomic.AtomicInteger events = new java.util.concurrent.atomic.AtomicInteger();
        long startTick, lastPoll, lastBeat = -1000, lastErrLog = -10000, loggedErrors, lastRefresh = -1000;
        int brokeAtPoll, brokeAtStart, restarts;
        boolean holdSeen;
        double[] startXZ;
        Saved saved;
        /** the drop sweep running now (the builder is stopped meanwhile), what comes after it, the totals */
        io.github.mojolowjo.entropybot.clear.DropSweep sweep;
        BuilderReport.End afterSweep;
        boolean tripAfterSweep, sweptForTrip;
        int sweepInv, picked, dropsLeft, sweeps;
    }

    static Seq.Step step(ClearJob.Options o) {
        Seq.Step s = new Seq.Step("clear");
        s.kind = KIND;
        s.clear = o;
        s.state = new State();
        return s;
    }

    // ---- is the builder there? ----

    /** Null when Baritone's builder and the schematic classes are there, else why not. */
    static String unavailable() {
        try {
            Class.forName("baritone.api.schematic.MaskSchematic");
            Class.forName("baritone.api.schematic.FillSchematic");
            IBaritone b = Jobs.baritone();
            if (b == null) return "Baritone not loaded";
            if (b.getBuilderProcess() == null) return "no builder process";
            return null;
        } catch (Throwable t) {
            return t.toString();
        }
    }

    /** "builder: ok" or "builder: NOT available (why) - dig via baritone falls back to plain dig" (ready line, check). */
    public static String checkLine() {
        String why = unavailable();
        return why == null ? "builder: ok" : "builder: NOT available (" + why + ") - dig via baritone falls back to plain dig";
    }

    /** The check finding when the builder is missing (empty when fine). */
    static List<SelfCheck.Finding> findings() {
        String why = unavailable();
        if (why == null) return List.of();
        return List.of(new SelfCheck.Finding("builder", checkLine(), "dig without via baritone (the plain dig works without it)"));
    }

    /** The status/debug line: "builder: active|paused|idle, broke N, mask calls N, refused K, last change Ts ago". */
    public static String statusLine() {
        String state = "idle";
        try {
            IBaritone b = Jobs.baritone();
            if (b != null && b.getBuilderProcess().isActive()) state = b.getBuilderProcess().isPaused() ? "paused" : "active";
        } catch (Throwable t) {
            state = "unknown (" + t + ")";
        }
        State s = current;
        String line = "builder: " + state;
        if (s != null && s.mask != null) {
            long now = Core.INSTANCE.tick();
            line += ", dig via baritone: broke " + s.brokeAtPoll + ", " + s.mask.counters() + ", last change " + Math.max(0, now - s.lastChange) / 20 + "s ago"
                + (lastProbe.isEmpty() ? "" : ", look: " + lastProbe);
        } else if (lastEnd != null) {
            line += ", last dig via baritone: " + lastEnd;
        }
        return line;
    }

    // ---- the verb ----

    /**
     * DigCommands.dig with "via baritone", after its checks (areas, safe areas, size, force): the leases now (a refusal is
     * the reply), then the Seq [restock trips] [this step]. The builder starts at the step's first tick.
     */
    static String start(Commands c, LocalPlayer p, ClearJob.Options o, List<Seq.Step> before) {
        String why = unavailable();
        if (why != null) {
            LOG.warn("[entropybot] dig via baritone: builder not available ({}), plain dig instead", why);
            o.viaBaritone(false);
            String r = DigCommands.startClear(c, p, o, before);
            return r + " - builder: NOT available (" + why + "), so the plain dig";
        }
        String bad = settingsProblem();
        if (bad != null) return "error: dig via baritone needs Baritone's " + bad + " - b set it back (or dig without via baritone)";
        Seq.Step st = step(o);
        State s = (State) st.state;
        String err = prepare(s, p, o, c);
        if (err != null) return err;
        Seq seq = new Seq(c.jobs, c.storage, s.job.label, List.of(), "always");
        List<Seq.Step> steps = new ArrayList<>();
        if (before != null && !before.isEmpty()) {
            steps.addAll(before);
            s.inTrip = true;
            s.tripKind = "restock";
        }
        steps.add(st);
        seq.splice(0, steps);
        c.jobs.startSeq(seq, "always");
        c.jobs.job.holdOnFight = true;
        seq.setStatus(s.job.label + ": starting (via baritone)");
        return "started: " + s.job.label + " (via baritone: its builder walks and breaks, my rules pick the blocks; "
                + s.startCells.size() + " to break)";
    }

    /** Builder settings that would move or repeat the schematic (the mask would then judge the wrong cells), or null. */
    static String settingsProblem() {
        try {
            Settings s = BaritoneAPI.getSettings();
            if (s.schematicOrientationX.value || s.schematicOrientationY.value || s.schematicOrientationZ.value) return "schematicOrientationX/Y/Z off";
            if (s.buildSchematicMirror.value != net.minecraft.world.level.block.Mirror.NONE) return "buildSchematicMirror NONE";
            if (s.buildSchematicRotation.value != net.minecraft.world.level.block.Rotation.NONE) return "buildSchematicRotation NONE";
            if (!Vec3i.ZERO.equals(s.buildRepeat.value)) return "buildRepeat 0,0,0";
            return null;
        } catch (Throwable t) {
            return "settings readable (" + t + ")";
        }
    }

    /** The job, the mask, the fenced cells, the start snapshot and the break lease. Null or the error. */
    static String prepare(State s, LocalPlayer p, ClearJob.Options o, Commands c) {
        IBaritone b = Jobs.baritone();
        if (b == null) return "error: baritone not loaded";
        Jobs.cancel(b);
        Jobs.safeSettings();
        s.world.set(p);
        s.startXZ = new double[]{p.getX(), p.getZ()};
        s.job = ClearJob.start(o, null, null);
        s.job.standOk = Clearing.standCheck(c);
        ClearBox box = s.job.box;
        String dim = Storage.dim();
        GuardCore g = Core.INSTANCE.guard.core;
        boolean denied = GuardCore.DENIED_DIMS.contains(dim);
        try {
            var pol = g.policy();
            for (int x = box.x1(); x <= box.x2(); x++)
                for (int y = box.y1(); y <= box.y2(); y++)
                    for (int z = box.z1(); z <= box.z2(); z++)
                        if (denied || pol.protectAt(dim, x, y, z) != null || pol.typeAt(dim, x, y, z) == AreaType.SAFE) s.fenced.add(BaritoneMask.packed(x, y, z));
        } catch (RuntimeException e) {
            return "error: couldn't read the guard's protect boxes: " + e;
        }
        Set<Long> fenced = s.fenced;
        s.mask = new BaritoneMask(box, s.job.force, s.job.keepOres, (x, y, z) -> fenced.contains(BaritoneMask.packed(x, y, z)) ? "protect box or safe area" : null);
        s.startCells = Set.copyOf(s.mask.digCells(s.world));
        s.mask.refresh(s.world);
        s.leases = Clearing.newLeases();
        String le = s.leases.take(s.job.label, s.job.breakLeaseBox(), false, s.job.force);
        if (le != null) {
            s.leases.releaseAll();
            return le;
        }
        s.prepared = true;
        LOG.info("[entropybot] dig via baritone: {} - {} cells to break, {} fenced", box, s.startCells.size(), s.fenced.size());
        return null;
    }

    // ---- the builder ----

    /** Settings for the job (saved first): breaking on (path digging), placing off, top layer first, the cell under the feet. */
    static void applySettings(State s) {
        Settings st = BaritoneAPI.getSettings();
        if (s.saved == null) {
            Saved sv = new Saved();
            sv.buildInLayers = st.buildInLayers.value;
            sv.layerOrder = st.layerOrder.value;
            sv.breakFromAbove = st.breakFromAbove.value;
            sv.layerHeight = st.layerHeight.value;
            sv.remainWithExistingLookDirection = st.remainWithExistingLookDirection.value;
            sv.randomLooking = st.randomLooking.value;
            sv.randomLooking113 = st.randomLooking113.value;
            s.saved = sv;
        }
        st.allowBreak.value = true;
        st.allowPlace.value = false;
        st.buildInLayers.value = true;
        st.layerOrder.value = true;
        st.layerHeight.value = 1;
        st.breakFromAbove.value = true;
        st.buildIgnoreExisting.value = false;
        st.allowInventory.value = true;
        // live check 2026-10-13: with it on, the in-place click on a block it already looks at never lands in this pack
        st.remainWithExistingLookDirection.value = false;
        // live probe 2026-10-13 (plan section 9): the aim jitter (randomLooking113, about 1 degree a tick) makes a block seen
        // at a grazing angle reachable on one tick and not the next, so toBreakNearPlayer flips between two targets every
        // few ticks and the break progress starts over each time; off for the job
        st.randomLooking.value = 0d;
        st.randomLooking113.value = 0d;
    }

    /** Puts every setting this job changed back (safe travel settings, the builder's own as they were). */
    static void restoreSettings(State s) {
        Jobs.safeSettings();
        try {
            Settings st = BaritoneAPI.getSettings();
            st.allowInventory.value = false;
            st.buildIgnoreExisting.value = false;
            if (s.saved != null) {
                st.buildInLayers.value = s.saved.buildInLayers;
                st.layerOrder.value = s.saved.layerOrder;
                st.breakFromAbove.value = s.saved.breakFromAbove;
                st.layerHeight.value = s.saved.layerHeight;
                st.remainWithExistingLookDirection.value = s.saved.remainWithExistingLookDirection;
                st.randomLooking.value = s.saved.randomLooking;
                st.randomLooking113.value = s.saved.randomLooking113;
            }
        } catch (Throwable t) {
            LOG.warn("[entropybot] dig via baritone: couldn't restore settings: {}", t.toString());
        }
    }

    /** The masked air fill over the box, handed to Baritone's builder. */
    static void startBuilder(State s, long now) {
        IBaritone b = Jobs.baritone();
        if (b == null) throw new IllegalStateException("baritone not loaded");
        ClearBox box = s.job.box;
        int[] sz = BaritoneMask.size(box);
        final int ox = box.x1(), oy = box.y1(), oz = box.z1();
        final BaritoneMask mask = s.mask;
        mask.refresh(s.world);
        s.lastRefresh = now;
        ISchematic fill = new FillSchematic(sz[0], sz[1], sz[2], Blocks.AIR.defaultBlockState());
        ISchematic masked = new MaskSchematic(fill) {
            @Override
            protected boolean partOfMask(int x, int y, int z, BlockState currentState) {
                // Baritone calls this from its path thread too: only the client-thread snapshot is read, never the world;
                // relative to the origin (Baritone subtracts it); fail-closed (BaritoneMask.partOf never throws)
                return mask.partOf(ox, oy, oz, x, y, z);
            }
        };
        applySettings(s);
        breaking = true;
        current = s;
        b.getBuilderProcess().build("dig via baritone", masked, new BlockPos(ox, oy, oz));
        s.running = true;
        s.startTick = now;
        s.lastChange = now;
        s.brokeAtStart = s.brokeAtPoll;
    }

    /** Cancels the builder (a paused one holds control forever) and puts the settings back. */
    static void stopBuilder(State s) {
        breaking = false;
        s.running = false;
        IBaritone b = Jobs.baritone();
        if (b != null) {
            try {
                if (b.getBuilderProcess().isActive()) b.getBuilderProcess().onLostControl();
            } catch (Throwable ignored) {}
            io.github.mojolowjo.entropybot.baritone.SafetyNet.cancel(b);
        }
        restoreSettings(s);
    }

    /** Blocks of the start snapshot that are air (or liquid) now. */
    static int broke(State s) {
        int n = 0;
        for (long k : s.startCells) {
            int x = (int) (k >> 38), z = (int) ((k << 26) >> 38), y = (int) ((k << 52) >> 52);
            try {
                if (s.world.air(x, y, z) || s.world.fluid(x, y, z)) n++;
            } catch (RuntimeException ignored) {}
        }
        return n;
    }

    // ---- the step ----

    static String run(Seq seq, Seq.Step st, LocalPlayer p) {
        if (!(st.state instanceof State s)) return "the dig via baritone was not set up";
        if (s.done) return "next";
        long now = seq.now();
        s.world.set(p);
        if (!s.prepared) {
            // a re-run after the water round: set up again (a fresh job, mask and lease)
            String err = prepare(s, p, st.clear, seq.jobs.core().commands);
            if (err != null) return err.replaceFirst("^error: ", "");
        }
        if (s.sweep != null) return sweepTick(seq, st, s, p, now);
        if (!s.running) {
            if (s.inTrip) {
                String how = s.caughtSet ? s.caught : null;
                if (how != null) LOG.info("[entropybot] dig via baritone: the {} trip failed ({}), digging on", s.tripKind, how);
                s.inTrip = false;
                s.caught = null;
                s.caughtSet = false;
                s.tripKind = null;
            }
            String le = beat(s, now, true);
            if (le != null) return end(seq, st, s, p, null, "the guard: " + le.replaceFirst("^error: ", ""));
            try {
                startBuilder(s, now);
            } catch (Throwable t) {
                return end(seq, st, s, p, null, "couldn't start Baritone's builder: " + t);
            }
            seq.setStatus(s.job.label + ": digging (via baritone)");
            return "wait";
        }
        String le0 = beat(s, now, false);
        if (le0 != null) return end(seq, st, s, p, null, "the guard: " + le0.replaceFirst("^error: ", ""));
        // the snapshot Baritone reads: every 10 ticks, or at once after a start cell was broken
        if (s.dirty || now - s.lastRefresh >= 10) {
            s.dirty = false;
            s.lastRefresh = now;
            s.mask.refresh(s.world);
        }
        long errs = s.mask.errors.get();
        if (errs > s.loggedErrors && now - s.lastErrLog >= 200) {
            LOG.warn("[entropybot] dig via baritone: mask errors {} (first: {}) - those blocks are left alone", errs, s.mask.firstError);
            s.loggedErrors = errs;
            s.lastErrLog = now;
        }
        probe(p, now - s.lastPoll >= 20);
        if (now - s.lastPoll < 20) return "wait";
        s.lastPoll = now;
        // the fallback count (block-change events may not come for our own breaks): the start cells that are air now
        int b = broke(s);
        if (b > s.brokeAtPoll) {
            s.lastChange = Math.max(s.lastChange, now);
            s.restarts = 0;                                 // progress: the restart cap counts from here
        }
        s.brokeAtPoll = b;
        seq.setStatus(s.job.label + ": broke " + b + " of " + s.startCells.size() + " (via baritone; " + s.mask.counters() + ")");
        // the bag is full: the builder stops, junk is thrown or taken home, then it starts again
        if (!s.noDeposit && p.getInventory().getFreeSlot() < 0) {
            // first what still fits (stacks with room), then the trip
            if (!s.sweptForTrip) return startSweep(seq, s, p, now, null, true);
            return trip(seq, s, p);
        }
        IBaritone bar = Jobs.baritone();
        if (bar == null) return end(seq, st, s, p, null, "Baritone went away");
        IBuilderProcess bp = bar.getBuilderProcess();
        if (now - s.startTick < 40) return "wait";              // let it plan its first path
        BuilderReport.End e = BuilderReport.watch(bp.isActive(), bp.isPaused(), now - s.lastChange);
        if (e == null) return "wait";
        if (e == BuilderReport.End.IDLE) {
            // idle with blocks it may break still there: cancelled from outside (a fight hold), or it lost them; once it
            // broke something since its last start, it goes again (a few times)
            BaritoneMask.Leftover left = s.mask.leftover(s.world);
            if (left.unreachable() > 0 && s.restarts < MAX_RESTARTS && (s.brokeAtPoll > s.brokeAtStart || s.holdSeen)) {
                s.restarts++;
                s.holdSeen = false;
                LOG.info("[entropybot] dig via baritone: the builder went idle with {} left, starting it again ({})", left.unreachable(), s.restarts);
                stopBuilder(s);
                return "wait";                              // next tick: startBuilder
            }
        }
        return startSweep(seq, s, p, now, e, false);        // the drops first, then the report
    }

    // ---- the drop sweep (Baritone's builder never walks over its drops) ----

    private static String startSweep(Seq seq, State s, LocalPlayer p, long now, BuilderReport.End e, boolean trip) {
        stopBuilder(s);
        s.sweep = new io.github.mojolowjo.entropybot.clear.DropSweep(s.job.box, now);
        s.afterSweep = e;
        s.tripAfterSweep = trip;
        s.sweepInv = invTotal(p);
        s.sweeps++;
        seq.setStatus(s.job.label + ": picking up the drops (via baritone)");
        return "wait";
    }

    private static String sweepTick(Seq seq, Seq.Step st, State s, LocalPlayer p, long now) {
        var sw = s.sweep;
        var cur = sw.current();
        boolean alive = cur != null && dropEntity(cur.id()) != null;
        IBaritone b = Jobs.baritone();
        boolean idle = b == null || Jobs.idle(b);
        var a = sw.tick(now, seenDrops(s, p, now), alive, idle);
        if (a.kind() == io.github.mojolowjo.entropybot.clear.DropSweep.Kind.WALK) {
            if (b != null) {
                Jobs.safeSettings();
                b.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalBlock(new BlockPos(a.item().x(), a.item().y(), a.item().z())));
            }
            return "wait";
        }
        if (a.kind() == io.github.mojolowjo.entropybot.clear.DropSweep.Kind.WAIT) return "wait";
        if (b != null) Jobs.cancel(b);
        s.picked += Math.max(0, invTotal(p) - s.sweepInv);
        s.dropsLeft = sw.capped ? sw.left : 0;
        LOG.info("[entropybot] dig via baritone: drop sweep {}: {} walks, {}", s.sweeps, sw.walks, sw.note(s.picked));
        s.sweep = null;
        if (s.tripAfterSweep) {
            s.sweptForTrip = true;
            if (p.getInventory().getFreeSlot() >= 0) return "wait";     // room again: dig on (next tick starts the builder)
            return trip(seq, s, p);
        }
        return end(seq, st, s, p, s.afterSweep, null);
    }

    /** The drops the plain dig would chase (its dropWanted and junk-drop rules), in the sweep's area, with room in the bag. */
    private static List<io.github.mojolowjo.entropybot.clear.DropSweep.Item> seenDrops(State s, LocalPlayer p, long now) {
        List<io.github.mojolowjo.entropybot.clear.DropSweep.Item> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return out;
        Map<String, Integer> inv = null;
        var pinv = p.getInventory();
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.item.ItemEntity ie) || !e.isAlive()) continue;
            int x = (int) Math.floor(e.getX()), y = (int) Math.floor(e.getY() + 0.01), z = (int) Math.floor(e.getZ());
            if (!s.sweep.covers(x, y, z) || !s.job.dropWanted(x, y, z)) continue;
            if (s.job.junkDrop) {
                if (inv == null) inv = io.github.mojolowjo.entropybot.gui.Gui.inventory(p);
                if (!io.github.mojolowjo.entropybot.clear.JunkDrop.chase(io.github.mojolowjo.entropybot.gui.Gui.itemId(ie.getItem()), s.job, inv, x, y, z, now)) continue;
            }
            if (pinv.getFreeSlot() < 0 && pinv.getSlotWithRemainingSpace(ie.getItem()) < 0) continue;
            out.add(new io.github.mojolowjo.entropybot.clear.DropSweep.Item(String.valueOf(e.getId()), x, y, z, e.distanceTo(p)));
        }
        return out;
    }

    private static net.minecraft.world.entity.item.ItemEntity dropEntity(String id) {
        try {
            var e = Minecraft.getInstance().level.getEntity(Integer.parseInt(id));
            return e instanceof net.minecraft.world.entity.item.ItemEntity ie && ie.isAlive() && !ie.isRemoved() ? ie : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static int invTotal(LocalPlayer p) {
        int n = 0;
        for (int c : io.github.mojolowjo.entropybot.gui.Gui.inventory(p).values()) n += c;
        return n;
    }

    /** Debug (DIG_VIA_BARITONE_PLAN section 9): the look/click state while the builder runs, every 20 ticks. */
    /** Off: one sample every 20 ticks into {@link #lastProbe} (shown by debug baritone), no log. On: a log line per target change. */
    public static volatile boolean probeOn = false;
    public static volatile String lastProbe = "";

    private static String lastProbeHit = "";

    static void probe(LocalPlayer p, boolean periodic) {
        if (!probeOn && !periodic) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            IBaritone bar = Jobs.baritone();
            boolean forced = bar != null && bar.getInputOverrideHandler().isInputForcedDown(baritone.api.utils.input.Input.CLICK_LEFT);
            String hr = "null";
            boolean veto = false;
            if (mc.hitResult != null) {
                hr = mc.hitResult.getType().toString();
                if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh) {
                    BlockPos hp = bh.getBlockPos();
                    hr += " " + hp.getX() + " " + hp.getY() + " " + hp.getZ() + " " + bh.getDirection()
                        + " " + p.level().getBlockState(hp).getBlock().getDescriptionId();
                    veto = io.github.mojolowjo.entropybot.guard.Guard.INSTANCE.vetoBreak(hp);
                }
            }
            String target = "?";
            if (bar != null) {
                try {
                    Object lb = bar.getLookBehavior();
                    java.lang.reflect.Field f = lb.getClass().getDeclaredField("target");
                    f.setAccessible(true);
                    Object t = f.get(lb);
                    target = String.valueOf(t);
                    if (t != null) {
                        java.lang.reflect.Field rf = t.getClass().getDeclaredField("rotation");
                        rf.setAccessible(true);
                        Object r = rf.get(t);
                        if (r instanceof baritone.api.utils.Rotation rot) target = String.format("%.1f/%.1f", rot.getYaw(), rot.getPitch());
                    }
                } catch (Throwable ignored) {}
            }
            String btr = "?";
            boolean bveto = false;
            if (bar != null) {
                try {
                    net.minecraft.world.phys.HitResult t = bar.getPlayerContext().objectMouseOver();
                    btr = t == null ? "null" : t.getType().toString();
                    if (t instanceof net.minecraft.world.phys.BlockHitResult bh) {
                        BlockPos hp = bh.getBlockPos();
                        btr += " " + hp.getX() + " " + hp.getY() + " " + hp.getZ();
                        String key = hp.toShortString();
                        if (!periodic && key.equals(lastProbeHit)) return;
                        lastProbeHit = key;
                        bveto = io.github.mojolowjo.entropybot.guard.Guard.INSTANCE.vetoBreak(hp);
                    }
                    baritone.api.utils.Rotation er = bar.getPlayerContext().playerRotations();
                    btr += String.format(" rot %.1f/%.1f", er.getYaw(), er.getPitch());
                } catch (Throwable x) { btr = "err " + x; }
            }
            String prog = "?";
            try {
                java.lang.reflect.Field pf = net.minecraft.client.multiplayer.MultiPlayerGameMode.class.getDeclaredField("destroyProgress");
                pf.setAccessible(true);
                prog = String.format("%.2f", pf.getFloat(mc.gameMode));
            } catch (Throwable ignored) {}
            btr += " progress " + prog + " held " + p.getMainHandItem().getItem();
            Settings st = BaritoneAPI.getSettings();
            String line = String.format("yaw %.1f pitch %.1f feet %d %d %d ground %s target %s mcHit %s veto %s bHit %s bveto %s forcedLeft %s keyAttack %s destroying %s screen %s freeLook %s blockFreeLook %s anti %s",
                p.getYRot(), p.getXRot(), p.blockPosition().getX(), p.blockPosition().getY(), p.blockPosition().getZ(), p.onGround(),
                target, hr, veto, btr, bveto, forced, mc.options.keyAttack.isDown(), mc.gameMode != null && mc.gameMode.isDestroying(),
                mc.screen == null ? "none" : mc.screen.getClass().getSimpleName(),
                st.freeLook.value, st.blockFreeLook.value, st.antiCheatCompatibility.value);
            lastProbe = line;
            if (probeOn) LOG.info("[entropybot] dig via baritone probe: {}", line);
        } catch (Throwable t) {
            LOG.info("[entropybot] dig via baritone probe failed: {}", t.toString());
        }
    }

    /**
     * The lease heartbeat, every 20 ticks whether the builder runs or not (a long restock trip before the first run tick
     * must not lose it); a lease that died is taken again, and once more before giving up. force: now. Null or the error.
     */
    static String beat(State s, long now, boolean force) {
        if (!force && now - s.lastBeat < 20) return null;
        s.lastBeat = now;
        s.leases.beat();
        String le = s.leases.ensure();
        if (le != null) {
            LOG.info("[entropybot] dig via baritone: lease lost ({}), taking it again", le);
            le = s.leases.ensure();
        }
        return le;
    }

    /** The bag is full: junk thrown here ("junk drop") or a base trip; the builder waits cancelled. */
    private static String trip(Seq seq, State s, LocalPlayer p) {
        List<Seq.Step> steps;
        String kind;
        if (s.job.junkDrop) {
            steps = List.of(FloorSteps.junkStep(s.job, s.startXZ));
            kind = "junk";
        } else {
            steps = Clearing.depositSteps(seq.storage, p, s.job);
            kind = "deposit";
            if (steps == null) {
                s.noDeposit = true;
                LOG.info("[entropybot] dig via baritone: inventory full and nothing to put away - drops stay on the ground");
                return "wait";
            }
        }
        stopBuilder(s);
        s.sweptForTrip = false;                             // after this trip, the next full bag sweeps first again
        seq.splice(seq.idx, steps);
        s.inTrip = true;
        s.tripKind = kind;
        s.caught = null;
        s.caughtSet = false;
        seq.stage = null;
        seq.stepStart = seq.now();
        LOG.info("[entropybot] dig via baritone: inventory full, {} trip first", kind);
        return "wait";
    }

    /**
     * The end: builder cancelled, settings back, the leases released, the box re-scanned, the report. gave: how it ended
     * (null: an error, text in {@code stop}). The water round comes next when the dig asked for water and water is left.
     */
    private static String end(Seq seq, Seq.Step st, State s, LocalPlayer p, BuilderReport.End gave, String stop) {
        stopBuilder(s);
        s.done = true;
        current = null;
        if (s.leases != null) s.leases.releaseAll();
        String msg;
        int broke = 0;
        BaritoneMask.Leftover left = null;
        try {
            s.world.set(p);
            broke = broke(s);
            left = s.mask.leftover(s.world);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] dig via baritone: the end re-scan failed: {}", e.toString());
        }
        if (stop != null || left == null) {
            msg = "stopped: " + s.job.label + " - " + (stop != null ? stop : "couldn't re-scan the box") + " - broke " + broke;
        } else {
            msg = BuilderReport.text(s.job.label, broke, left, gave, s.mask.errors.get());
        }
        if (s.sweeps > 0) msg += "; " + io.github.mojolowjo.entropybot.clear.DropSweep.text(s.picked, s.dropsLeft);
        s.job.broken = broke;
        if (left != null) s.job.skip.putAll(left.waterCells);
        int leftN = 0;
        if (left != null) for (int n : left.counts.values()) leftN += n;
        lastEnd = msg.length() > 160 ? msg.substring(0, 157) + "..." : msg;
        if (gave != null && BuilderReport.gaveUp(gave)) {
            LOG.info("[entropybot] dig via baritone: gave up ({}) in {}: {}", gave, s.job.box, msg);
            try { Core.INSTANCE.events.push("job", "dig via baritone gave up: " + lastEnd, null); } catch (Throwable ignored) {}
        }
        if (WaterSteps.wants(st, msg)) {
            st.cleared = new Clearing.Outcome(false, broke, leftN, new LinkedHashMap<>(), new ArrayList<>(), msg);
            LOG.info("[entropybot] dig via baritone: {} - looking at the water", msg);
            seq.splice(seq.idx + 1, List.of(WaterSteps.step(st, s.job)));
            return "next";
        }
        msg = WaterSteps.endText(st, s.job, msg);
        st.cleared = new Clearing.Outcome(msg.startsWith("done") || msg.startsWith("ok"), broke, leftN, Map.of(), List.of(), msg);
        LOG.info("[entropybot] dig via baritone: {}", msg);
        seq.jobs.finish(msg);
        return "wait";
    }

    // ---- hooks ----

    /** Clearing.ended: the job ended some other way (stop, death, an error): builder off, settings back, leases gone. */
    static void ended(State s) {
        if (s.done) return;
        s.done = true;
        if (current == s) current = null;
        stopBuilder(s);
        if (s.leases != null) s.leases.releaseAll();
    }

    /** Clearing.caught: a trip this dig spliced in failed: the steps up to the dig go, the dig hears it and goes on. */
    static boolean caught(Seq seq, String r) {
        for (int j = seq.idx + 1; j < seq.steps.size(); j++) {
            Seq.Step c = seq.steps.get(j);
            if (!(c.state instanceof State s) || !s.inTrip) continue;
            for (int k = seq.idx; k < j; k++) seq.steps.remove(seq.idx);
            s.caught = r;
            s.caughtSet = true;
            seq.stage = null;
            seq.stepStart = seq.now();
            return true;
        }
        return false;
    }

    /** Seq.afterHold: a fight held the job (the reflex may have cancelled the builder): it may start again. */
    static void afterHold(Seq.Step st, long held) {
        if (st.state instanceof State s) {
            s.holdSeen = true;
            s.lastChange += held;
            s.startTick += held;
        }
    }

    /**
     * BaritoneHook.onBlockChange (Baritone's event thread): a start cell of the running dig turned to air (the stall
     * clock starts again, the snapshot is refreshed). Water flowing or gravel falling is no progress.
     */
    public static void blockChanged(int x, int y, int z, boolean air) {
        State s = current;
        if (s == null || !air || !s.startCells.contains(BaritoneMask.packed(x, y, z))) return;
        s.events.incrementAndGet();
        s.dirty = true;
        try { s.lastChange = Core.INSTANCE.tick(); } catch (Throwable ignored) {}
    }
}
