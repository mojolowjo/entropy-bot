package io.github.mojolowjo.entropybot.commands;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.30.5 (trial 9, finding 4: climb x4 and dig stairs up all ended "couldn't get there", the bot sat at y 75 under the
 * camp). Remembers climb's failures per spot ({@link #NEAR} blocks): after {@link #AFTER} in a row the next climb digs
 * a plain staircase up ({@link StairPlan#upSteps}, via engine) toward the nearest known surface point (the camp mark,
 * else straight on north). Also the stair chains' honest ending: which block stopped the walk and the next verb.
 *
 * <p>Pure (JUnit: {@code FoodClimbTest}). Loader notes: none.
 */
public final class ClimbFallback {
    public static final int NEAR = 8;
    public static final int AFTER = 2;

    private int[] at;
    private int fails;
    private int[] started;

    /** A climb chain started at pos. */
    public void started(int[] pos) { started = pos; }

    /** The last climb chain's end (Chains.takeEnd), folded in once; null: nothing new. */
    public void ended(String end) {
        if (started == null || end == null) return;
        if (end.startsWith("done") || end.startsWith("ok")) succeeded();
        else failed(started);
        started = null;
    }

    public void failed(int[] pos) {
        // live 0.30.5: each failed climb gets a few blocks up before it stops, so the spot follows the latest failure
        if (at != null && near(at, pos)) fails++;
        else fails = 1;
        at = pos.clone();
    }

    public void succeeded() { at = null; fails = 0; }

    /** Failures in a row near pos (0 when the last ones were somewhere else). */
    public int fails(int[] pos) { return at != null && near(at, pos) ? fails : 0; }

    static boolean near(int[] a, int[] b) {
        long dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz <= (long) NEAR * NEAR;
    }

    /** Fall back to the plain staircase? */
    public static boolean fallback(int fails) { return fails >= AFTER; }

    /** The side toward target (the longer horizontal axis; x wins a tie); null when no target or it is straight above. */
    public static String toward(int[] here, int[] target) {
        if (target == null) return null;
        int dx = target[0] - here[0], dz = target[2] - here[2];
        if (dx == 0 && dz == 0) return null;
        if (Math.abs(dx) >= Math.abs(dz)) return dx > 0 ? "east" : "west";
        return dz > 0 ? "south" : "north";
    }

    /** Steps up: to the higher of the target's y and the surface over me, 1..StairPlan.MAX. targetY: Integer.MIN_VALUE = none. */
    public static int stepsUp(int y, int targetY, int surfaceY) {
        int top = Math.max(targetY, surfaceY);
        return Math.max(1, Math.min(StairPlan.MAX, top - y));
    }

    /** The fallback's start line. */
    public static String startText(boolean toCamp, String dir, int n) {
        return "climb: my own way failed twice, digging stairs up " + (toCamp ? "toward the camp (" + n + " blocks up, " + dir + ")" : dir + " (" + n + " blocks up; no camp mark)");
    }

    private static final Pattern GOTO = Pattern.compile("\\(goto (-?\\d+) (-?\\d+) (-?\\d+)\\)");

    /** The goto step's goal in a chain end ("stopped at step 4 (goto 3 70 -2): ..."), or null. */
    public static int[] gotoGoal(String msg) {
        if (msg == null) return null;
        Matcher m = GOTO.matcher(msg);
        if (!m.find()) return null;
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }

    /** Does this chain end need the honest note (a stair chain that ended "couldn't get there" or "stuck")? */
    public static boolean needsNote(String name, String msg) {
        if (msg == null || !(name.equals("climb") || name.startsWith("dig stairs"))) return false;
        if (msg.contains(" - in the way: ")) return false;
        String low = msg.toLowerCase();
        return low.contains("couldn't get there") || low.contains("stuck");
    }

    /**
     * The honest end: what stopped the walk at goal g and the next verb. feet/head: the block ids there (null: open),
     * floorOpen: nothing to stand on under g. A hole hint the walk added is dropped (the note says what to do).
     */
    public static String honestEnd(String msg, int[] g, String feet, String head, boolean floorOpen) {
        return honestEnd(msg, g, feet, head, floorOpen, false);
    }

    /** hard: the block in the way can't be dug (bedrock, a fluid): then the next verb is climb (it goes another way). */
    public static String honestEnd(String msg, int[] g, String feet, String head, boolean floorOpen, boolean hard) {
        if (hard && (feet != null || head != null)) {
            String m = cutHint(msg);
            String id = feet != null ? feet : head;
            int y = feet != null ? g[1] : g[1] + 1;
            return m + " - in the way: " + id + " at " + g[0] + " " + y + " " + g[2] + " (I can't dig it) - next: climb (another way; after two fails it digs stairs up toward the camp)";
        }
        return honestSoft(msg, g, feet, head, floorOpen);
    }

    private static String cutHint(String msg) {
        String m = msg;
        for (String cut : new String[]{" - I'm in a hole", " - I'm walled in"}) {
            int i = m.indexOf(cut);
            if (i > 0) m = m.substring(0, i);
        }
        return m;
    }

    private static String honestSoft(String msg, int[] g, String feet, String head, boolean floorOpen) {
        String m = msg;
        for (String cut : new String[]{" - I'm in a hole", " - I'm walled in"}) {
            int i = m.indexOf(cut);
            if (i > 0) m = m.substring(0, i);
        }
        String xyz = g[0] + " " + g[1] + " " + g[2];
        if (feet != null || head != null) {
            String id = feet != null ? feet : head;
            int y = feet != null ? g[1] : g[1] + 1;
            return m + " - in the way: " + id + " at " + g[0] + " " + y + " " + g[2] + " - next: dig " + xyz + " " + g[0] + " " + (g[1] + 1) + " " + g[2]
                    + StairPlan.VIA_ENGINE + " then goto " + xyz + " (or climb again: after two fails it digs stairs up on its own)";
        }
        if (floorOpen) return m + " - in the way: a gap under " + xyz + " (nothing to stand on at " + g[0] + " " + (g[1] - 1) + " " + g[2]
                + ") - next: place cobblestone " + g[0] + " " + (g[1] - 1) + " " + g[2] + " then goto " + xyz;
        return m + " - in the way: nothing I can see (the cells at " + xyz + " are open; the walk itself failed) - next: goto " + xyz + " (or climb again)";
    }
}
