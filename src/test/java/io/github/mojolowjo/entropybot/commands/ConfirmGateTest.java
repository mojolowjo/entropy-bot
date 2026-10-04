package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** B7e N item 8: big verbs ask first; confirm runs them; a timeout or another command cancels; chains pass. */
class ConfirmGateTest {
    static final String O = "mojolowjo";

    ConfirmGate gate() {
        return new ConfirmGate(new ConfirmGate.Facts() {
            @Override public String zone() { return "zone 0 60 0 to 9 64 9 (10x5x10)"; }

            @Override public String area(String name) { return name.equals("base") ? "x -10..50, z 0..60" : null; }
        });
    }

    static void asks(ConfirmGate.Gate g) {
        assertNotNull(g.reply(), "asks");
        assertNull(g.line());
        assertTrue(g.reply().startsWith("confirm? "), g.reply());
        assertTrue(g.reply().contains("say \"confirm\" within 30 s"), g.reply());
    }

    static void runs(ConfirmGate.Gate g, String line) {
        assertNull(g.reply(), "runs: " + g.reply());
        assertEquals(line, g.line());
    }

    @Test
    void buildClearAsksThenConfirmRunsIt() {
        ConfirmGate c = gate();
        ConfirmGate.Gate g = c.gate(O, "build clear", false, 1000);
        asks(g);
        assertTrue(g.reply().contains("zone 0 60 0 to 9 64 9"), "the summary names the zone: " + g.reply());
        assertTrue(c.waiting(O, 1000));
        runs(c.gate("MojoLowJo", "confirm", false, 20_000), "build clear");
        assertFalse(c.waiting(O, 20_000));
        assertTrue(c.gate(O, "confirm", false, 21_000).reply().startsWith("error: nothing to confirm"), "only once");
    }

    @Test
    void timeoutCancels() {
        ConfirmGate c = gate();
        asks(c.gate(O, "stripmine reset", false, 0));
        ConfirmGate.Gate late = c.gate(O, "confirm", false, 30_001);
        assertTrue(late.reply().startsWith("error: too late"), late.reply());
        assertTrue(late.reply().contains("stripmine reset"));
        assertTrue(c.gate(O, "confirm", false, 30_002).reply().startsWith("error: nothing to confirm"));
        // just inside the window
        asks(c.gate(O, "stripmine reset", false, 100));
        runs(c.gate(O, "confirm", false, 30_100), "stripmine reset");
    }

    @Test
    void anotherCommandCancels() {
        ConfirmGate c = gate();
        asks(c.gate(O, "build clear", false, 0));
        runs(c.gate(O, "status", false, 1000), "status");
        assertFalse(c.waiting(O, 1000));
        assertTrue(c.gate(O, "confirm", false, 2000).reply().startsWith("error: nothing to confirm"));
        // a chain's own steps (internal) don't cancel the owner's question
        asks(c.gate(O, "build clear", false, 3000));
        runs(c.gate(O, "deposit", true, 3500), "deposit");
        runs(c.gate(O, "confirm", false, 4000), "build clear");
        // another sender's command doesn't either
        asks(c.gate(O, "build clear", false, 5000));
        runs(c.gate("Steve", "status", false, 5500), "status");
        runs(c.gate(O, "confirm", false, 6000), "build clear");
    }

    @Test
    void trailingConfirmRunsAtOnce() {
        ConfirmGate c = gate();
        runs(c.gate(O, "build clear confirm", false, 0), "build clear");
        runs(c.gate(O, "stripmine reset confirm", false, 0), "stripmine reset");
        runs(c.gate(O, "dig 0 0 0 20 20 20 ores confirm", false, 0), "dig 0 0 0 20 20 20 ores");
        // area remove keeps its own word
        runs(c.gate(O, "area remove base confirm", false, 0), "area remove base confirm");
    }

    @Test
    void areaRemoveAsksAndRunsWithItsWord() {
        ConfirmGate c = gate();
        ConfirmGate.Gate g = c.gate(O, "area remove base", false, 0);
        asks(g);
        assertTrue(g.reply().contains("x -10..50, z 0..60"), g.reply());
        runs(c.gate(O, "confirm", false, 1000), "area remove base confirm");
    }

    @Test
    void digsOver1000AskSmallOnesDont() {
        ConfirmGate c = gate();
        runs(c.gate(O, "dig 0 0 0 9 9 9", false, 0), "dig 0 0 0 9 9 9");             // 1000 exactly
        ConfirmGate.Gate g = c.gate(O, "dig 0 0 0 9 9 10", false, 0);                    // 1100
        asks(g);
        assertTrue(g.reply().contains("1100 blocks"), g.reply());
        runs(c.gate(O, "confirm", false, 10), "dig 0 0 0 9 9 10");
        runs(c.gate(O, "dig 1 2 3", false, 0), "dig 1 2 3");                             // dig's own usage answers
        runs(c.gate(O, "dig 5 60 5 4 60 4 force", false, 0), "dig 5 60 5 4 60 4 force");
        assertEquals(1100, ConfirmGate.digVolume(Texts.words("9 9 10 0 0 0")));
    }

    @Test
    void chainStepsNeverAsk() {
        ConfirmGate c = gate();
        runs(c.gate(O, "build clear", true, 0), "build clear");
        runs(c.gate(O, "dig 0 0 0 99 99 99", true, 0), "dig 0 0 0 99 99 99");
        runs(c.gate(O, "build clear confirm", true, 0), "build clear");
        runs(c.gate(O, "stripmine reset", true, 0), "stripmine reset");
        assertFalse(c.waiting(O, 0));
        assertTrue(c.gate(O, "confirm", true, 0).reply().startsWith("error:"), "confirm as a chain step does nothing");
    }

    @Test
    void typedChainsAskOnceUnlessEachBigStepSaysConfirm() {
        ConfirmGate c = gate();
        ConfirmGate.Gate g = c.gate(O, "build clear then deposit", false, 0);
        asks(g);
        assertTrue(g.reply().contains("this chain has a big step"), g.reply());
        runs(c.gate(O, "confirm", false, 100), "build clear then deposit");
        runs(c.gate(O, "build clear confirm then deposit", false, 0), "build clear then deposit");   // a step's own confirm is dropped
        runs(c.gate(O, "deposit then eat then base", false, 0), "deposit then eat then base");
        // routines by name and repeat pass (saved routines keep working)
        runs(c.gate(O, "night", false, 0), "night");
        runs(c.gate(O, "repeat forever build clear", false, 0), "repeat forever build clear");
        runs(c.gate(O, "routine save night build clear then deposit", false, 0), "routine save night build clear then deposit");
    }

    @Test
    void otherVerbsPass() {
        ConfirmGate c = gate();
        for (String s : new String[]{"build floor cobblestone", "area add base here 60", "area list", "stripmine", "stripmine status", "confirmx", "help confirm"}) {
            runs(c.gate(O, s, false, 0), s);
        }
        assertTrue(c.gate(O, "confirm", false, 0).reply().startsWith("error: nothing to confirm"));
    }

    @Test
    void savingAndSchedulingLinesNeverAsk() {
        ConfirmGate c = gate();
        for (String s : new String[]{"routine save night deposit then build clear", "rule every 30m do farm then build clear", "rules",
                "repeat forever deposit then build clear", "run night", "repeat 3 build clear"}) {
            runs(c.gate(O, s, false, 0), s);
            assertFalse(c.waiting(O, 0), s);
        }
    }

    @Test
    void aBigStepThatIsNotFirstStillAsks() {
        ConfirmGate c = gate();
        ConfirmGate.Gate g = c.gate(O, "deposit then eat then build clear", false, 0);
        asks(g);
        assertTrue(g.reply().contains("build clear breaks"), g.reply());
        runs(c.gate(O, "confirm", false, 10), "deposit then eat then build clear");
    }

    @Test
    void aTrailingConfirmOnTheLastStepCoversTheWholeLine() {
        ConfirmGate c = gate();
        runs(c.gate(O, "build clear then deposit confirm", false, 0), "build clear then deposit");
        runs(c.gate(O, "deposit then build clear confirm", false, 0), "deposit then build clear");
        runs(c.gate(O, "build clear then dig 0 0 0 20 20 20 confirm", false, 0), "build clear then dig 0 0 0 20 20 20");
        runs(c.gate(O, "dig 0 0 0 1 1 1 confirm then deposit", false, 0), "dig 0 0 0 1 1 1 then deposit");
        runs(c.gate(O, "build clear then area remove base confirm", false, 0), "build clear then area remove base confirm");
        assertFalse(c.waiting(O, 0));
    }

    @Test
    void aSmallDigDropsItsConfirmWord() {
        ConfirmGate c = gate();
        runs(c.gate(O, "dig 0 0 0 1 1 1 confirm", false, 0), "dig 0 0 0 1 1 1");
        runs(c.gate(O, "dig 0 0 0 1 1 1 ores confirm", false, 0), "dig 0 0 0 1 1 1 ores");
        runs(c.gate(O, "dig 0 0 0 1 1 1", false, 0), "dig 0 0 0 1 1 1");
    }

    @Test
    void aBusyBotAnswersBusyInsteadOfAsking() {
        ConfirmGate c = gate();
        ConfirmGate.Gate g = c.gate(O, "build clear", false, 0, null, "busy: digging (pm \"stop\" first)");
        assertEquals("busy: digging (pm \"stop\" first)", g.reply());
        assertFalse(c.waiting(O, 0));
        assertEquals("busy: chain (pm \"stop\" first)", c.gate(O, "build clear then deposit", false, 0, "busy: chain (pm \"stop\" first)", null).reply());
        assertFalse(c.waiting(O, 0));
        // area remove is not a job: it still asks mid-job
        asks(c.gate(O, "area remove base", false, 0, null, "busy: digging (pm \"stop\" first)"));
        // not busy: asks as before
        asks(c.gate(O, "build clear", false, 0, null, null));
    }

    @Test
    void aDigOverTheCapErrorsBeforeAsking() {
        ConfirmGate c = gate();
        ConfirmGate.Gate g = c.gate(O, "dig 0 0 0 99 99 99", false, 0);                   // 1,000,000
        assertEquals("error: that box is too big (20000 blocks max)", g.reply());
        assertFalse(c.waiting(O, 0));
        assertTrue(c.gate(O, "dig 0 0 0 99 99 99 confirm", false, 0).reply().startsWith("error: that box is too big"));
        assertTrue(c.gate(O, "deposit then dig 0 0 0 99 99 99", false, 0).reply().startsWith("error: that box is too big"));
        asks(c.gate(O, "dig 0 0 0 19 19 49", false, 0));                                    // 20000 exactly: asks
    }
}