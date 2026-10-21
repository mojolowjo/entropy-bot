package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

/**
 * "Teleport home first" (the sim's tp run, B7e: pure, JUnit tests it). A walk to a spot near home (the /home landing)
 * starts with the server's /home when the bot is far from it: in another dimension, more than 40 blocks across, or
 * more than 10 levels up or down. After a /home that didn't move the bot, it doesn't try again for a minute.
 */
public final class TpRules {
    private TpRules() {}

    /** The destination counts as "near home" within this many blocks of the landing. */
    public static int homeR() { return Tuning.i("tpRules.homeR"); }
    /** Far: more than this many blocks across (x and z) from the destination... */
    public static int across() { return Tuning.i("tpRules.across"); }
    /** ...or more than this many levels up or down. */
    public static int levels() { return Tuning.i("tpRules.levels"); }
    /** No new /home this many ticks after one that didn't move the bot (1 minute). */
    public static long retryTicks() { return Tuning.l("tpRules.retryTicks"); }
    /** A /home that hasn't moved the bot after this many ticks failed (10 s). */
    public static long waitTicks() { return Tuning.l("tpRules.waitTicks"); }
    /** The bot "jumped" when it is more than this far (squared: 8 blocks) from where it was at the last check. */
    public static long jumpSq() { return Tuning.l("tpRules.jumpSq"); }

    /**
     * Worth a /home on the way to dest? home/homeDim: where /home lands (null: none known); destDim null = home's
     * dimension; me/botDim: where the bot is; tick and failedAt: the clock and the last failed /home.
     */
    public static boolean worth(int[] home, String homeDim, int[] dest, String destDim, int[] me, String botDim, long tick, long failedAt) {
        if (home == null || dest == null || tick - failedAt < retryTicks()) return false;
        String hd = homeDim == null ? "minecraft:overworld" : homeDim;
        if (!(destDim == null ? hd : destDim).equals(hd) || distSq(dest, home) > (long) homeR() * homeR()) return false;
        if (!botDim.equals(hd)) return true;
        long dx = me[0] - dest[0], dz = me[2] - dest[2];
        return dx * dx + dz * dz > (long) across() * across() || Math.abs(me[1] - dest[1]) > levels();
    }

    /** After a /home: "ok" when the bot jumped (another dimension, or 8+ blocks), null while waiting, "failed" after WAIT_TICKS. */
    public static String result(int[] last, String lastDim, int[] me, String dim, long sentAt, long tick) {
        if (!dim.equals(lastDim) || distSq(me, last) > jumpSq()) return "ok";
        return tick - sentAt < waitTicks() ? null : "failed";
    }

    static long distSq(int[] a, int[] b) {
        long dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }
}
