package io.github.mojolowjo.entropybot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.39.z77 pending (run BV 13:59:36: a leg's "no way" built while its cheaper ways were still computing - rule 15, the same
 * class as the -64 heightmap and underDepth's 1: an unknown read as a verdict). A playbook leg's no-way verdict is built only
 * through {@code NeedChain.legNoWay}, the one place that checks every way of the leg has a final answer: the leg loop
 * ({@code playbookChain}) never calls {@code playbookFails} itself. Comments and strings are not scanned
 * (Z76HeightmapReadsTest.code).
 */
class Z77NoWayVerdictScanTest {
    static final Path NEED_CHAIN = Path.of("src/main/java/io/github/mojolowjo/entropybot/aa/NeedChain.java");

    /** The body of the method whose declaration starts with {@code head} (braces matched on the blanked code); empty: not found. */
    static String body(String code, String head) {
        int at = code.indexOf(head);
        if (at < 0) return "";
        int open = code.indexOf('{', at), depth = 0;
        for (int i = open; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return code.substring(open, i + 1);
        }
        return "";
    }

    @Test
    void theBodyIsTheWholeMethod() {
        String probe = "class P {\n  static int a() { if (x) { return 1; } return \"}\".length(); }\n  static int b() { return 2; }\n}\n";
        String b = body(Z76HeightmapReadsTest.code(probe), "static int a(");
        assertTrue(b.contains("return 1") && b.contains(".length()") && !b.contains("return 2"), b);
    }

    @Test
    void everyLegNoWayGoesThroughTheFinalAnswerCheck() throws IOException {
        String code = Z76HeightmapReadsTest.code(Files.readString(NEED_CHAIN));
        String legs = body(code, "private static Chain playbookChain(");
        assertFalse(legs.isEmpty(), "playbookChain found");
        assertFalse(legs.contains("playbookFails("), "a leg's no-way built without the final-answer check (use legNoWay)");
        int n = 0;
        for (int i = legs.indexOf("legNoWay("); i >= 0; i = legs.indexOf("legNoWay(", i + 1)) n++;
        assertEquals(4, n, "the strip's fail and explore, a get's fail and explore under ground: each through legNoWay");
        String check = body(code, "private static Chain legNoWay(");
        assertTrue(check.contains("c.waited") && check.contains("waitsHere(") && check.contains("NOT_PRICED"), "legNoWay reads the ways dropped waiting: " + check);
    }

    /**
     * 0.39.z77 choose (run BW: an explore picked over a way still computing): the pick between ways lets an explore win only
     * after it read every other way's answer - {@code choose} asks {@code noAnswerYet} of the ways when its winner is stuck,
     * and that check covers both a failure that is a wait ({@code answerPending}) and a way that stands ({@code stands}).
     */
    @Test
    void anExplorePickReadsTheOtherWaysAnswersFirst() throws IOException {
        String code = Z76HeightmapReadsTest.code(Files.readString(NEED_CHAIN));
        String choose = body(code, "private static Trial choose(");
        assertFalse(choose.isEmpty(), "choose found");
        int stuck = choose.indexOf("if (win.stuck() && !stands(win, stepsAt))"), restore = choose.indexOf("c.restore(win.after())");
        assertTrue(stuck >= 0 && restore > stuck, "the explore check runs before the winner is taken: " + choose);
        assertTrue(choose.substring(stuck, restore).contains("noAnswerYet("), "the explore check reads the other ways' answers");
        String open = body(code, "private static boolean noAnswerYet(");
        assertTrue(open.contains("answerPending(") && open.contains("stands("), "a wait and a stand are both no answer yet: " + open);
        String waiting = body(code, "public boolean waiting(");
        assertTrue(waiting.contains("answerPending("), "the chain's waiting states and the pick's are one predicate: " + waiting);
    }
}
