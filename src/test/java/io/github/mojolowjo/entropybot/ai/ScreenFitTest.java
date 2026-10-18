package io.github.mojolowjo.entropybot.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.35.2: the AI screen's words fit at 427x240 (854x480, GUI scale auto) and 480x270 (1080p, scale 4), worst-case 6 px a char. */
class ScreenFitTest {
    @Test
    void labelsAndButtonsFit() {
        for (Object[] l : AiScreenText.LABELS)
            assertTrue(((String) l[0]).length() * AiScreenText.CHAR_PX <= (Integer) l[1], "label " + l[0]);
        for (Object[] b : AiScreenText.BUTTONS)
            assertTrue(((String) b[0]).length() * AiScreenText.CHAR_PX <= (Integer) b[1] - 8, "button " + b[0]);
        assertTrue(AiScreenText.TITLE_1.length() * AiScreenText.CHAR_PX <= AiScreenText.MIN_W - 20);
        assertTrue(AiScreenText.TITLE_2.length() * AiScreenText.CHAR_PX <= AiScreenText.MIN_W - 20);
    }

    @Test
    void explanationsFitAboveTheButtons() {
        for (int[] screen : new int[][]{{427, 240}, {480, 270}}) {
            int col = AiScreenText.column(screen[0]);
            assertEquals(300, col, "the column fits with a margin");
            // page 2: the whole explanation
            int need2 = AiScreenText.wrap(AiScreenText.HELP_2, col).size();
            assertTrue(need2 <= AiScreenText.linesFit(AiScreenText.TEXT_TOP_P2, screen[1]), need2 + " lines on " + screen[0] + "x" + screen[1]);
            // page 1: key line + status (2) + test (2) + help
            int need1 = 1 + 2 + 2 + AiScreenText.wrap(AiScreenText.HELP_1, col).size();
            assertTrue(need1 <= AiScreenText.linesFit(AiScreenText.TEXT_TOP_P1, screen[1]) + 3, need1 + " lines (help may be cut on the smallest screen)");
            assertTrue(AiScreenText.linesFit(AiScreenText.TEXT_TOP_P1, screen[1]) >= 5, "status and test always show");
        }
        for (String l : AiScreenText.wrap(AiScreenText.HELP_2, 300)) assertTrue(l.length() * AiScreenText.CHAR_PX <= 300, l);
    }
}
