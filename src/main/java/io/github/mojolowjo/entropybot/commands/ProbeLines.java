package io.github.mojolowjo.entropybot.commands;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 0.39.z54 B (run M 19:00:59: "Baritone's walk ended at y 70 short of y 16 (no path down to the band)" while the priced
 * path to y 16 was complete; 38 UNREACHABLE "no progress" answers in 3.5 min): the probe's line texts from plain values,
 * so the game side ({@link WalkProbe}) only gathers facts. No fix and no decision here: facts for one watched run.
 * 0.39.z56: the step probe's ({@link StepProbe}: still rule, phase and still lines) and the load probe's ({@link LoadProbe}:
 * window, load and slow-tick lines) texts too.
 * Pure (JUnit: Z54WalkProbeTest, Z56StepProbeTest, Z56LoadProbeTest). Loader notes: none.
 */
public final class ProbeLines {
    private ProbeLines() {}

    /** The probe is on: Tuning debug.walkProbe 1 (WalkProbe's gate; every probe entry returns at once when this is false). */
    public static boolean on() { return io.github.mojolowjo.entropybot.aa.brain.Tuning.get("debug.walkProbe") >= 1; }

    /** 0.39.z56 item 1: the step probe is on (Tuning debug.stepProbe 1; StepProbe's gate). */
    public static boolean stepOn() { return io.github.mojolowjo.entropybot.aa.brain.Tuning.get("debug.stepProbe") >= 1; }

    /** 0.39.z77 mineintent: the strip's refuse probe is on (the strip probe debug.walkProbe, or debug.stepProbe; both off by default). */
    public static boolean stripRefuseOn() { return on() || stepOn(); }

    /** 0.39.z77 hotbar: the tool probe is on (Tuning debug.toolProbe 1; ToolProbe's gate; off by default). */
    public static boolean toolOn() { return io.github.mojolowjo.entropybot.aa.brain.Tuning.get("debug.toolProbe") >= 1; }

    /** The stall site probe (runs GZ / HB, 2026-11-01): names as runs - "stone x4, air x2, lava"; "" for none. Pure. */
    public static String runs(List<String> names) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < names.size()) {
            String n = names.get(i);
            int j = i;
            while (j < names.size() && names.get(j).equals(n)) j++;
            if (sb.length() > 0) sb.append(", ");
            sb.append(n);
            if (j - i > 1) sb.append(" x").append(j - i);
            i = j;
        }
        return sb.toString();
    }

    // ---- 0.39.z77 hotbar: the tool probe (candidate-strip-pace, runs CW-DI: no line named the tool in hand) ----

    /**
     * When a tool line is due: each new break stage of the block being broken (another cell, or another stage on the
     * same cell); no block being broken (-1) says nothing and forgets the last one, so the next block's first stage is due.
     */
    public static final class Stages {
        private String cell;
        private int stage = -1;

        public boolean due(String atCell, int now) {
            if (now < 0 || atCell == null) { cell = null; stage = -1; return false; }
            if (atCell.equals(cell) && now == stage) return false;
            cell = atCell;
            stage = now;
            return true;
        }
    }

    /** One item as the tool line names it: its id (short), the bag slot (1-36, the hotbar 1-9) and its speed on the block. */
    public record Held(String id, int slot, double speed) {
        String text() { return (id == null || id.isEmpty() ? "empty hand" : shortId(id)) + " (" + (slot < 9 ? "hotbar " : "bag slot ") + (slot + 1) + String.format(Locale.ROOT, ", speed %.1f)", speed); }   // game fact: the hotbar is inventory slots 0-8
    }

    /** True when the bag holds a faster item for the block than the one in hand (the run summary counts these). */
    public static boolean lower(Held hand, Held best) {
        return hand != null && best != null && best.speed() > hand.speed() && best.slot() != hand.slot();
    }

    /**
     * "t=120 stage 3 at 10 11 12 stone | hand wooden_pickaxe (hotbar 1, speed 2.0) | bag best stone_pickaxe (bag slot 24,
     * speed 4.0) | LOWER than the bag's | step strip ..."; "best in hand" when the hand holds the bag's fastest.
     */
    public static String tool(long tick, int stage, String cell, String block, Held hand, Held best, String step) {
        String b = best == null || !lower(hand, best) ? "best in hand" : "bag best " + best.text() + " | LOWER than the bag's";
        return "t=" + tick + " stage " + stage + " at " + cell + " " + shortId(block) + " | hand " + (hand == null ? "-" : hand.text()) + " | " + b
                + " | step " + (step == null ? "-" : step);
    }

    private static String shortId(String id) { return id == null ? "-" : id.replaceFirst("^minecraft:", ""); }

    /** 0.39.z56 item 3: the load probe is on (Tuning debug.loadProbe 1; LoadProbe's gate). */
    public static boolean loadOn() { return io.github.mojolowjo.entropybot.aa.brain.Tuning.get("debug.loadProbe") >= 1; }

    /** 0.39.z56: client ticks between a step probe's still lines and between load lines (Tuning debug.probeEveryTicks, 20 = 1 s). */
    public static long everyTicks() { return Math.max(1, io.github.mojolowjo.entropybot.aa.brain.Tuning.l("debug.probeEveryTicks")); }

    /** 0.39.z56: the fewest client ticks between two step phase lines (Tuning debug.stepProbePhaseGapTicks; changes in between are counted). */
    public static long phaseGapTicks() { return Math.max(1, io.github.mojolowjo.entropybot.aa.brain.Tuning.l("debug.stepProbePhaseGapTicks")); }

    /** 0.39.z56 item 3: a client tick longer than this many ms gets its own slow-tick line (Tuning debug.slowTickMs). */
    public static double slowTickMs() { return io.github.mojolowjo.entropybot.aa.brain.Tuning.get("debug.slowTickMs"); }

    // ---- 0.39.z56 item 1: the step probe (run O 22:11:46-22:14:51: "crafting furnace at the crafting table" then three
    // unrelated price lines in 3 min and "no progress 180 s at craft furnace 1"; the craft's walk had no probe at all) ----

    /**
     * When a still line is due: no progress = the feet stay in the anchor's column within one block up or down (a swimmer's
     * bob, run O: y 62 / 63 every 3-4 s, is no progress) and the bag is unchanged. A move out of that box or a bag change
     * is progress: the anchor moves there and the count starts again. Due once per {@code every} ticks of no progress.
     */
    public static final class Still {
        private int[] anchor;
        private int bag;
        private long since = -1, lastLine = -1;

        /** Forget everything (a new step). */
        public void reset() { anchor = null; since = -1; lastLine = -1; }

        /** One tick's feet and bag signature; true when a still line is due now. */
        public boolean due(long tick, int[] feet, int bagSig, long every) {
            if (feet == null) return false;
            boolean same = anchor != null && bag == bagSig && anchor[0] == feet[0] && anchor[2] == feet[2] && Math.abs(anchor[1] - feet[1]) <= 1;
            if (!same) {
                anchor = feet.clone();
                bag = bagSig;
                since = tick;
                lastLine = tick;
                return false;
            }
            if (tick - lastLine < every) return false;
            lastLine = tick;
            return true;
        }

        /** Ticks since the last progress (0 before any). */
        public long stillTicks(long tick) { return since < 0 ? 0 : tick - since; }

        /** The anchor cell ("x y z"), "-" before any. */
        public String anchor() { return at(anchor); }
    }

    /**
     * A step's sub-phase as the probe sees it: the step's own status words, the job hands' request, Baritone's process in
     * control and its goal. A change of any is a phase change.
     */
    public record Phase(String status, String hands, String control, String goal) {
        public String text() { return "status " + status + " | hands " + hands + " | in control " + control + " | goal " + goal; }
    }

    /**
     * 0.39.z78 run EJ (3): the "probe lease:" line beside the step's start line and on each cancel (debug.stepProbe): the
     * break / place / inventory flags as Baritone's settings read now ({@code null}: not read - Baritone not loaded) and the
     * fork's lease state with its holders (RunLease.describe; {@code null}: not read - Baritone not loaded). Pure.
     */
    public static String leaseLine(long tick, String kind, String step, String when, Boolean brk, Boolean place, Boolean inv, String fork) {
        return "probe lease: t=" + tick + " | " + kind + " " + step + " | at " + when + " | allowBreak " + flag(brk) + " | allowPlace " + flag(place)
                + " | allowInventory " + flag(inv) + " | fork lease " + (fork == null ? "not read (Baritone not loaded)" : fork);
    }

    /** Run EN: the walk line's last-calc part (the fork's WalkCalc.line(); null: no "no path" end in this walk yet). Pure. */
    public static String lastCalc(String line) {
        return " | last calc " + (line == null ? "none (no 'no path' end in this walk)" : line);
    }

    /**
     * Run EQ: a place step's walk line part - the fork's last face check that found no usable face
     * (IBuilderProcess.legFaceCheck, job-vein d1d15445; null: every face check passed). Pure.
     */
    public static String faceCheck(String line) {
        return " | face check " + (line == null ? "every face passed (none failed in this leg)" : line);
    }

    private static String flag(Boolean b) { return b == null ? "not read (Baritone not loaded)" : b.toString(); }

    /** The phase line: what changed from {@code was} to {@code now} (the step's start when {@code was} is null); {@code folded}: changes not printed since the last line. */
    public static String phase(long tick, int[] feet, String kind, String step, Phase was, Phase now, int folded) {
        StringBuilder b = new StringBuilder("t=" + tick + " feet " + at(feet) + " | " + kind + " " + step + " | ");
        if (was == null) b.append("start: ").append(now.text());
        else {
            List<String> ch = new java.util.ArrayList<>();
            if (!java.util.Objects.equals(was.status(), now.status())) ch.add("status " + was.status() + " -> " + now.status());
            if (!java.util.Objects.equals(was.hands(), now.hands())) ch.add("hands " + was.hands() + " -> " + now.hands());
            if (!java.util.Objects.equals(was.control(), now.control())) ch.add("in control " + was.control() + " -> " + now.control());
            if (!java.util.Objects.equals(was.goal(), now.goal())) ch.add("goal " + was.goal() + " -> " + now.goal());
            b.append(ch.isEmpty() ? "no change" : String.join(" | ", ch));
        }
        if (folded > 0) b.append(" (").append(folded).append(" phase change(s) in between not printed: debug.stepProbePhaseGapTicks)");
        return b.toString();
    }

    /** The still line: everything a stalled step shows, once per debug.probeEveryTicks. */
    public record StepStill(long tick, int[] feet, String kind, String step, long stillTicks, String anchor, Phase phase,
                            boolean executing, int[] src, int[] dest, int pos, int len, boolean calc, String lastEvent, long eventAgo,
                            String waiting, String needs, int keep, String throwaway) {
        public String line() {
            return "t=" + tick + " feet " + at(feet) + " | " + kind + " " + step
                    + String.format(Locale.ROOT, " | NO PROGRESS %.1f s (feet within one block of %s, bag unchanged)", stillTicks / 20.0, anchor)   // game fact: 20 ticks a second
                    + " | " + phase.text()
                    + " | path " + (executing ? at(src) + " -> " + at(dest) + " at " + pos + "/" + len : "none executing")
                    + " | calc " + (calc ? "in progress" : "none")
                    + " | last path event " + (lastEvent == null ? "none this step" : lastEvent + " " + eventAgo + " ticks ago")
                    + " | waiting " + waiting + " | needs in bag " + needs + " | reserves " + keep + " | spare throwaway in bag " + throwaway;
        }
    }

    /** "cobblestone 8/8, stick 0/1" for the step's needs (have / uses) - "none" when it uses nothing. */
    public static String needs(Map<String, Integer> uses, java.util.function.ToIntFunction<String> have) {
        if (uses == null || uses.isEmpty()) return "none";
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : uses.entrySet())
            b.append(b.length() == 0 ? "" : ", ").append(e.getKey().replaceFirst("^minecraft:", "")).append(' ').append(have.applyAsInt(e.getKey())).append('/').append(e.getValue());
        return b.toString();
    }

    // ---- 0.39.z56 item 3: the load probe (the owner sees very low frame rates at times) ----

    /** One window's client tick and mod times (ms), summed per tick; read and reset once per load line. */
    public static final class Load {
        private int ticks, frames;
        private double tickSum, tickMax, modSum, modMax, frameMax;

        public void tick(double tickMs, double modMs) {
            ticks++;
            tickSum += tickMs;
            tickMax = Math.max(tickMax, tickMs);
            modSum += modMs;
            modMax = Math.max(modMax, modMs);
        }

        /** One frame's interval (ms from the last frame's start). */
        public void frame(double ms) {
            frames++;
            frameMax = Math.max(frameMax, ms);
        }

        /** "client tick avg 3.1 max 12.0 ms (20 ticks) | mod avg 1.2 max 9.0 ms (39% of the tick time) | frames 58, longest 48 ms" then reset. */
        public String takeLine() {
            String s = ticks == 0 ? "client tick: none measured"
                    : String.format(Locale.ROOT, "client tick avg %.1f max %.1f ms (%d ticks) | mod avg %.1f max %.1f ms (%.0f%% of the tick time)",
                    tickSum / ticks, tickMax, ticks, modSum / ticks, modMax, tickSum <= 0 ? 0 : 100 * modSum / tickSum);
            s += String.format(Locale.ROOT, " | frames %d, longest %.0f ms", frames, frameMax);
            ticks = 0; frames = 0; tickSum = 0; tickMax = 0; modSum = 0; modMax = 0; frameMax = 0;
            return s;
        }
    }

    /** The load line's other facts, as counts since the last line where a "+" is shown. */
    public record LoadFacts(int fps, String window, long pricedStarted, long pricedLanded, int pricedInFlight,
                            long[] segs, long calcs, long calcFails, long[] recorder, long heapUsedMb, long heapMaxMb, long gcCount, long gcMs, long[] joins) {
        public LoadFacts(int fps, String window, long pricedStarted, long pricedLanded, int pricedInFlight,
                         long[] segs, long calcs, long calcFails, long[] recorder, long heapUsedMb, long heapMaxMb, long gcCount, long gcMs) {
            this(fps, window, pricedStarted, pricedLanded, pricedInFlight, segs, calcs, calcFails, recorder, heapUsedMb, heapMaxMb, gcCount, gcMs, null);
        }

        public String line(long tick) {
            String fork = segs == null || segs[0] < 0 ? "fork counts not in this jar" : "fork segments started +" + segs[0] + " landed +" + segs[1] + ", nodes opened +" + segs[2];
            // D11 (the re-join): re-joins asked, join searches started / landed and spliced / failed (IPricedPath.joinCounts)
            if (joins != null && joins.length >= 4 && joins[0] >= 0) {
                fork += "; re-joins +" + joins[0] + " (join searches started +" + joins[1] + " landed +" + joins[2] + " failed +" + joins[3];
                // D11 fix (run FF: 74 failed joins the log could not explain): the failures by kind, and the re-joins queued for a slot
                if (joins.length >= 9) fork += ": budget +" + joins[4] + " no path +" + joins[5] + " off the path +" + joins[6] + " other +" + joins[8] + "; queued for a slot +" + joins[7];
                fork += ")";
            } else if (joins != null) fork += "; re-joins not readable in this fork jar";   // D11 review 1 (rule 7): said, never left out
            String rec = recorder == null ? "recorder off" : "recorder queue " + recorder[0] + ", lines written +" + recorder[1] + String.format(Locale.ROOT, ", disk %.1f MB", recorder[2] / 1048576.0);
            return "t=" + tick + " | fps " + fps + " | " + window
                    + " | priced paths started +" + pricedStarted + " landed +" + pricedLanded + " in flight " + pricedInFlight + "; " + fork
                    + " | Baritone path calcs started +" + calcs + " failed +" + calcFails
                    + " | " + rec
                    + " | heap " + heapUsedMb + "/" + heapMaxMb + " MB | GC +" + gcCount + " in " + gcMs + " ms";
        }
    }

    /**
     * 0.39.z57 (path memory; run P 23:46-23:50: 406 priced paths, 180 exact repeats): the asks part of the load line from
     * the fork's IPricedPath.askCounts deltas {asks, zero, exact, suffix, kept no-path, joined, started, queued, cancelled,
     * dropped}; a fork without them says so.
     */
    public static String asks(long[] d) {
        if (d == null || d.length < 10 || d[0] < 0) return "path asks: not counted by this fork jar";
        return "path asks +" + d[0] + ": from memory exact +" + d[2] + " suffix +" + d[3] + " zero +" + d[1] + " no-path +" + d[4]
                + ", joined in flight +" + d[5] + ", searches started +" + d[6] + ", queued +" + d[7] + ", cancelled +" + d[8]
                + ", kept dropped +" + d[9] + (d.length > 10 ? ", not loaded +" + d[10] : "")
                + (d.length > 11 ? ", stamp builds +" + d[11] : "")           // z57 review 2 item 3
                + queue(d);
    }

    /**
     * 0.39.z58 item 3 (run Q 02:28-02:35: "in flight 4", "queued +16" with no word of who held the slots): the fork's queue
     * counts per class [12..23] - started and queued per class (running step / first answer / re-search / rest), cancelled
     * for priority (re-search, rest), re-search waits answered, queued asks that left the queue; "" from an older fork.
     */
    static String queue(long[] d) {
        if (d.length < 24) return "";
        return " | by class running step/first answer/re-search/rest: started +" + d[12] + "/+" + d[13] + "/+" + d[14] + "/+" + d[15]
                + ", queued +" + d[16] + "/+" + d[17] + "/+" + d[18] + "/+" + d[19]
                + ", cancelled for priority re-search +" + d[20] + " rest +" + d[21]
                + ", re-search waits +" + d[22] + ", left the queue +" + d[23];
    }

    /** 0.39.z57 (rule 7): one dropped kept answer, the fork's words ("to <goal>: <why>"). */
    public static String reuse(String fork) {
        return "dropped the kept answer " + fork + " (not known until the new search lands)";
    }

    /** Names of the mod's tick sections, in Core.onClientTick's order (LoadProbe.mark indices). */
    public static final String[] SECTIONS = {"guard+commands (aa)", "reflexes", "probes+knowledge", "chunk pass", "recorder", "rest"};

    /** The slow-tick line: the tick, the mod's part and its sections (ms), what the mod did in it. */
    public static String slowTick(long tick, double tickMs, double limit, double modMs, double[] sections, String did) {
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "t=%d | client tick %.1f ms (over debug.slowTickMs %.0f) | mod %.1f ms", tick, tickMs, limit, modMs));
        if (sections != null) {
            b.append(':');
            for (int i = 0; i < sections.length && i < SECTIONS.length; i++) b.append(String.format(Locale.ROOT, " %s %.1f", SECTIONS[i], sections[i])).append(i + 1 < sections.length ? "," : "");
        }
        b.append(String.format(Locale.ROOT, " | outside the mod %.1f ms (vanilla, Baritone's tick, other mods)", Math.max(0, tickMs - modMs)));
        return b.append(" | ").append(did).toString();
    }

    /**
     * 0.39.z54 review 1: what a movement already knows, read without side effects (the probe never calls Baritone's
     * toBreak / toPlace, which fill these caches from a world read): the caches when Baritone computed them (null = not
     * yet), the planned break positions and whether a placement is planned.
     */
    public interface MoveFacts {
        List<?> breakCached();
        List<?> placeCached();
        int plannedBreaks();
        boolean plannedPlace();
    }

    /** "breaks 1 places 0" from Baritone's caches, else "breaks/places not computed yet (planned: breaks N, place yes|no)". */
    public static String move(MoveFacts m) {
        List<?> b = m.breakCached(), p = m.placeCached();
        String planned = "planned: breaks " + m.plannedBreaks() + ", place " + (m.plannedPlace() ? "yes" : "no");
        if (b == null || p == null)
            return "breaks " + (b == null ? "?" : b.size()) + " places " + (p == null ? "?" : p.size()) + " (not computed yet by Baritone; " + planned + ")";
        return "breaks " + b.size() + " places " + p.size() + " (" + planned + ")";
    }

    /** "x y z", or "-" for none. */
    public static String at(int[] p) { return p == null ? "-" : p[0] + " " + p[1] + " " + p[2]; }

    /**
     * One client tick of a walk step. {@code origin}: "kept" (PathingBehavior got the executing path from the priced path),
     * "fresh" (a search of its own), "-" (no path executing); {@code offer}: segments the walk offered / taken so far.
     */
    public record Walk(long tick, int[] feet, String step, String goal, String inControl, boolean goalProcessActive, boolean executing,
                       int[] src, int[] dest, int pos, int len, boolean calc, String origin, int[] offer, String walkAnswer,
                       String move, String moveFacts, String intent, boolean hold) {
        public String line() {
            return "t=" + tick + " feet " + at(feet) + " | step " + step + " | goal " + goal + " | in control " + inControl
                    + " | custom goal active " + goalProcessActive
                    + " | path " + (executing ? at(src) + " -> " + at(dest) + " at " + pos + "/" + len : "none executing")
                    + " | calc " + (calc ? "in progress" : "none")
                    + " | origin " + origin + " (offered " + offer[0] + ", taken " + offer[1] + "; walk start: " + walkAnswer + ")"
                    + " | move " + move + (move.equals("-") ? "" : " " + moveFacts)
                    + " | intent " + intent + " | reflex hold " + hold;
        }
    }

    /** A Baritone path event while a walk step runs. */
    public static String event(long tick, int[] feet, String event, String step) {
        return "t=" + tick + " feet " + at(feet) + " | " + event + " | step " + (step == null ? "-" : step);
    }

    /** A cancel the mod sent while a walk step ran: who, and the calling frames. */
    public static String cancel(long tick, int[] feet, String who, String frames, String step) {
        return "t=" + tick + " feet " + at(feet) + " | CANCEL by " + who + " (" + frames + ") | step " + (step == null ? "-" : step);
    }

    /**
     * One kept segment of a price: start, end, ticks, the movements by kind, the break positions and placements they plan
     * (the movements' own plan, no world read: review 1) and the falls (a drop of more than one block).
     */
    public record Segment(int[] src, int[] dest, double ticks, Map<String, Integer> kinds, int breaks, int places, int falls) {
        public String text() {
            StringBuilder k = new StringBuilder();
            for (Map.Entry<String, Integer> e : new TreeMap<>(kinds).entrySet()) k.append(k.length() == 0 ? "" : ", ").append(e.getKey()).append(' ').append(e.getValue());
            return at(src) + " -> " + at(dest) + " " + Math.round(ticks) + " ticks [" + k + "] planned breaks " + breaks + " places " + places + " falls " + falls;
        }
    }

    /** The priced path at its landing: from, goal, the answer and each kept segment (none = why). */
    public static String price(int[] from, int[] goal, String answer, List<Segment> segs) {
        StringBuilder b = new StringBuilder("from " + at(from) + " to " + at(goal) + " | " + answer + " | " + segs.size() + " segment(s)");
        if (segs.isEmpty()) b.append(" (none kept: no path, or the price is not the fork's priced path)");
        for (int i = 0; i < segs.size(); i++) b.append(" | ").append(i + 1).append(": ").append(segs.get(i).text());
        return b.toString();
    }

    /**
     * An UNREACHABLE / NO_PROGRESS answer: from, goal, the fork's failure detail (null: the fork kept none - which fields
     * are missing is said), the goal's chunk, the cost hook's refusals since the last probe line (every search, not this
     * one alone: the hook's counters are global).
     */
    public static String unreachable(int[] from, int[] goal, String reason, String failure, boolean goalChunkLoaded, long costDenies, long placeDenies) {
        return "from " + at(from) + " to " + at(goal) + " | " + reason
                + " | " + (failure == null ? "no failure detail kept by the fork (segment, nodes and best node missing: IPricedPath.failure gave null)" : failure)
                + " | goal chunk " + (goalChunkLoaded ? "loaded" : "NOT loaded")
                + String.format(Locale.ROOT, " | cost hook refusals since the last probe line (all searches): break %d, place %d", costDenies, placeDenies);
    }
}
