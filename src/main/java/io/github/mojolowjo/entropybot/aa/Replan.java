package io.github.mojolowjo.entropybot.aa;

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

    public static final double PLACE_BLOCK = IronOptions.PLACE_BLOCK, REFILL = 0.05, CLIMB_BLOCK = 3, CLIMB_FIX = 10;
    public static final double CHEAPER = 0.30, MIN_SAVE = 3;
    public static final long EVERY_MS = 5_000, LONG_STEP_MS = 15_000, LOCK_MS = 20_000;

    public enum Leg { NONE, PILLAR, TUNNEL, WALK }

    // ---- aa-replan 2 (director, aa381): the real way back, chosen where the ore step ends ----
    //
    // The leg stored at plan time is only the scoring estimate. At the ore step's end the runtime scans the column over
    // the head up to the sky and prices every way back as a cost the chooser compares (no "if X then walk" rules):
    // tunnel up + pillar = breaks overhead (x1 a cell I dug, x6 a foreign one, x the BreakPolicy multiplier) + places x0.05;
    // an open own shaft is its cheapest case (no breaks). Hazards and the cap are costs too: lava/water/falling blocks in
    // the column, levels over 40, blocks I lack. Tunnel/pillar wins only when >= 20% cheaper than the walk.

    public static final int TUNNEL_CAP = 40;
    public static final double OWN_BREAK = 1, FOREIGN_BREAK = 6, UP_PLACE = 0.05, TUNNEL_BETTER = 0.20;
    /** Hazard costs per cell (walk-blocks): lava (burns, flows onto me), water (flows down the shaft), a falling block. */
    public static final double LAVA = 1000, WATER = 60, FALLING = 40, OVER_CAP = 25, NO_BLOCK = 30;

    /** One cell of the column over the head, bottom up. {@code mult} = the BreakPolicy multiplier, infinite = denied. */
    public record Cell(boolean open, boolean ownDug, double mult, boolean lava, boolean water, boolean falling) {
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
            if (c.lava()) haz += LAVA;
            if (c.water()) haz += WATER;
            if (c.falling()) haz += FALLING;
            if (!c.open() && !c.water() && !c.lava()) {
                double m = c.mult();
                if (Double.isInfinite(m) || Double.isNaN(m)) { brk = Double.POSITIVE_INFINITY; notes.add("a cell may not be broken"); break; }
                brk += (c.ownDug() ? OWN_BREAK : FOREIGN_BREAK) * m;
                breaks++;
                if (c.ownDug()) own++;
            }
        }
        if (haz > 0) notes.add("hazards " + Math.round(haz));
        double over = Math.max(0, levels - TUNNEL_CAP) * OVER_CAP;
        if (over > 0) notes.add((levels - TUNNEL_CAP) + " over the " + TUNNEL_CAP + " cap " + Math.round(over));
        double lack = Math.max(0, levels - throwaway) * NO_BLOCK;
        if (lack > 0) notes.add((levels - Math.max(0, throwaway)) + " blocks short " + Math.round(lack));
        double cost = brk + levels * UP_PLACE + haz + over + lack;
        Leg leg = breaks == 0 ? Leg.PILLAR : Leg.TUNNEL;
        String why = levels + " up, " + breaks + " breaks (" + own + " mine)" + (notes.isEmpty() ? "" : ", " + String.join(", ", notes));
        return new Way(leg, cost, why);
    }

    /** The choice at the ore step's end: tunnel/pillar when >= 20% cheaper than the walk, else walk. Both priced. */
    public static Way chooseNow(List<Cell> column, int levels, int throwaway, double walk) {
        if (levels <= 0) return new Way(Leg.NONE, 0, "already out");
        Way t = tunnelUp(column, levels, throwaway);
        String both = t.leg().name().toLowerCase(Locale.ROOT) + " " + fmt(t.cost()) + " (" + t.why() + ") vs walk " + fmt(walk);
        if (t.cost() <= walk * (1 - TUNNEL_BETTER)) return new Way(t.leg(), t.cost(), both);
        return new Way(Leg.WALK, walk, both + ": walking");
    }

    private static String fmt(double d) { return Double.isInfinite(d) ? "inf" : d < 10 ? String.format(Locale.ROOT, "%.1f", d) : String.valueOf(Math.round(d)); }

    /** The target cell in an option id like "dig_known -65 57 -10" (the last three ints), or null. */
    public static int[] targetOf(String id) {
        if (id == null) return null;
        String[] w = id.trim().split("\\s+");
        if (w.length != 4 || !w[0].endsWith("_known")) return null;
        try {
            return new int[]{Integer.parseInt(w[w.length - 3]), Integer.parseInt(w[w.length - 2]), Integer.parseInt(w[w.length - 1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Wandered farther than this from the planned target during the ore step: re-plan. */
    public static final int WANDER = 24;

    public static boolean wandered(int[] target, int[] me) {
        if (target == null || me == null) return false;
        long dx = target[0] - me[0], dz = target[2] - me[2];        // horizontal: a dig straight down is not a wander
        return dx * dx + dz * dz > (long) WANDER * WANDER;
    }

    /** The stored way back: the leg, its cost (walk-blocks), why, and the step it follows (0-based; -1 = the chain's end). */
    public record Ret(Leg leg, double cost, String why, int afterStep) {
        public static final Ret NONE = new Ret(Leg.NONE, 0, "no way back needed", -1);

        public String text() { return leg == Leg.NONE ? "" : "back: " + leg.name().toLowerCase(Locale.ROOT) + " " + Math.round(cost) + " (" + why + ")"; }
    }

    /** Pillar up my own shaft: depth x place x refill. */
    public static double pillarCost(int depth) { return Math.max(0, depth) * PLACE_BLOCK * REFILL; }

    /** Walk / stairs back up: depth x 3 + 10, or the walk back when that is longer. */
    public static double walkCost(int depth, double walkBack) { return Math.max(depth <= 0 ? 0 : depth * CLIMB_BLOCK + CLIMB_FIX, Math.max(0, walkBack)); }

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
            if (now - stepAt >= LONG_STEP_MS && now - lastAt >= EVERY_MS) {
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

    /** Steps shared at the start of both chains. */
    public static int commonPrefix(List<String> a, List<String> b) {
        int n = 0;
        while (n < a.size() && n < b.size() && same(a.get(n), b.get(n))) n++;
        return n;
    }

    /** Steps shared at the end of both chains (not overlapping the prefix). */
    public static int commonSuffix(List<String> a, List<String> b, int prefix) {
        int n = 0;
        while (n < a.size() - prefix && n < b.size() - prefix && same(a.get(a.size() - 1 - n), b.get(b.size() - 1 - n))) n++;
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
        String scores = (wandered ? "bug signal: wandered >" + WANDER + " from the target (a per-ore mine never should): " : "") + (cur == null ? running.id() + " gone" : cur.id() + " " + Math.round(c)) + " vs " + alt.id() + " " + Math.round(a);
        if (!(a <= c * (1 - CHEAPER) && c - a >= MIN_SAVE)) return new Decision(null, -1, c, a, scores + ": not 30% and 3 s cheaper");
        if (lastSwitchAt >= 0 && now - lastSwitchAt < LOCK_MS) return new Decision(null, -1, c, a, scores + ": locked " + (LOCK_MS - (now - lastSwitchAt) + 999) / 1000 + " s after a switch");
        if (midDigOrPillar) return new Decision(null, -1, c, a, scores + ": mid dig-down or pillar");
        List<String> rs = Options.Chains.split(running.command()), as = Options.Chains.split(alt.command());
        int p = commonPrefix(rs, as), s = commonSuffix(rs, as, p);
        if (stepIdx < p) return new Decision(null, -1, c, a, scores + ": the step that differs has not started");
        if (stepIdx >= rs.size() - s) return new Decision(null, -1, c, a, scores + ": past the part that differs");
        return new Decision(alt, p, c, a, scores);
    }

    /** True for a step that digs straight down (a mine ... dig step) or walks up (a pillar goto). */
    public static boolean digOrPillarStep(String step, int[] me) {
        if (step == null) return false;
        String t = step.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("mine ") && t.matches(".*\\bdig\\b.*")) return true;
        if (t.startsWith("goto ") && me != null) {
            String[] w = t.split("\\s+");
            try {
                if (w.length >= 4) return Integer.parseInt(w[2]) > me[1] + 2;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
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
            while (lines.size() > 5) lines.remove(0);
        }

        public String text(boolean on) {
            return "replan " + (on ? "on" : "off") + ": checks " + checks + ", switches " + switches + ", held " + held
                    + (lines.isEmpty() ? "" : "; last: " + String.join(" | ", lines));
        }
    }
}
