package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.calc.IPathCost;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.Quiet;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
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
 * IPath, IPricedPath z54 methods of the entropy fork) and its internal {@code baritone.pathing.movement.Movement} /
 * {@code baritone.utils.BlockStateInterface} (toBreak / toPlace, as ReflexProbe reads internals); vanilla
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
    public static boolean on() { return Tuning.get("debug.walkProbe") >= 1; }

    /** StepHands: a walk step started (its text); null = no walk step runs. Cheap: one field. */
    static void step(String text) {
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
        String move = "-";
        int breaks = 0, places = 0;
        if (path != null && pos >= 0 && pos < path.movements().size()) {
            IMovement m = path.movements().get(pos);
            move = m.getClass().getSimpleName();
            int[] bp = breaksPlaces(b, m);
            breaks = bp[0];
            places = bp[1];
        }
        String control = b.getPathingControlManager().mostRecentInControl().map(pr -> pr.displayName()).orElse("none");
        var priced = b.getPricedPath();
        String origin = path == null ? "-" : priced.fromKept(path) ? "kept" : "fresh";
        return new ProbeLines.Walk(tick, feet(), s, String.valueOf(pb.getGoal()), control, b.getCustomGoalProcess().isActive(), path != null,
                path == null ? null : at(path.getSrc()), path == null ? null : at(path.getDest()), pos, path == null ? 0 : path.length(),
                pb.getInProgress().isPresent(), origin, priced.offerCounts(), walkAnswer, move, breaks, places,
                String.valueOf(io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.intentNow()), Core.INSTANCE.reflexes.hold()).line();
    }

    /** {breaks, places} of one movement as Baritone's own Movement says now (0, 0 for a movement of another kind). */
    private static int[] breaksPlaces(IBaritone b, IMovement m) {
        if (!(m instanceof baritone.pathing.movement.Movement mv)) return new int[]{0, 0};
        var bsi = new baritone.utils.BlockStateInterface(b.getPlayerContext());
        return new int[]{mv.toBreak(bsi).size(), mv.toPlace(bsi).size()};
    }

    /** BaritoneHook: one path event; printed while a walk step runs and the probe is on (a repeat in a row is counted). */
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

    /** A cancel the mod sends ({@code who}: Jobs.cancel, SafetyNet.cancel, reflex, stop); said with its callers while a walk step runs. */
    public static void cancelled(String who) {
        String s = step;
        if (s == null || !on()) return;
        try {
            String frames = StackWalker.getInstance().walk(st -> st.skip(1).limit(4)
                    .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber())
                    .reduce((a, c) -> a + " <- " + c).orElse("?"));
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
                    int[] bp = breaksPlaces(b, m);
                    br += bp[0];
                    pl += bp[1];
                    if (m.getDest().getY() < m.getSrc().getY() - 1) falls++;     // structural: a drop of more than one block
                }
                segs.add(new ProbeLines.Segment(at(seg.getSrc()), at(seg.getDest()), seg.ticksRemainingFrom(0), kinds, br, pl, falls));
            }
            LOG.info("[entropybot] probe price: {}", ProbeLines.price(from, at(goal), a == null ? "no answer" : a.toString(), segs));
        } catch (RuntimeException | LinkageError e) {
            Quiet.warn("WalkProbe.price: the price line could not be read", e);
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
            LOG.info("[entropybot] probe unreachable: {}", ProbeLines.unreachable(from, at(goal), reason, failure, loaded, dc, dp));
        } catch (RuntimeException | LinkageError e) {
            Quiet.warn("WalkProbe.unreachable: the line could not be read", e);
        }
    }
}
