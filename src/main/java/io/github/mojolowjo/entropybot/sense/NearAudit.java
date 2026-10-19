package io.github.mojolowjo.entropybot.sense;

/**
 * 0.36.a9 (to-do vanilla 1b, "the ore list never learns new ore"): which chunk the near-bot check reads again next. The
 * 3 x 3 chunks round the bot take turns (a plain round robin of nine), one every few ticks, so a block change the patch path missed (a dropped hook, a patch that did not take) shows in the
 * ore index within seconds instead of at the next minute-long self-audit round over every loaded chunk. Plain Java, JUnit.
 * Loader notes: none. Errors: none to report (the caller counts what a check had to fix).
 */
public final class NearAudit {
    private NearAudit() {}

    /** Ticks between two near checks (10 = twice a second: the 3 x 3 are all checked again within 4.5 s). */
    public static final int EVERY_TICKS = 10;

    /** The chunk {cx, cz} for turn {@code cursor} (0, 1, 2...) round the bot's chunk: nine in a row, the middle one first. */
    public static int[] pick(int ecx, int ecz, long cursor) {
        int i = (int) Math.floorMod(cursor, 9L);
        if (i == 0) return new int[]{ecx, ecz};
        int j = i - 1;                       // 0..7: the eight neighbours
        int k = j >= 4 ? j + 1 : j;          // 0..8 without 4 (the middle)
        return new int[]{ecx + (k % 3) - 1, ecz + (k / 3) - 1};
    }
}
