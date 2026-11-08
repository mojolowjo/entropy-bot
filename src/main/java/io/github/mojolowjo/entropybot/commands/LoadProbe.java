package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.Quiet;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.39.z56 item 3 (the owner sees very low frame rates at times): the load line. With Tuning {@code debug.loadProbe} 1, into
 * latest.log (never chat):
 * <ul>
 * <li>{@code [entropybot] probe load:} once per {@code debug.probeEveryTicks} (20 = a second): the client's fps, the client
 *     tick avg / max ms (NeoForge ClientTickEvent.Pre at HIGHEST to Post at LOWEST: the whole tick, vanilla and every mod),
 *     the mod's part (Core.onClientTick) and its share, the frames and the longest frame interval (RenderFrameEvent.Pre to
 *     the next), the chain's priced paths started / landed / in flight, the fork's segment searches started / landed and
 *     nodes opened (IPricedPath.searchCounts, fork z56), Baritone's own path calcs started / failed (its path events), the
 *     recorder's writer queue, lines written and disk use, JVM heap used / max and GC count and time - each "+" since the
 *     last line; 0.39.z57: then the path memory's asks (IPricedPath.askCounts: asks, answered from memory exact / suffix /
 *     zero / kept no-path, joined in flight, searches started, queued, cancelled, kept answers dropped);</li>
 * <li>{@code [entropybot] probe slowtick:} every client tick longer than {@code debug.slowTickMs} (50 ms): its ms, the mod's
 *     ms by section of Core.onClientTick, the time outside the mod, and what the mod did in it (the running step, the path
 *     events heard, priced paths that landed). Not rate-limited: a slow tick is the fact asked for (at most 20 a second).</li>
 * </ul>
 * Off (the default): {@link #preTick} reads the flag once per tick into {@link #active}; every other entry returns on that
 * field; nothing is measured or built. Never throws into the tick (Quiet.warn). Read only. Pure parts in {@link ProbeLines}
 * (JUnit: Z56LoadProbeTest). Loader notes: NeoForge ClientTickEvent.Pre / Post and RenderFrameEvent.Pre (EntropyBot), vanilla
 * {@code Minecraft.getFps()}, java.lang.management (heap, GC), the fork's IPricedPath.searchCounts; slf4j.
 */
public final class LoadProbe {
    private static final Logger LOG = LoggerFactory.getLogger("entropybot");

    private LoadProbe() {}

    /** 0.39.z78 D6: the planner's numbers for the load line (AaRuntime installs it; null: none). */
    static volatile java.util.function.Supplier<String> planner;

    /** This tick measures (read once in {@link #preTick}). Core and the hooks check only this field. */
    public static volatile boolean active;

    private static final ProbeLines.Load window = new ProbeLines.Load();
    private static long preNs, modStartNs, modNs, frameNs, lastLine = -1;
    private static final long[] marks = new long[ProbeLines.SECTIONS.length - 1];
    private static final List<String> eventsThisTick = new ArrayList<>();
    private static long calcs, calcFails, landedSeen, startedSeen, landedTick, written = -1, gcCount = -1, gcMs;
    private static long[] segSeen;
    /** D11: the fork's join counts at the last line (IPricedPath.joinCounts). */
    private static long[] joinSeen;

    /** EntropyBot, ClientTickEvent.Pre at HIGHEST: the tick's start, and the one flag read of this tick. */
    public static void preTick() {
        active = ProbeLines.loadOn();
        if (!active) return;
        preNs = System.nanoTime();
        modNs = 0;
        java.util.Arrays.fill(marks, 0);
        stepsNs = 0;
        aaNs = 0;
        eventsThisTick.clear();
        landedTick = LivePathPrice.INSTANCE.landed();
    }

    /** 0.39.z62 item 4: this tick's step machine and AA tick inside Commands.tick (ns; 0: not reached). */
    private static long stepsNs, aaNs;

    /** 0.39.z62 item 4 (D3a 3.1 "Core sections split"): Commands.tick's step machine and AA tick took these nanoseconds. */
    public static void inside(long steps, long aa) {
        if (!active) return;
        stepsNs = Math.max(0, steps);
        aaNs = Math.max(0, aa);
    }

    /** EntropyBot: around Core.onClientTick. */
    public static void modStart() { if (active) modStartNs = System.nanoTime(); }

    public static void modEnd() { if (active) modNs = System.nanoTime() - modStartNs; }

    /** Core.onClientTick: section {@code i} ended (ProbeLines.SECTIONS). */
    public static void mark(int i) { if (active && i >= 0 && i < marks.length) marks[i] = System.nanoTime(); }

    /**
     * BaritoneHook: a path event (Baritone's own calcs counted; the slow tick says what it heard). Path events arrive on the
     * client thread (PathingBehavior.dispatchEvents from onTick; the calc thread only queues them).
     */
    public static void pathEvent(String name) {
        if (!active) return;
        if (name.equals("CALC_STARTED") || name.equals("NEXT_SEGMENT_CALC_STARTED")) calcs++;
        if (name.equals("CALC_FAILED") || name.equals("NEXT_CALC_FAILED")) calcFails++;
        if (eventsThisTick.size() < 8) eventsThisTick.add(name);     // structural: a slow-tick line names at most 8 events
    }

    /** EntropyBot, RenderFrameEvent.Pre: one frame's interval. */
    public static void frame() {
        if (!active) { frameNs = 0; return; }
        long now = System.nanoTime();
        if (frameNs > 0) window.frame((now - frameNs) / 1e6);
        frameNs = now;
    }

    /** EntropyBot, ClientTickEvent.Post at LOWEST: the tick's end; the slow-tick line, and the load line once a window. */
    public static void postTick() {
        if (!active || preNs == 0) return;
        try {
            long end = System.nanoTime();
            double tickMs = (end - preNs) / 1e6, modMs = modNs / 1e6;
            window.tick(tickMs, modMs);
            long t = Core.INSTANCE.tick();
            double limit = ProbeLines.slowTickMs();
            if (limit > 0 && tickMs > limit) LOG.info("[entropybot] probe slowtick: {}", ProbeLines.slowTick(t, tickMs, limit, modMs, sections(), did()));
            if (lastLine < 0 || t - lastLine >= ProbeLines.everyTicks()) {
                lastLine = t;
                var pl = planner;
                LOG.info("[entropybot] probe load: {} | {}{} | {}", facts().line(t), ProbeLines.asks(askDelta()), pl == null ? "" : " | " + pl.get(),
                        io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.caveIndex().counts());   // run EE: the cave index's counts
            }
        } catch (RuntimeException | LinkageError e) {
            Quiet.warn("LoadProbe.postTick: the load line could not be written", e);
        }
    }

    /** The mod's sections (ms) from its start mark through the marks set this tick; the last is the rest to its end. */
    private static double[] sections() {
        double[] out = new double[ProbeLines.SECTIONS.length];
        long prev = modStartNs;
        for (int i = 0; i < marks.length; i++) {
            if (marks[i] == 0) continue;               // not reached this tick (out of a world: an early return)
            out[i] = (marks[i] - prev) / 1e6;
            prev = marks[i];
        }
        out[out.length - 1] = Math.max(0, (modStartNs + modNs - prev) / 1e6);
        return out;
    }

    private static String did() {
        var s = Core.INSTANCE.commands;
        String step = "-";
        String typed = null;
        try {
            step = s != null && s.jobs.running() ? s.jobs.job.status : "no job";
            typed = s == null ? null : s.stepRun.steps.stepText();      // 0.39.z62 item 4: the typed step (D3a: "job no job" during typed steps)
        } catch (RuntimeException e) {
            step = "unknown (" + e + ")";
        }
        return "job " + step + (typed == null ? "" : " | typed step " + typed)
                + String.format(java.util.Locale.ROOT, " | inside guard+commands: steps %.1f ms, aa tick %.1f ms (see probe aatick)", stepsNs / 1e6, aaNs / 1e6)
                + " | path events " + (eventsThisTick.isEmpty() ? "none" : eventsThisTick)
                + " | priced paths landed " + (LivePathPrice.INSTANCE.landed() - landedTick);
    }

    private static long[] askSeen;

    /** 0.39.z57: the fork's path-memory counts since the last line (IPricedPath.askCounts); null from a fork without them. */
    private static long[] askDelta() {
        var b = Jobs.baritone();
        if (b == null) return null;
        long[] now;
        try {
            now = b.getPricedPath().askCounts();
        } catch (LinkageError e) {
            return null;                                 // an older fork: the line says "not counted by this fork jar"
        }
        if (now == null || now.length < 10 || now[0] < 0) return null;
        long[] d = new long[now.length];
        for (int i = 0; i < now.length; i++) d[i] = askSeen == null ? 0 : now[i] - askSeen[i];
        askSeen = now;
        return d;
    }

    private static long[] routeSeen;

    /**
     * D11 build 2: the fork's route-memory counts since the last line, read by name (IPricedPath.routeCounts is not in the
     * fork jar yet): {asked, reused, check failed changed, check failed not loaded, saved}; {-1} when the jar lacks it (rule 7).
     */
    static long[] routeDelta(Object pricedPath) {
        long[] r = ProbeLines.routeCounts(pricedPath);
        if (r == null) return new long[]{-1};
        long[] d = new long[5];
        if (routeSeen != null) for (int i = 0; i < 5; i++) d[i] = r[i] - routeSeen[i];
        routeSeen = r.clone();
        return d;
    }

    private static long[] cpuSeen;
    private static long[] segmentSeen;

    /** job-segmod: the fork's first-segment counts since the last line, read by name; {-1} when the jar lacks them (rule 7). */
    static long[] segmentDelta(Object pricedPath) {
        long[] r = ProbeLines.segmentCounts(pricedPath);
        if (r == null) return new long[]{-1};
        long[] d = new long[4];
        if (segmentSeen != null) for (int i = 0; i < 4; i++) d[i] = r[i] - segmentSeen[i];
        segmentSeen = r.clone();
        return d;
    }

    /** CPU job: the fork's cpu counts since the last line, read by name; {-1} when the jar lacks them (rule 7). */
    static long[] cpuDelta(Object pricedPath) {
        long[] r = ProbeLines.cpuCounts(pricedPath);
        if (r == null) return new long[]{-1};
        long[] d = new long[7];
        if (cpuSeen != null) for (int i = 0; i < 7; i++) d[i] = r[i] - cpuSeen[i];
        cpuSeen = r.clone();
        return d;
    }

    private static ProbeLines.LoadFacts facts() {
        Minecraft mc = Minecraft.getInstance();
        long started = LivePathPrice.INSTANCE.started(), landed = LivePathPrice.INSTANCE.landed();
        long ds = started - startedSeen, dl = landed - landedSeen;
        startedSeen = started;
        landedSeen = landed;
        long[] seg = null, joins = null, routes = null, cpu = null, firstSegs = null;
        var b = Jobs.baritone();
        if (b != null) {
            long[] now = b.getPricedPath().searchCounts();
            if (now != null && now.length >= 3 && now[0] >= 0) {
                seg = segSeen == null ? new long[]{0, 0, 0} : new long[]{now[0] - segSeen[0], now[1] - segSeen[1], now[2] - segSeen[2]};
                segSeen = now;
            } else seg = new long[]{-1, -1, -1};
            // D11: the re-join counts since the last line; an older fork without them says so (rule 7: never a silent omission)
            try {
                long[] j = b.getPricedPath().joinCounts();
                if (j != null && j.length >= 4 && j[0] >= 0) {
                    // D11 fix: 9 from a fork with the failure kinds and the queued re-joins, 4 from the first D11 fork
                    int n = j.length >= 9 ? 9 : 4;
                    joins = new long[n];
                    if (joinSeen != null && joinSeen.length == n) for (int i = 0; i < n; i++) joins[i] = j[i] - joinSeen[i];
                    joinSeen = j;
                } else joins = new long[]{-1};
            } catch (LinkageError e) {
                joins = new long[]{-1};
            }
            routes = routeDelta(b.getPricedPath());
            cpu = cpuDelta(b.getPricedPath());
            firstSegs = segmentDelta(b.getPricedPath());
        }
        long[] rec = Core.INSTANCE.recorder.loadCounts();
        long[] recLine = null;
        if (rec != null) {
            recLine = new long[]{rec[0], written < 0 ? 0 : rec[1] - written, rec[2]};
            written = rec[1];
        }
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long n = 0, ms = 0;
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            n += Math.max(0, g.getCollectionCount());
            ms += Math.max(0, g.getCollectionTime());
        }
        long dn = gcCount < 0 ? 0 : n - gcCount, dms = gcCount < 0 ? 0 : ms - gcMs;
        gcCount = n;
        gcMs = ms;
        long c = calcs, f = calcFails;
        calcs = 0;
        calcFails = 0;
        return new ProbeLines.LoadFacts(mc.getFps(), window.takeLine(), ds, dl, LivePathPrice.INSTANCE.inFlight(), seg, c, f, recLine,
                heap.getUsed() >> 20, heap.getMax() >> 20, dn, dms, joins, routes, cpu, firstSegs);      // structural: bytes to MB
    }
}
