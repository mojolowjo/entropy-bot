package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.path.IPathExecutor;
import io.github.mojolowjo.entropybot.Quiet;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepNeeds;
import io.github.mojolowjo.entropybot.gui.Gui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 0.39.z56 item 1 (run O 22:11:46-22:14:51: "crafting furnace at the crafting table -34 70 -55 (7 m)", then for 3 min only
 * three price lines of another walk, then "no progress 180 s at craft furnace 1"; the walk probe covered ToStrip / ToBand /
 * Walk steps only, so the craft's walk to its table had no line at all): a probe for EVERY step the step machine runs. With
 * Tuning {@code debug.stepProbe} 1, into latest.log (never chat), each line {@code [entropybot] probe step:} with the tick:
 * <ul>
 * <li>the step's start, and each sub-phase change - the step's own status words, the job hands' request (a station use,
 *     a placement, a build leg), Baritone's process in control (its display name carries the fork's reason, e.g.
 *     "Interact walking within 4.5 m of crafting_table at ...") and Baritone's goal (the stand goal it was given). At most
 *     one phase line per {@code debug.stepProbePhaseGapTicks} (5 = 4 a second); changes in between are counted on the next;</li>
 * <li>once per {@code debug.probeEveryTicks} (20 = a second) while the step makes no progress (feet within one block of the
 *     same cell - a swimmer's bob is no progress - and the bag unchanged): feet, the phase, the executing path and its
 *     position, a calc in progress, the last path event, the hands' own last words, the bag counts of the step's needs, the
 *     keep list size and the throwaway Baritone may place from the bag;</li>
 * <li>every Baritone path event (a repeat in a row counted) and every cancel the mod sends while the step runs, unless the
 *     walk probe already says them for this step; the step's end and how.</li>
 * </ul>
 * Off (the default): {@link #begin} / {@link #status} / {@link #keep} store one field, the rest return after one Tuning read;
 * no string is built. Never throws into the tick (Quiet.warn). Read only: Baritone's public state (goal, process in control,
 * the executing path and position, the calc in progress), the bag, the throwaway setting and the fork's lease list; no
 * cache-filling call, client thread only. Pure parts in {@link ProbeLines} (JUnit: Z56StepProbeTest). Loader notes: Baritone
 * API (IPathingBehavior, IPathingControlManager, IPathExecutor); vanilla {@code Minecraft.player}; slf4j.
 */
public final class StepProbe {
    private static final Logger LOG = LoggerFactory.getLogger("entropybot");

    private StepProbe() {}

    private static volatile Step step;
    private static volatile String status = "-";
    private static volatile int keep;
    private static boolean fresh;
    private static long tick, startTick, lastPhaseTick = -1, eventTick;
    private static ProbeLines.Phase lastPhase;
    private static int folded, eventRepeats;
    private static String lastEvent;
    private static Map<String, Integer> uses = Map.of();
    private static String tool;
    private static final ProbeLines.Still still = new ProbeLines.Still();

    /** The step probe is on (Tuning debug.stepProbe 1). */
    public static boolean on() { return ProbeLines.stepOn(); }

    /** StepHands: a step started (one field; the probe reads the rest when on). */
    static void begin(Step s) {
        step = s;
        status = "-";
        fresh = true;
    }

    /** StepHands.status: the step's own status words this look (one field). */
    static void status(String why) { status = why == null ? "-" : why; }

    /** StepHands.keep: the run's keep list size at the step's start (one field). */
    static void keep(int n) { keep = n; }

    /** Steps told the hands the step is over (done, failed, refused, cancelled, the run's end): said once, then no more lines. */
    static void end(String how) {
        Step s = step;
        step = null;
        if (s == null || !on()) return;
        try {
            LOG.info("[entropybot] probe step: t={} feet {} | {} {} | ended ({}) after {} s | last status {}", tick, ProbeLines.at(feet()), Step.verb(s), s.text(), how,
                    String.format(java.util.Locale.ROOT, "%.1f", (tick - startTick) / 20.0), status);   // game fact: 20 ticks a second
        } catch (RuntimeException e) {
            Quiet.warn("StepProbe.end: the end line could not be written", e);
        }
    }

    private static int[] feet() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? null : new int[]{p.getBlockX(), p.getBlockY(), p.getBlockZ()};
    }

    private static int[] at(net.minecraft.core.BlockPos p) { return p == null ? null : new int[]{p.getX(), p.getY(), p.getZ()}; }

    /** Core, every client tick: the start / phase / still lines while a step runs and the probe is on. */
    public static void tick(long t) {
        tick = t;
        Step s = step;
        if (s == null || !on()) return;
        try {
            tick0(s, t);
        } catch (RuntimeException | LinkageError e) {
            Quiet.warn("StepProbe.tick: the step line could not be read", e);
        }
    }

    private static void tick0(Step s, long t) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        int[] f = feet();
        IBaritone b = Jobs.baritone();
        String[] hands = io.github.mojolowjo.entropybot.aa.moves.JobHands.peek();
        String control = "Baritone not loaded", goal = "-";
        if (b != null) {
            control = b.getPathingControlManager().mostRecentInControl().map(pr -> pr.displayName()).orElse("none");
            goal = String.valueOf(b.getPathingBehavior().getGoal());
        }
        ProbeLines.Phase now = new ProbeLines.Phase(status, hands[0], control, goal);
        String kind = Step.verb(s);
        if (fresh) {
            fresh = false;
            startTick = t;
            still.reset();
            lastEvent = null;
            lastEventSaid = null;
            eventRepeats = 0;
            StepNeeds n = StepNeeds.of(s);
            Map<String, Integer> m = new LinkedHashMap<>(n.consumes());
            uses = m;
            tool = n.hasTool() ? n.toolKind() : null;
            lastPhase = now;
            lastPhaseTick = t;
            folded = 0;
            LOG.info("[entropybot] probe step: {} | needs {}", ProbeLines.phase(t, f, kind, s.text(), null, now, 0), n.none() ? "none" : n.text());
            return;
        }
        if (!now.equals(lastPhase)) {
            if (t - lastPhaseTick >= ProbeLines.phaseGapTicks()) {
                LOG.info("[entropybot] probe step: {}", ProbeLines.phase(t, f, kind, s.text(), lastPhase, now, folded));
                lastPhase = now;
                lastPhaseTick = t;
                folded = 0;
            } else folded++;
        }
        if (!still.due(t, f, bagSig(p), ProbeLines.everyTicks())) return;
        IPathExecutor cur = b == null ? null : b.getPathingBehavior().getCurrent();
        IPath path = cur == null ? null : cur.getPath();
        Map<String, Integer> inv = Gui.inventory(p);
        String needs = ProbeLines.needs(uses, id -> inv.getOrDefault(id, 0));
        if (tool != null) needs += " | " + tool + " best tier in the bag " + bagTier(p, tool);
        LOG.info("[entropybot] probe step: {}", new ProbeLines.StepStill(t, f, kind, s.text(), still.stillTicks(t), still.anchor(), now,
                path != null, path == null ? null : at(path.getSrc()), path == null ? null : at(path.getDest()), cur == null ? -1 : cur.getPosition(),
                path == null ? 0 : path.length(), b != null && b.getPathingBehavior().getInProgress().isPresent(), lastEvent, t - eventTick,
                hands[1], needs, keep, io.github.mojolowjo.entropybot.engine.RunThrowaway.throwawayInBag(p)).line());
    }

    private static int bagTier(LocalPlayer p, String kind) {
        int best = 0;
        for (var st : p.getInventory().items) {
            if (st.isEmpty()) continue;
            best = Math.max(best, io.github.mojolowjo.entropybot.aa.brain.MiningTiers.toolTier(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), kind));
        }
        return best;
    }

    /** The bag as one number (items and counts): a change is progress. */
    private static int bagSig(LocalPlayer p) {
        var inv = p.getInventory();
        int h = 1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var st = inv.getItem(i);
            if (!st.isEmpty()) h = 31 * h + st.getItem().hashCode() * 7 + st.getCount();
        }
        return h;
    }

    /**
     * BaritoneHook: a path event while a step runs (not said here when the walk probe says it for this step). Path events
     * arrive on the client thread (PathingBehavior.dispatchEvents from onTick; the calc thread only queues them).
     */
    public static void event(String name) {
        Step s = step;
        if (s == null || !on()) return;
        try {
            lastEvent = name;
            eventTick = tick;
            if (WalkProbe.covers()) return;
            if (name.equals(lastEventSaid)) { eventRepeats++; return; }
            String was = lastEventSaid;
            int n = eventRepeats;
            lastEventSaid = name;
            eventRepeats = 0;
            LOG.info("[entropybot] probe step: t={} feet {} | {} {} | path event {}{}", tick, ProbeLines.at(feet()), Step.verb(s), s.text(), name,
                    was != null && n > 0 ? " (" + was + " came " + n + " more times before)" : "");
        } catch (RuntimeException e) {
            Quiet.warn("StepProbe.event: the event line could not be written", e);
        }
    }

    private static String lastEventSaid;

    /** A cancel the mod sends, with its calling frames, while a step runs (not said here when the walk probe says it). */
    static void cancelled(String who, String frames) {
        Step s = step;
        if (s == null || !on() || WalkProbe.covers()) return;
        try {
            LOG.info("[entropybot] probe step: t={} feet {} | {} {} | CANCEL by {} ({})", tick, ProbeLines.at(feet()), Step.verb(s), s.text(), who, frames);
        } catch (RuntimeException e) {
            Quiet.warn("StepProbe.cancelled: the cancel line could not be written", e);
        }
    }

    /** True while a step runs and the probe is on (WalkProbe asks before it builds a cancel's frames for us). */
    static boolean running() { return step != null && on(); }
}
