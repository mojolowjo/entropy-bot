package io.github.mojolowjo.entropybot.aa.moves;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 0.39.z71 (design D2 3.2, Job B; run O 22:11:46-22:14:51: a craft's walk bobbed in water for 3 min until the 180 s watch;
 * run P 00:04:15-54: a new explore target every second while the feet stood still): the running step's progress in its own
 * unit, looked at every {@code monitor.everyTicks} from {@link Steps#tick}. A signal counts only when it reaches a NEW BEST
 * since the step started (a bob, a goal that comes and goes, status words that change are no progress):
 * <ul>
 * <li>goal: Baritone's goal heuristic at the feet below its best by 1 or more (rule 18: a progress fact, never a price);</li>
 * <li>feet: the farthest horizontal distance from the start cell grown by 1 or more (a composite goal - the fork's priced
 *     search goal, DigDownGoal, ClimbGoal - may plateau while the walk is right; a vertical dig or climb is covered by the
 *     break stage and the placed count);</li>
 * <li>made: the bag's gain of the step's own output ({@link #output}) above its best;</li>
 * <li>break stage: the block being broken reached a higher stage (a long strip between ores digs stone: each stage is
 *     progress, so 30 s never cuts a working tunnel);</li>
 * <li>placed: the client's placed-block count went up (a pillar, a build leg, a station);</li>
 * <li>explored: the running explore's new chunks went up.</li>
 * </ul>
 * Verdicts ({@link Verdict}), each with its reason (rule 7): CONTINUE (what moved, the step's own wait, or "signals not
 * loaded: why" - rule 15, never REPLAN on unknown); COMPLETE (the step's done fact seen while its hands still run: the
 * gather kinds whose hands count until n - a collect, a tree, a hunt for an item); REPLAN on a stall (no new best for
 * {@code monitor.stallS}; the reason lists each signal and its best) or on Baritone finding no path
 * {@code monitor.noPathFails} times since the last new best with nothing executing. A REPLAN carries a typed fact (the
 * step's source key, else {@code step <StepId>}) for {@link Steps#failOrReplan}: the planner decides what next (rule 1).
 * Exempt kinds ({@link Kind#EXEMPT}: a wait, eating, a GUI job, a reflex move) are never judged. The monitor never names a
 * way, never moves anything (rule 2). Pure (JUnit: Z71StepMonitorTest). Loader notes: none (StepSignals are plain values).
 */
public final class StepMonitor {
    public enum Verdict { CONTINUE, COMPLETE, REPLAN }

    /** One look's verdict, its reason, and for a REPLAN the fact handed to the planner (null otherwise). */
    public record Look(Verdict v, String reason, FactChange.Fact fact) {}

    /** The step kinds by what their progress is (design 3.2's table); EXEMPT: never judged. */
    public enum Kind { WALK, GATHER, ITEM_JOB, PLACE, HUNT, EXPLORE, EXEMPT }

    /** Ticks between looks (Tuning monitor.everyTicks). */
    public static long everyTicks() { return Math.max(1, Tuning.l("monitor.everyTicks")); }

    /** Ticks with no new best before a stall (Tuning monitor.stallS x 20 ticks a second, a game fact). */
    public static long stallTicks() { return Math.round(20 * Tuning.get("monitor.stallS")); }

    /** Baritone's failed searches since the last new best before "no path" (Tuning monitor.noPathFails). */
    public static int noPathFails() { return Math.max(1, Tuning.i("monitor.noPathFails")); }

    /** The step's kind for the monitor. */
    public static Kind kind(Step s) {
        return switch (s) {
            case Step.Walk w -> Kind.WALK;
            case Step.ToStrip w -> Kind.WALK;
            case Step.ToBand w -> Kind.WALK;
            case Step.ToStation w -> Kind.WALK;
            case Step.ToSurface w -> Kind.WALK;
            case Step.StripBack w -> Kind.WALK;
            case Step.Climb w -> Kind.WALK;
            case Step.Collect g -> Kind.GATHER;
            case Step.ChopTree g -> Kind.GATHER;
            case Step.MineAt g -> Kind.GATHER;
            case Step.Mine g -> Kind.GATHER;
            case Step.Strip g -> Kind.GATHER;
            case Step.KelpCut g -> Kind.GATHER;
            case Step.TakeStation g -> Kind.GATHER;
            case Step.Craft j -> Kind.ITEM_JOB;
            case Step.Smelt j -> Kind.ITEM_JOB;
            case Step.Cook j -> Kind.ITEM_JOB;
            case Step.PlaceNear p -> Kind.PLACE;
            case Step.Build p -> Kind.PLACE;
            case Step.HuntAt h -> Kind.HUNT;
            case Step.Explore e -> Kind.EXPLORE;
            case Step.FindCave e -> Kind.EXPLORE;
            case null -> Kind.EXEMPT;
            default -> Kind.EXEMPT;               // a wait, eating, sleep, GUI jobs (deposit, fetch, scan), reflex moves, owner builds
        };
    }

    /**
     * The item (or tag word) whose bag gain is the step's own output, null: none the step's data names (a mine of a family,
     * a walk, a placement). From the step's data only (rule 4).
     */
    public static String output(Step s) {
        return switch (s) {
            case Step.Collect k -> k.item();
            case Step.ChopTree t -> t.log() != null ? t.log() : "#minecraft:logs";   // the game's logs tag (the chop's own filter)
            case Step.Craft k -> k.item();
            case Step.Smelt k -> k.item();
            case Step.HuntAt h -> h.forItem();
            case null -> null;
            default -> null;
        };
    }

    /** How many of {@link #output} the step is for, -1: no count (it ends on its own state). */
    public static int target(Step s) {
        return switch (s) {
            case Step.Collect k -> k.n();
            case Step.ChopTree t -> t.logs();
            case Step.Craft k -> k.n();
            case Step.Smelt k -> k.n();
            case Step.HuntAt h -> h.forItem() == null ? -1 : h.forCount();
            case null -> -1;
            default -> -1;
        };
    }

    /**
     * The kinds a COMPLETE may end: the Baritone gathers whose hands count to n (cancel = Baritone's process stops, the
     * chop's whole-tree job included). A craft / smelt never: the item job ends itself on its own observed state and a
     * cancel there would cut its clicks (design 8, COMPLETE mid-job).
     */
    static boolean completes(Step s) { return s instanceof Step.Collect || s instanceof Step.ChopTree || s instanceof Step.HuntAt h && h.forItem() != null; }

    // ---- the running step's bests ----

    private Step step;
    private Kind kind = Kind.EXEMPT;
    private boolean based, stalled;
    private long startTick, lastProgress, calcAtBest;
    private int[] startFeet;
    private double bestGoal = Double.NaN, bestFar;
    private long bestGoalAt = -1, bestFarAt = -1, bestMadeAt = -1, stageAt = -1, placedAt = -1, chunksAt = -1;
    private int bestMade = -1, lastStage = -1, bestChunks = -1;
    private long bestPlaced;

    /** A step started (or started again after a pause): its bests from {@code sig} (null or not loaded: on the first loaded look). */
    public void begin(Step s, StepSignals sig, long now) {
        step = s;
        kind = kind(s);
        based = false;
        stalled = false;
        startTick = now;
        lastProgress = now;
        if (sig != null && sig.loaded()) base(sig, now);
    }

    private void base(StepSignals sig, long now) {
        based = true;
        lastProgress = now;
        startFeet = sig.feet();
        bestGoal = sig.goalDist();
        bestGoalAt = Double.isNaN(bestGoal) ? -1 : now;
        bestFar = 0;
        bestFarAt = -1;
        bestMade = sig.made();
        bestMadeAt = -1;
        lastStage = sig.destroyStage();
        stageAt = -1;
        bestPlaced = sig.placedCount();
        placedAt = -1;
        bestChunks = sig.exploredChunks();
        chunksAt = -1;
        calcAtBest = sig.calcFailed();
    }

    /** The step is judged (not exempt, a step running). */
    public boolean judges() { return step != null && kind != Kind.EXEMPT; }

    /** The last look found no new best (the probe's "while stalled" lines). */
    public boolean stalled() { return stalled; }

    public Kind kind() { return kind; }

    /** One look at {@code now} (ticks) on {@code sig}. */
    public Look look(StepSignals sig, long now) {
        stalled = false;
        if (step == null) return cont("no step runs");
        if (kind == Kind.EXEMPT) return cont(Step.verb(step) + " is not judged (it may stand still on purpose)");
        if (sig == null) return cont("no signals from these hands");
        if (!sig.loaded()) {
            lastProgress = now;                  // rule 15: unknown is no stall; the window starts again once known
            return cont("signals not loaded: " + sig.notLoaded());
        }
        if (!based) {
            base(sig, now);
            return cont("first loaded look: the bests start here");
        }
        if (sig.ownWait() != null) {
            lastProgress = now;                  // its own clock watches it (the furnace's due time, the first-answer wait)
            calcAtBest = sig.calcFailed();
            return cont("waiting on its own clock: " + sig.ownWait());
        }
        List<String> moved = new ArrayList<>();
        double g = sig.goalDist();
        if (!Double.isNaN(g)) {
            if (Double.isNaN(bestGoal)) { bestGoal = g; bestGoalAt = now; }        // a goal appeared: its first value is the start
            else if (g <= bestGoal - 1) {
                moved.add("the goal " + num(bestGoal - g) + " nearer, best " + num(g));
                bestGoal = g;
                bestGoalAt = now;
            }
        }
        if (sig.feet() != null && startFeet == null) startFeet = sig.feet();
        else if (sig.feet() != null) {
            double far = horizontal(sig.feet(), startFeet);
            if (far >= bestFar + 1) {
                moved.add("feet " + num(far) + " from the start");
                bestFar = far;
                bestFarAt = now;
            }
        }
        if (sig.made() >= 0 && sig.made() > bestMade) {
            moved.add("made " + sig.made());
            bestMade = sig.made();
            bestMadeAt = now;
        }
        if (sig.destroyStage() >= 0 && sig.destroyStage() > lastStage) {
            moved.add("breaking a block (stage " + sig.destroyStage() + ")");
            stageAt = now;
        }
        lastStage = sig.destroyStage();
        if (sig.placedCount() > bestPlaced) {
            moved.add("placed " + (sig.placedCount() - bestPlaced));
            bestPlaced = sig.placedCount();
            placedAt = now;
        }
        if (sig.exploredChunks() >= 0) {
            if (bestChunks < 0 || sig.exploredChunks() < bestChunks) bestChunks = sig.exploredChunks();   // a new explore run started: its count is the start
            else if (sig.exploredChunks() > bestChunks) {
                moved.add("explored " + (sig.exploredChunks() - bestChunks) + " new chunk(s)");
                bestChunks = sig.exploredChunks();
                chunksAt = now;
            }
        }
        if (!moved.isEmpty()) {
            lastProgress = now;
            calcAtBest = sig.calcFailed();
        }
        int t = target(step);
        if (completes(step) && t > 0 && sig.made() >= t)
            return new Look(Verdict.COMPLETE, "complete: " + sig.made() + " of " + t + " " + StepNeeds.sh(output(step)) + " in the bag", null);
        long fails = sig.calcFailed() - calcAtBest;
        if (fails >= noPathFails() && !sig.executing())
            return replan("Baritone found no path " + fails + " times from " + at(sig.feet()) + (Double.isNaN(g) ? "" : " (goal " + num(g) + ", best " + num(bestGoal) + ")") + ", nothing executing");
        long quiet = now - lastProgress;
        if (quiet >= stallTicks()) return replan("no progress " + quiet / 20 + " s: " + bests(sig));     // game fact: 20 ticks a second
        stalled = moved.isEmpty();
        return cont(moved.isEmpty() ? "no new best for " + quiet / 20 + " s" : String.join(", ", moved));     // game fact: 20 ticks a second
    }

    /** Each signal with its best and when (seconds from the step's start): the stall's reason. */
    String bests(StepSignals sig) {
        List<String> p = new ArrayList<>();
        p.add(Double.isNaN(sig.goalDist()) ? "no goal" : "the goal " + num(sig.goalDist()) + " (best " + num(bestGoal) + (bestGoalAt < 0 ? "" : " at " + sec(bestGoalAt)) + ")");
        p.add("feet " + at(sig.feet()) + " (farthest " + num(bestFar) + " from the start" + (bestFarAt < 0 ? "" : " at " + sec(bestFarAt)) + ")");
        p.add(sig.made() < 0 ? "bag gain not known" : bestMadeAt < 0 ? "bag same (made " + sig.made() + ")" : "made " + sig.made() + " (best " + bestMade + " at " + sec(bestMadeAt) + ")");
        p.add(stageAt < 0 ? "no block broken" : "last break stage at " + sec(stageAt));
        p.add(placedAt < 0 ? "nothing placed" : "last placed at " + sec(placedAt));
        if (sig.exploredChunks() >= 0) p.add(chunksAt < 0 ? "no new chunk" : "last new chunk at " + sec(chunksAt));
        p.add("Baritone " + (sig.executing() ? "executing" : "not executing") + (sig.calcRunning() ? ", a search running" : ""));
        return String.join(", ", p);
    }

    /**
     * The probe line ({@code debug.monitorProbe} 1): "t=.. feet .. | <verb> <text> | <verdict> | goal <d> best <b> at <s> |
     * made .. | stage .. | placed .. | calcFailed n | executing y/n - <reason>".
     */
    public String probeLine(long now, StepSignals sig, Look l) {
        String verb = step == null ? "-" : Step.verb(step), text = step == null ? "-" : step.text();
        if (sig == null || !sig.loaded())
            return "t=" + now + " | " + verb + " " + text + " | " + l.v() + " - " + l.reason();
        return "t=" + now + " feet " + at(sig.feet()) + " | " + verb + " " + text + " | " + l.v() + " | goal " + num(sig.goalDist()) + " best " + num(bestGoal)
                + (bestGoalAt < 0 ? "" : " at " + sec(bestGoalAt)) + " | made " + sig.made() + " | stage " + sig.destroyStage() + " | placed " + sig.placedCount()
                + " | calcFailed " + (sig.calcFailed() - calcAtBest) + " | executing " + (sig.executing() ? "y" : "n") + " - " + l.reason();
    }

    private Look replan(String why) {
        stalled = true;
        String src = io.github.mojolowjo.entropybot.aa.NeedChain.sourceKey(step);
        FactChange.Fact f = src != null ? FactChange.noProgress(src, why + " at " + step.text())
                : new FactChange.Fact("step " + StepId.of(step).text(), why + " at " + step.text());
        return new Look(Verdict.REPLAN, why, f);
    }

    private static Look cont(String why) { return new Look(Verdict.CONTINUE, why, null); }

    private String sec(long tick) { return (tick - startTick) / 20 + " s"; }     // game fact: 20 ticks a second

    static double horizontal(int[] a, int[] b) {
        if (a == null || b == null || a.length < 3 || b.length < 3) return 0;     // structural: x y z
        double dx = a[0] - b[0], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dz * dz);
    }

    static String num(double d) {
        if (Double.isNaN(d)) return "-";
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d);
    }

    static String at(int[] f) { return f == null || f.length < 3 ? "unknown" : f[0] + " " + f[1] + " " + f[2]; }     // structural: x y z
}
