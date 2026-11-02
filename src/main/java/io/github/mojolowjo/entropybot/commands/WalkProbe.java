package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.calc.IPathCost;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.Quiet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.39.z54 B: probe lines for one watched run, no fix (run M 19:00:59: the strip leg "to y 16 (priced path)" ended "Baritone's
 * walk ended at y 70 short of y 16 (no path down to the band)" while its priced path was complete; 38 UNREACHABLE "no
 * progress" answers in 3.5 min from near spots). With Tuning {@code debug.walkProbe} 1, into latest.log (never chat):
 * <ul>
 * <li>{@code [entropybot] probe walk:} one line per client tick while a ToStrip / ToBand / Walk step runs (feet, step,
 *     Baritone's goal and the process in control, the executing path and its position, a calc in progress, whether the
 *     path came from the priced path or a fresh search, the movement and what it breaks / places, the break-policy
 *     intent, the reflex hold);</li>
 * <li>{@code [entropybot] probe event:} every Baritone path event while such a step runs (repeats of one event in a row
 *     counted, not printed), and {@code CANCEL by} each cancel the mod sends (Jobs.cancel, SafetyNet.cancel, a reflex,
 *     stop) with its calling frames;</li>
 * <li>{@code [entropybot] probe price:} the priced path at its landing (from, goal, each kept segment's start, end, ticks,
 *     movement kinds, breaks, places, falls);</li>
 * <li>{@code [entropybot] probe unreachable:} every UNREACHABLE / NO_PROGRESS answer with the fork's failure detail.</li>
 * </ul>
 * Off (the default) costs one Tuning read per call and builds no string. Never throws (a failure is said by Quiet.warn).
 * Pure parts in {@link ProbeLines} (JUnit: Z54WalkProbeTest). Loader notes: Baritone API (IPathingBehavior, IPathExecutor,
 * IPath, IPricedPath z54 methods of the entropy fork) and its internal {@code baritone.pathing.movement.Movement} (read
 * only: the public toBreakCached / toPlaceCached, toBreakAll, the fork's placePlanned; never toBreak / toPlace, which fill
 * Baritone's caches from a world read - review of z54); vanilla
 * {@code Minecraft.player / level}; slf4j.
 */
public final class WalkProbe {
    private static final Logger LOG = LoggerFactory.getLogger("entropybot");

    private WalkProbe() {}

    private static volatile String step;
    private static volatile String walkAnswer = "-";
    private static volatile long tick;
    private static String lastEvent;
    private static int eventRepeats;
    private static long costSeen, placeSeen;

    /** The probe is on (Tuning debug.walkProbe 1). */
    public static boolean on() { return ProbeLines.on(); }

    /** Run EQ: the step the probe covers is a place-near (its line carries the fork's face check). */
    private static volatile boolean placeStep;

    /** StepHands: a walk step started (its text); null = no walk step runs. Cheap: one field. */
    static void step(String text) { step(text, false); }

    /** Run EQ: as above; {@code place}: a place-near step (the walk line adds the fork's face check). */
    static void step(String text, boolean place) {
        placeStep = text != null && place;
        step = text;
        walkAnswer = "-";
        lastEvent = null;
        eventRepeats = 0;
    }

    /** LivePathPrice.walk: the kept priced path was offered, or Baritone searches fresh. */
    static void walked(boolean kept) { walkAnswer = kept ? "kept path offered" : "no kept path, fresh search"; }

    private static int[] feet() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? null : new int[]{p.getBlockX(), p.getBlockY(), p.getBlockZ()};
    }

    private static int[] at(BlockPos p) { return p == null ? null : new int[]{p.getX(), p.getY(), p.getZ()}; }

    /** Core, every client tick: the walk line while a walk step runs and the probe is on. */
    public static void tick(long t) {
        tick = t;
        String s = step;
        if (s == null || !on()) return;
        try {
            LOG.info("[entropybot] probe walk: {}", walkLine(s));
        } catch (RuntimeException | LinkageError e) {
            Quiet.warn("WalkProbe.tick: the walk line could not be read", e);
        }
    }

    private static String walkLine(String s) {
        IBaritone b = Jobs.baritone();
        if (b == null) return ProbeLines.event(tick, feet(), "Baritone not loaded", s);
        var pb = b.getPathingBehavior();
        IPathExecutor cur = pb.getCurrent();
        IPath path = cur == null ? null : cur.getPath();
        int pos = cur == null ? -1 : cur.getPosition();
        String move = "-", facts = "";
        if (path != null && pos >= 0 && pos < path.movements().size()) {
            IMovement m = path.movements().get(pos);
            move = m.getClass().getSimpleName();
            facts = m instanceof baritone.pathing.movement.Movement mv ? ProbeLines.move(facts(mv)) : "(not Baritone's Movement: breaks/places unknown)";
        }
        String control = b.getPathingControlManager().mostRecentInControl().map(pr -> pr.displayName()).orElse("none");
        var priced = b.getPricedPath();
        String origin = path == null ? "-" : priced.fromKept(path) ? "kept" : "fresh";
        String line = new ProbeLines.Walk(tick, feet(), s, String.valueOf(pb.getGoal()), control, b.getCustomGoalProcess().isActive(), path != null,
                path == null ? null : at(path.getSrc()), path == null ? null : at(path.getDest()), pos, path == null ? 0 : path.length(),
                pb.getInProgress().isPresent(), origin, priced.offerCounts(), walkAnswer, move, facts,
                String.valueOf(io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.intentNow()), Core.INSTANCE.reflexes.hold()).line();
        // run EN: the fork's last calc of this walk's "no path" end (ICustomGoalProcess.lastCalc, job-vein 4c96772f; any
        // custom-goal walk, reset at each new walk): its type, nodes, best node, start feet, goal
        String calc;
        try {
            var wc = b.getCustomGoalProcess().lastCalc();
            calc = wc == null ? null : wc.line();
        } catch (LinkageError e) {
            calc = "not readable from this fork: " + e;          // rule 15: said, not guessed
        }
        if (!placeStep) return line + ProbeLines.lastCalc(calc);
        // run EQ: a place step's leg - the fork's last failed face check (IBuilderProcess.legFaceCheck, job-vein d1d15445)
        String face;
        try {
            face = ProbeLines.faceCheck(b.getBuilderProcess().legFaceCheck());
        } catch (LinkageError e) {
            face = " | face check not readable from this fork: " + e;   // rule 15: said, not guessed
        }
        return line + ProbeLines.lastCalc(calc) + face;
    }

    /**
     * 0.39.z54 review 1: a movement's facts read without side effects - the public caches as they are (never toBreak /
     * toPlace, which fill them from a world read), the planned break positions and the planned placement (fork z54).
     */
    private static ProbeLines.MoveFacts facts(baritone.pathing.movement.Movement mv) {
        return new ProbeLines.MoveFacts() {
            public List<?> breakCached() { return mv.toBreakCached; }
            public List<?> placeCached() { return mv.toPlaceCached; }
            public int plannedBreaks() { return mv.toBreakAll().length; }
            public boolean plannedPlace() { return mv.placePlanned(); }
        };
    }

    /**
     * BaritoneHook: one path event; printed while a walk step runs and the probe is on (a repeat in a row is counted).
     * Path events arrive on the client thread (PathingBehavior.dispatchEvents from onTick; the calc thread only queues them).
     */
    public static void event(String name) {
        String s = step;
        if (s == null || !on()) return;
        try {
            if (name.equals(lastEvent)) { eventRepeats++; return; }
            String was = lastEvent;
            int n = eventRepeats;
            lastEvent = name;
            eventRepeats = 0;
            LOG.info("[entropybot] probe event: {}{}", ProbeLines.event(tick, feet(), name, s), was != null && n > 0 ? " (" + was + " came " + n + " more times before)" : "");
        } catch (RuntimeException e) {
            Quiet.warn("WalkProbe.event: the event line could not be written", e);
        }
    }

    /** 0.39.z56 item 1: the walk probe says this step's events and cancels itself (the step probe leaves them out). */
    static boolean covers() { return step != null && on(); }

    /**
     * A cancel the mod sends ({@code who}: Jobs.cancel, SafetyNet.cancel, reflex, stop); said with its callers while a walk
     * step runs, and (0.39.z56 item 1) by the step probe while any other step runs.
     */
    public static void cancelled(String who) {
        String s = step;
        boolean walk = s != null && on();
        if (!walk && !StepProbe.running()) return;
        try {
            String frames = StackWalker.getInstance().walk(st -> st.skip(1).limit(4)
                    .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber())
                    .reduce((a, c) -> a + " <- " + c).orElse("?"));
            if (!walk) {
                StepProbe.cancelled(who, frames);
                return;
            }
            LOG.info("[entropybot] probe event: {}", ProbeLines.cancel(tick, feet(), who, frames, s));
        } catch (RuntimeException e) {
            Quiet.warn("WalkProbe.cancelled: the cancel line could not be written", e);
        }
    }

    /** LivePathPrice.land: the priced path's answer for goal from {@code from}; segments read from the fork's kept price. */
    static void price(BlockPos goal, int[] from, IPathCost.Answer a) {
        if (!on()) return;
        try {
            IBaritone b = Jobs.baritone();
            if (b == null) return;
            List<ProbeLines.Segment> segs = new ArrayList<>();
            for (IPath seg : b.getPricedPath().keptSegments(LivePathPrice.goal(goal))) {
                Map<String, Integer> kinds = new HashMap<>();
                int br = 0, pl = 0, falls = 0;
                for (IMovement m : seg.movements()) {
                    String k = m.getClass().getSimpleName().replaceFirst("^Movement", "");
                    kinds.merge(k, 1, Integer::sum);
                    if (m instanceof baritone.pathing.movement.Movement mv) {      // review 1: the plan only, no world read
                        br += mv.toBreakAll().length;
                        if (mv.placePlanned()) pl++;
                    }
                    if (m.getDest().getY() < m.getSrc().getY() - 1) falls++;     // structural: a drop of more than one block
                }
                segs.add(new ProbeLines.Segment(at(seg.getSrc()), at(seg.getDest()), seg.ticksRemainingFrom(0), kinds, br, pl, falls));
            }
            LOG.info("[entropybot] probe price: {}", ProbeLines.price(from, at(goal), a == null ? "no answer" : a.toString(), segs));
        } catch (RuntimeException | LinkageError e) {
            Quiet.warn("WalkProbe.price: the price line could not be read", e);
        }
    }

    /**
     * 0.39.z55 item 1 (run N 21:23:54: the walk to the table placed the furnace's cobblestone at -21 55..59 -11): every block
     * this client places, while the probe is on: the item, how many stay in the hand, kept by the job or not, where.
     */
    public static void placing(net.minecraft.world.item.ItemStack inHand) {
        if (!on()) return;
        // 0.39.z56 item 2b (run O 22:11:20.963-21.759: one crafting table, five "placed" lines 4 ticks apart; the block came at
        // 21.759): BlockItem.place's HEAD runs for every use attempt, also the ones it refuses (canPlace: an entity in the
        // cell, updatePlacementContext / getPlacementState null, placeBlock false), and Baritone's builder clicks every few
        // ticks until one lands. HEAD only remembers the item and the count (the stack reads AIR once a placement empties it);
        // the line is written at RETURN, once, when the result says the block was placed (consumesAction).
        placingItem = inHand.getItem();
        placingCount = inHand.getCount();
    }

    private static net.minecraft.world.item.Item placingItem;
    private static int placingCount;

    /** GuardMixinBlockItem at RETURN of BlockItem.place: one line per real placement (the result consumed the action). */
    public static void placed(net.minecraft.world.InteractionResult result, BlockPos at) {
        if (!on()) return;
        var item = placingItem;
        placingItem = null;
        if (item == null || result == null || !result.consumesAction()) return;
        try {
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
            LOG.info("[entropybot] probe keep: placed {} at {} {} {} | {} left in the hand after it | {} | step {}", id, at.getX(), at.getY(), at.getZ(),
                    Math.max(0, placingCount - 1), io.github.mojolowjo.entropybot.engine.RunThrowaway.placedAs(item), step == null ? "-" : step);
        } catch (RuntimeException | LinkageError e) {
            Quiet.warn("WalkProbe.placed: the place line could not be written", e);
        }
    }

    /** LivePathPrice.land: an UNREACHABLE / NO_PROGRESS answer for goal from {@code from}. */
    static void unreachable(BlockPos goal, int[] from, String reason) {
        if (!on()) return;
        try {
            IBaritone b = Jobs.baritone();
            String failure = b == null ? null : b.getPricedPath().failure(LivePathPrice.goal(goal));
            var mc = Minecraft.getInstance();
            boolean loaded = mc.level != null && mc.level.hasChunk(goal.getX() >> 4, goal.getZ() >> 4);
            var stats = io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.stats;
            long cost = stats.denies(io.github.mojolowjo.entropybot.guard.PolicyStats.SITE_ASTAR_COST), place = stats.denies(io.github.mojolowjo.entropybot.guard.PolicyStats.SITE_ASTAR_PLACE);
            long dc, dp;
            synchronized (WalkProbe.class) {
                dc = cost - costSeen;
                dp = place - placeSeen;
                costSeen = cost;
                placeSeen = place;
            }
            // run HR: which rule refused the breaks (the cap, water, a falling block above...) - the hook's top reasons
            LOG.info("[entropybot] probe unreachable: {}", ProbeLines.unreachable(from, at(goal), reason, failure, loaded, dc, dp,
                    stats.topDenies(io.github.mojolowjo.entropybot.guard.PolicyStats.SITE_ASTAR_COST, 4)));
        } catch (RuntimeException | LinkageError e) {
            Quiet.warn("WalkProbe.unreachable: the line could not be read", e);
        }
    }
}
