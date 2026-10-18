package io.github.mojolowjo.entropybot.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.35.2: the AI operator screen's words and its layout budget, in plain Java so JUnit checks that everything fits
 * without a game: the smallest common GUI is 854x480 at GUI scale auto (2) = 427x240 scaled pixels; a large one is
 * 1920x1080 at scale 4 = 480x270. The text column is {@link #COLUMN} px wide; the vanilla font is at most
 * {@link #CHAR_PX} px a character (most are 5-6), so {@link #wrap} over-estimates the lines the real
 * {@code Font.split} makes.
 */
public final class AiScreenText {
    private AiScreenText() {}

    public static final int COLUMN = 300, CHAR_PX = 6, LINE_PX = 10, MIN_W = 427, MIN_H = 240;
    /** Top of the text area under the widget rows, and the top of the bottom buttons (from the screen's bottom). */
    public static final int TEXT_TOP_P1 = 120, TEXT_TOP_P2 = 104, BUTTONS_FROM_BOTTOM = 52;

    // page 1: connection
    public static final String TITLE_1 = "AI operator (1/2): connection";
    public static final String HELP_1 = "Off by default. On: the bot asks this model what to do for your request (ai <request>). "
            + "The key stays in this game's entropybot folder.";
    // page 2: limits
    public static final String TITLE_2 = "AI operator (2/2): limits";
    public static final String HELP_2 = "Tokens: bits of text the model reads and writes (about 4 letters). Tokens/day stops the AI for the day; "
            + "free models cost nothing. Paid on: it also stops at USD/day. Requests/hour: how often it may ask. "
            + "Reasoning off: answers at once (cheapest). Max output: longest answer; raise it if status says it ran out.";
    /** Labels drawn left of a box, with the box's x offset in the column (the label must end before it). */
    public static final List<Object[]> LABELS = List.of(
            new Object[]{"Model id", 70}, new Object[]{"Base URL", 70}, new Object[]{"API key", 70},
            new Object[]{"USD/day", 46}, new Object[]{"Tokens/day", 64}, new Object[]{"Req/hour", 48}, new Object[]{"Max output", 64});
    /** Button labels and their widths. */
    public static final List<Object[]> BUTTONS = List.of(
            new Object[]{"AI operator: off", 148}, new Object[]{"OpenAI-compatible", 148}, new Object[]{"Demo (no network)", 148}, new Object[]{"Paid models: off", 148},
            new Object[]{"Reasoning: off", 148}, new Object[]{"Test the key", 97}, new Object[]{"Log folder", 97}, new Object[]{"Save", 97}, new Object[]{"Done (no save)", 148},
            new Object[]{"Show", 48}, new Object[]{"Next: limits >", 148}, new Object[]{"< Back", 148});

    /** Greedy word wrap with the worst-case character width (a long word is cut). */
    public static List<String> wrap(String text, int widthPx) {
        int max = Math.max(1, widthPx / CHAR_PX);
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : text.split("\\s+")) {
            while (w.length() > max) {
                if (!line.isEmpty()) { out.add(line.toString()); line.setLength(0); }
                out.add(w.substring(0, max));
                w = w.substring(max);
            }
            if (line.length() + (line.isEmpty() ? 0 : 1) + w.length() > max) { out.add(line.toString()); line.setLength(0); }
            if (!line.isEmpty()) line.append(' ');
            line.append(w);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    /** How many text lines fit between top and the bottom buttons on a screen this high. */
    public static int linesFit(int top, int height) { return Math.max(0, (height - BUTTONS_FROM_BOTTOM - 4 - top) / LINE_PX); }

    /** The column width on a screen this wide (a 10 px margin each side). */
    public static int column(int width) { return Math.min(COLUMN, width - 20); }
}
