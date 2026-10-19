package io.github.mojolowjo.entropybot.gather;

/**
 * aa385: the gather's step ended the same way (no progress) {@link #TIMES} times in a row, each within {@link #WINDOW_TICKS} of the last:
 * the gather ends FAILED with that step's text, so the brain's Failures charge the option and the picker moves on (live:
 * "hunt 8 cow" said "I see no cow" every 30 s inside "gather food 8"). Numbers in the text ("2/4") do not make it new.
 * Pure Java (JUnit: RepeatFailTest). Loader notes: none.
 */
public final class RepeatFail {
    public static final int TIMES = 3;
    public static final long WINDOW_TICKS = 120 * 20;
    private String key;
    private int n;
    private long first;

    /** One no-progress end of {@code command} with {@code text} at {@code tick}; true = end the gather now. */
    public boolean note(String command, String text, long tick) {
        String k = (command + "|" + (text == null ? "" : text)).replaceAll("\\d+", "#");
        // the window runs from the previous end, not the first: one live hunt step lasts 60 s itself (two 30 s looks), so 120 s from end to end
        if (!k.equals(key) || tick - first > WINDOW_TICKS) {
            key = k;
            n = 0;
        }
        first = tick;
        return ++n >= TIMES;
    }

    /** A step made progress (or another step ran): the count starts afresh. */
    public void reset() { key = null; n = 0; }
}
