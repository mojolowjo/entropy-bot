package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * aa-replan (director, 2026-10-19; aa380: it dug straight down for iron, then took stairs back up): round trips and
 * rolling re-scores.
 *
 * <ul>
 * <li><b>Return leg</b>: every option is go + do + return. The return is chosen from {pillar my own shaft, walk} by cost
 *     when the option is scored and stored on it ({@link Options.Option#ret}); it is not re-derived mid-step. Pillar =
 *     refilling the shaft I dug: {@value #PLACE_BLOCK} x {@value #REFILL} per block (placing back what I broke is nearly
 *     free), only with enough throwaway blocks and placing allowed. Walk = the stairs/trail back: depth x 3 + 10
 *     (MINING_PLAN 11's climb cost) or the walk distance. At the step boundary the runtime checks the shaft again
 *     ({@link #returnNow}): broken or too few blocks = walk (the next step's own goto).</li>
 * <li><b>Rolling re-score</b> of the running option ({@link Timer}, {@link #decide}): at every step boundary and every
 *     {@value #EVERY_MS} ms on a step older than {@value #LONG_STEP_MS} ms. Switch only when the alternative is at least
 *     {@value #CHEAPER} cheaper (fraction) AND saves {@value #MIN_SAVE} s of work, never within {@value #LOCK_MS} ms of
 *     a switch, never mid-dig-down or mid-pillar, and only while the running step is in the part of the chain the two
 *     options do not share.</li>
 * </ul>
 * Cost unit: walk-blocks, 1 block = 1 s of work ({@link Options#MINUTE} = 60). Pure (JUnit: ReplanTest). Loader notes: none.
 */
public final class Replan {
    private Replan() {}

    public static double placeBlock() { return IronOptions.placeBlock(); }
    public static double refill() { return Tuning.get("replan.refill"); }
    public static double climbBlock() { return Tuning.get("replan.climbBlock"); }
    public static double climbFix() { return Tuning.get("replan.climbFix"); }
    public static double cheaper() { return Tuning.get("replan.cheaper"); }
    public static double minSave() { return Tuning.get("replan.minSave"); }
    public static long everyMs() { return Tuning.l("replan.everyMs"); }
    public static long longStepMs() { return Tuning.l("replan.longStepMs"); }
    public static long lockMs() { return Tuning.l("replan.lockMs"); }

    public enum Leg { NONE, PILLAR, TUNNEL, WALK }

    // ---- aa-replan 2 (director, aa381): the real way back, chosen where the ore step ends ----
    //
    // The leg stored at plan time is only the scoring estimate. At the ore step's end the runtime scans the column over
    // the head up to the sky and prices every way back as a cost the chooser compares (no "if X then walk" rules):
    // tunnel up + pillar = breaks overhead (x1 a cell I dug, x6 a foreign one, x the BreakPolicy multiplier) + places x0.05;
    // an open own shaft is its cheapest case (no breaks). Hazards and the cap are costs too: lava/water/falling blocks in
    // the column, levels over 40, blocks I lack. Tunnel/pillar wins only when >= 20% cheaper than the walk.

    public static int tunnelCap() { return Tuning.i("replan.tunnelCap"); }
    public static double ownBreak() { return Tuning.get("replan.ownBreak"); }
    public static double foreignBreak() { return Tuning.get("replan.foreignBreak"); }
    public static double upPlace() { return Tuning.get("replan.upPlace"); }
    public static double tunnelBetter() { return Tuning.get("replan.tunnelBetter"); }
    /** Hazard costs per cell (walk-blocks): lava (burns, flows onto me), water (flows down the shaft), a falling block. */
    public static double lavaV() { return Tuning.get("replan.lava"); }
    public static double waterV() { return Tuning.get("replan.water"); }
    public static double fallingV() { return Tuning.get("replan.falling"); }
    public static double overCap() { return Tuning.get("replan.overCap"); }
    public static double noBlock() { return Tuning.get("replan.noBlock"); }

    /** One cell of the column over the head, bottom up. {@code mult} = the BreakPolicy multiplier, infinite = denied. */
    public record Cell(boolean open, boolean ownDug, double mult, boolean lava, boolean water, boolean falling, String reason) {
        /** 0.39.z26 (last) 4: {@code reason} = the policy's refusal when mult is infinite (shown in aa why), else null. */
        public Cell(boolean open, boolean ownDug, double mult, boolean lava, boolean water, boolean falling) { this(open, ownDug, mult, lava, water, falling, null); }
        public String refusal() { return reason == null ? "a cell may not be broken" : "a cell may not be broken (" + reason + ")"; }
        public static Cell air() { return new Cell(true, false, 1, false, false, false); }
        public static Cell stone(boolean own) { return new Cell(false, own, 1, false, false, false); }
    }

    /** A priced way back. */
    public record Way(Leg leg, double cost, String why) {}

    /** Tunnel up + pillar through {@code column} ({@code levels} up); PILLAR when nothing needs breaking. */
    public static Way tunnelUp(List<Cell> column, int levels, int throwaway) {
        double brk = 0, haz = 0;
        int breaks = 0, own = 0;
        List<String> notes = new ArrayList<>();
        for (Cell c : column) {
            if (c.lava()) haz += lavaV();
            if (c.water()) haz += waterV();
            if (c.falling()) haz += fallingV();
            if (!c.open() && !c.water() && !c.lava()) {
                double m = c.mult();
                if (Double.isInfinite(m) || Double.isNaN(m)) { brk = Double.POSITIVE_INFINITY; notes.add(c.refusal()); break; }
                brk += (c.ownDug() ? ownBreak() : foreignBreak()) * m;
                breaks++;
                if (c.ownDug()) own++;
            }
        }
        if (haz > 0) notes.add("hazards " + Math.round(haz));
        double over = Math.max(0, levels - tunnelCap()) * overCap();
        if (over > 0) notes.add((levels - tunnelCap()) + " over the " + tunnelCap() + " cap " + Math.round(over));
        double lack = Math.max(0, levels - throwaway) * noBlock();
        if (lack > 0) notes.add((levels - Math.max(0, throwaway)) + " blocks short " + Math.round(lack));
        double cost = brk + levels * upPlace() + haz + over + lack;
        Leg leg = breaks == 0 ? Leg.PILLAR : Leg.TUNNEL;
        String why = levels + " up, " + breaks + " breaks (" + own + " mine)" + (notes.isEmpty() ? "" : ", " + String.join(", ", notes));
        return new Way(leg, cost, why);
    }

    /** The choice at the ore step's end: tunnel/pillar when >= 20% cheaper than the walk, else walk. Both priced. */
    public static Way chooseNow(List<Cell> column, int levels, int throwaway, double walk) {
        return chooseNow(column, levels, throwaway, walk, null);
    }

    /** AA_ROUND3 1: the pillar/tunnel price is WayUp's (with the tool's seconds and wear for the breaks). */
    public static Way chooseNow(List<Cell> column, int levels, int throwaway, double walk, io.github.mojolowjo.entropybot.aa.brain.ToolWear.Kit kit) {
        if (levels <= 0) return new Way(Leg.NONE, 0, "already out");
        Way t = WayUp.upColumn(column, levels, throwaway, kit);
        String both = t.leg().name().toLowerCase(Locale.ROOT) + " " + fmt(t.cost()) + " (" + t.why() + ") vs walk " + fmt(walk);
        if (t.cost() <= walk * (1 - tunnelBetter())) return new Way(t.leg(), t.cost(), both);
        return new Way(Leg.WALK, walk, both + ": walking");
    }

    private static String fmt(double d) { return Double.isInfinite(d) ? "inf" : d < 10 ? String.format(Locale.ROOT, "%.1f", d) : String.valueOf(Math.round(d)); }  // structural: display: one decimal under 10

    /** The target cell in an option id like "dig_known -65 57 -10" (the last three ints), or null. */
    public static int[] targetOf(String id) {
        if (id == null) return null;
        String[] w = id.trim().split("\\s+");
        if (w.length != 4 || !w[0].endsWith("_known")) return null;  // structural: token count of a *_known fact name
        try {
            return new int[]{Integer.parseInt(w[w.length - 3]), Integer.parseInt(w[w.length - 2]), Integer.parseInt(w[w.length - 1])};  // structural: index of the coordinates in a fact name
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Wandered farther than this from the planned target during the ore step: re-plan. */
    public static int wander() { return Tuning.i("replan.wander"); }

    public static boolean wandered(int[] target, int[] me) {
        if (target == null || me == null) return false;
        long dx = target[0] - me[0], dz = target[2] - me[2];        // horizontal: a dig straight down is not a wander
        return dx * dx + dz * dz > (long) wander() * wander();
    }

    /** The stored way back: the leg, its cost (walk-blocks), why, and the step it follows (0-based; -1 = the chain's end). */
    public record Ret(Leg leg, double cost, String why, int afterStep) {
        public static final Ret NONE = new Ret(Leg.NONE, 0, "no way back needed", -1);

        public String text() { return leg == Leg.NONE ? "" : "back: " + leg.name().toLowerCase(Locale.ROOT) + " " + Math.round(cost) + " (" + why + ")"; }
    }

    /** Pillar up my own shaft: depth x place x refill. */
    public static double pillarCost(int depth) { return Math.max(0, depth) * placeBlock() * refill(); }

    /** Walk / stairs back up: depth x 3 + 10, or the walk back when that is longer. */
    public static double walkCost(int depth, double walkBack) { return Math.max(depth <= 0 ? 0 : depth * climbBlock() + climbFix(), Math.max(0, walkBack)); }

    /**
     * The return leg of an option that digs straight down {@code depth} blocks (a shaft of its own) or walks
     * {@code walkBack} back. Pillar when the shaft is its own, the bag has a block per level and placing is allowed and
     * it is cheaper; else walk.
     *
     * @param ownShaft  the way digs its own shaft straight down (dig to a buried ore)
     * @param afterStep the step the leg follows (the ore step's index)
     */
    public static Ret chooseReturn(int depth, boolean ownShaft, int throwaway, boolean placeAllowed, double walkBack, int afterStep) {
        double walk = walkCost(depth, walkBack);
        if (depth <= 0 && walkBack <= 0) return Ret.NONE;
        if (ownShaft && depth > 0) {
            String no = !placeAllowed ? "placing not allowed" : throwaway < depth ? throwaway + " of " + depth + " blocks to place" : null;
            double pillar = pillarCost(depth);
            if (no == null && pillar <= walk)
                return new Ret(Leg.PILLAR, pillar, "my own shaft " + depth + " up, " + throwaway + " blocks; walk " + Math.round(walk), afterStep);
            return new Ret(Leg.WALK, walk, "walk " + depth + " up" + (no == null ? "" : "; pillar: " + no), afterStep);
        }
        return new Ret(Leg.WALK, walk, depth > 0 ? depth + " up, " + Math.round(walkBack) + " back" : Math.round(walkBack) + " back", afterStep);
    }

    /**
     * At the step boundary (the ore step done): may the stored pillar run? Out already = NONE; the shaft broken (a block
     * or fluid in the column), or fewer throwaway blocks than levels = WALK (the next step's goto). The block count check.
     */
    public static Leg returnNow(Ret stored, int depth, boolean shaftOpen, int throwaway) {
        if (stored == null || stored.leg() != Leg.PILLAR || depth <= 0) return depth <= 0 ? Leg.NONE : Leg.WALK;
        return shaftOpen && throwaway >= depth ? Leg.PILLAR : Leg.WALK;
    }

    public static String returnNowWhy(int depth, boolean shaftOpen, int throwaway) {
        if (depth <= 0) return "already out";
        if (!shaftOpen) return "the shaft is broken (a block or fluid above): walking";
        if (throwaway < depth) return throwaway + " of " + depth + " blocks to place: walking";
        return "pillar up my shaft, " + depth + " up, " + throwaway + " blocks";
    }

    // ---- the rolling re-score ----

    /** When to re-score: at a step boundary, and every 5 s on a step older than 15 s. */
    public static final class Timer {
        private String step;
        private long stepAt = -1, lastAt = -1;

        /** @return true when a re-score is due now; a new step text is a boundary */
        public boolean due(String stepText, long now) {
            if (stepText == null) { step = null; return false; }
            if (!stepText.equals(step)) {
                step = stepText;
                stepAt = lastAt = now;
                return true;
            }
            // jobs (owner 2026-10-22): no 5 s re-score inside a block job / placement / use; only at its boundary
            if (!io.github.mojolowjo.entropybot.aa.brain.JobBoundary.mid() && now - stepAt >= longStepMs() && now - lastAt >= everyMs()) {
                lastAt = now;
                return true;
            }
            return false;
        }

        public void reset() { step = null; stepAt = lastAt = -1; }
    }

    /**
     * The verdict: switch to {@code to} starting at its step {@code from} (0-based), or stay ({@code to} null) with why.
     * Both scores are in the line.
     */
    public record Decision(Options.Option to, int from, double cur, double alt, String why) {
        public boolean switches() { return to != null; }
    }

    /** Steps shared at the start of both chains (0.39.z70, D2 3.1: by {@link io.github.mojolowjo.entropybot.aa.moves.StepId}, never by text). */
    public static <T> int commonPrefix(List<T> a, List<T> b) {
        int n = 0;
        while (n < a.size() && n < b.size() && java.util.Objects.equals(a.get(n), b.get(n))) n++;
        return n;
    }

    /** Steps shared at the end of both chains (not overlapping the prefix). */
    public static <T> int commonSuffix(List<T> a, List<T> b, int prefix) {
        int n = 0;
        while (n < a.size() - prefix && n < b.size() - prefix && java.util.Objects.equals(a.get(a.size() - 1 - n), b.get(b.size() - 1 - n))) n++;
        return n;
    }

    private static boolean same(String a, String b) { return a.trim().equalsIgnoreCase(b.trim()); }

    /**
     * @param running  the running option (its command as started)
     * @param fresh    the re-scored ranking from where I stand now (the running option's row = its remaining cost)
     * @param stepIdx  the running step (0-based, in the running option's steps)
     * @param lastSwitchAt the last switch (-1 none)
     * @param midDigOrPillar a dig down or a pillar runs right now
     */
    public static Decision decide(Options.Option running, List<Options.Option> fresh, int stepIdx, long now, long lastSwitchAt, boolean midDigOrPillar) {
        return decide(running, fresh, stepIdx, now, lastSwitchAt, midDigOrPillar, false);
    }

    /**
     * @param wandered the ore step went more than {@link #WANDER} from the planned target (aa381: cave to cave): the
     *                 dig-down hold does not apply (it is not digging down there), and a running option missing from the
     *                 new ranking counts as infinitely dear (its target is gone or out of reach).
     */
    public static Decision decide(Options.Option running, List<Options.Option> fresh, int stepIdx, long now, long lastSwitchAt, boolean midDigOrPillar, boolean wandered) {
        if (wandered) midDigOrPillar = false;
        if (running == null || fresh == null || fresh.isEmpty()) return new Decision(null, -1, 0, 0, "nothing to compare");
        Options.Option cur = null;
        for (Options.Option o : fresh) if (same(o.command(), running.command())) { cur = o; break; }
        if (cur == null) for (Options.Option o : fresh) if (o.id().equals(running.id())) { cur = o; break; }
        if (cur == null && !wandered) return new Decision(null, -1, 0, 0, "the running option is not in the new ranking");
        Options.Option alt = null;
        for (Options.Option o : fresh) {
            if (o == cur || same(o.command(), running.command()) || Options.NOTHING.equals(o.command())) continue;
            if (alt == null || o.total() < alt.total()) alt = o;
        }
        double c = cur == null ? Double.POSITIVE_INFINITY : cur.total();
        if (alt == null) return new Decision(null, -1, c, 0, "no other option");
        double a = alt.total();
        String scores = (wandered ? "bug signal: wandered >" + wander() + " from the target (a per-ore mine never should): " : "") + (cur == null ? running.id() + " gone" : cur.id() + " " + Math.round(c)) + " vs " + alt.id() + " " + Math.round(a);
        if (!(a <= c * (1 - cheaper()) && c - a >= minSave())) return new Decision(null, -1, c, a, scores + ": not 30% and 3 s cheaper");
        if (lastSwitchAt >= 0 && now - lastSwitchAt < lockMs()) return new Decision(null, -1, c, a, scores + ": locked " + (lockMs() - (now - lastSwitchAt) + 999) / 1000 + " s after a switch");  // structural: round up to whole seconds
        if (midDigOrPillar) return new Decision(null, -1, c, a, scores + ": mid dig-down or pillar");
        List<io.github.mojolowjo.entropybot.aa.moves.StepId> rs = ids(running), as = ids(alt);
        int p = commonPrefix(rs, as), s = commonSuffix(rs, as, p);
        if (stepIdx < p) return new Decision(null, -1, c, a, scores + ": the step that differs has not started");
        if (stepIdx >= rs.size() - s) return new Decision(null, -1, c, a, scores + ": past the part that differs");
        return new Decision(alt, p, c, a, scores);
    }

    /** The option's step identities (compared step by step: where two options start to differ; 0.39.z70: never the text). */
    static List<io.github.mojolowjo.entropybot.aa.moves.StepId> ids(Options.Option o) {
        return io.github.mojolowjo.entropybot.aa.moves.StepId.of(o.steps());
    }

    /** True for a step that digs straight down (an ore step that digs) or walks up (a pillar walk 3+ above me). */
    public static boolean digOrPillarStep(io.github.mojolowjo.entropybot.aa.moves.Step step, int[] me) {
        return switch (step) {
            case null -> false;
            case io.github.mojolowjo.entropybot.aa.moves.Step.MineAt m -> m.dig();
            case io.github.mojolowjo.entropybot.aa.moves.Step.Mine m -> m.how() == io.github.mojolowjo.entropybot.aa.moves.Step.Mine.How.DIG;
            case io.github.mojolowjo.entropybot.aa.moves.Step.Walk w -> me != null && w.y() > me[1] + 2;
            case io.github.mojolowjo.entropybot.aa.moves.Step.ToStation w -> me != null && w.y() > me[1] + 2;     // 0.39.z65
            case io.github.mojolowjo.entropybot.aa.moves.Step.ToSurface w -> me != null && w.y() > me[1] + 2;     // 0.39.z68: the climb up
            default -> false;
        };
    }

    /** An ore step (the wander watch follows it). */
    public static boolean oreStep(io.github.mojolowjo.entropybot.aa.moves.Step step) {
        return step instanceof io.github.mojolowjo.entropybot.aa.moves.Step.MineAt || step instanceof io.github.mojolowjo.entropybot.aa.moves.Step.Mine;
    }

    /** "3/7 craft ..." -> 2 (0-based), else -1. */
    public static int stepIndex(String stepText) {
        if (stepText == null) return -1;
        int k = stepText.indexOf('/');
        if (k <= 0) return -1;
        try {
            return Integer.parseInt(stepText.substring(0, k).trim()) - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "3/7 craft iron_pickaxe 1" -> "craft iron_pickaxe 1". */
    public static String stepBody(String stepText) {
        if (stepText == null) return null;
        int k = stepText.indexOf(' ');
        return k < 0 ? stepText : stepText.substring(k + 1);
    }

    /** The last switches for aa status (newest last, at most 5). */
    public static final class Log {
        private final List<String> lines = new ArrayList<>();
        public int switches, checks, held;

        public void add(String line) {
            lines.add(line);
            while (lines.size() > 5) lines.remove(0);  // structural: display: how many items a text lists
        }

        public String text(boolean on) {
            return "replan " + (on ? "on" : "off") + ": checks " + checks + ", switches " + switches + ", held " + held
                    + (lines.isEmpty() ? "" : "; last: " + String.join(" | ", lines));
        }
    }
}
