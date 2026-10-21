package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** AA_STEP3 B: slow-mode gating, the step times, the prediction ledger's row format and its error flag. */
class SlowModeTest {
    private static SlowMode.Plan plan(String key) {
        return new SlowMode.Plan(key, "objective", "wood: cut 16", "wood", "chop", 30, List.of(new SlowMode.Step("goto 1 64 1", 5, true)), 0, List.of(), List.of(), List.of());
    }

    @Test
    void offNeverHolds() {
        SlowMode m = new SlowMode();
        assertFalse(m.hold("task 1", 0, 20, () -> fail("no plan is built when off")));
    }

    @Test
    void nothingMovesBeforeGoOrTimeout() {
        SlowMode m = new SlowMode();
        assertTrue(m.set("on").startsWith("ok"));
        assertTrue(m.hold("task 1", 1000, 20, () -> plan("task 1")), "first ask builds the plan and holds");
        assertNotNull(m.pending());
        assertTrue(m.hold("task 1", 5000, 20, () -> fail("the plan is built once")));
        assertTrue(m.hold("task 1", 20_999, 20, () -> fail("built once")), "19.999 s: still held");
        assertFalse(m.hold("task 1", 21_000, 20, () -> fail("built once")), "20 s of silence: it goes");
        assertEquals(1, m.timeouts());
        assertNull(m.pending());
        assertFalse(m.hold("task 1", 22_000, 20, () -> fail("a released key never holds again")));
        assertTrue(m.hold("task 2", 23_000, 20, () -> plan("task 2")), "the next objective holds again");
    }

    @Test
    void goReleasesAtOnce() {
        SlowMode m = new SlowMode();
        m.set("on");
        assertTrue(m.go().startsWith("nothing waits"));
        assertTrue(m.hold("task 3", 0, 20, () -> plan("task 3")));
        assertTrue(m.go().startsWith("ok: go"));
        assertFalse(m.hold("task 3", 100, 20, () -> fail("released")));
        assertEquals(1, m.goes());
        assertTrue(m.lastRelease().contains("go"));
    }

    @Test
    void stepModeGatesEveryStepAndOffClears() {
        SlowMode m = new SlowMode();
        m.set("step");
        assertTrue(m.hold("task 4#1", 0, 20, () -> plan("task 4#1")));
        m.go();
        assertFalse(m.hold("task 4#1", 10, 20, () -> fail("x")));
        assertTrue(m.hold("task 4#2", 20, 20, () -> plan("task 4#2")), "the next step holds");
        assertTrue(m.set("off").contains("off"));
        assertNull(m.pending());
        assertFalse(m.hold("task 4#2", 30, 20, () -> fail("x")));
        assertNull(m.set("sideways"), "an unknown word is not a mode");
    }

    @Test
    void jsonShowsThePlanAndSecondsLeft() {
        SlowMode m = new SlowMode();
        m.set("on");
        m.hold("task 5", 0, 20, () -> plan("task 5"));
        var j = m.json(5000, 20);
        assertEquals("on", j.get("mode").getAsString());
        var p = j.getAsJsonObject("pending");
        assertEquals(15, p.get("secondsLeft").getAsInt());
        assertEquals("goto 1 64 1", p.getAsJsonArray("steps").get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void slowPauseDefaultIs20() {
        assertEquals(20, Tuning.get("slowPauseS"));
    }

    @Test
    void stepTimesUseTheGameNumbersAndShareTheRest() {
        List<String> steps = List.of("goto 43 64 0", "mine iron_ore 3", "wait 4", "cut 16");
        var est = StepTimes.estimate(steps, new int[]{0, 64, 0}, 60);
        assertTrue(est.get(0).known());
        assertEquals(43 * StepTimes.PATH_FACTOR / 4.317, est.get(0).seconds(), 0.01);
        assertEquals(4, est.get(2).seconds(), 1e-9);
        double rest = (60 - est.get(0).seconds() - 4) / 2;
        assertFalse(est.get(1).known());
        assertEquals(rest, est.get(1).seconds(), 1e-9);
        assertEquals(rest, est.get(3).seconds(), 1e-9);
        assertTrue(Double.isNaN(StepTimes.estimate(List.of("cut 16"), null, Double.NaN).get(0).seconds()), "no total, no guess");
        assertEquals("goto", StepTimes.key("soft goto 1 2 3"));
        assertEquals(3, StepTimes.route(steps, new int[]{0, 64, 0}, new int[]{9, 64, 9}).size());
    }

    @Test
    void ledgerRowFormatRoundTrips() {
        var r = new Predictions.Row(1_700_000_000_000L, "wood: cut 16", "mine", "mine iron_ore 3, \"deep\"", 12.34, 15.0, 31.7, 2.0);
        String text = Predictions.append(false, r);
        assertTrue(text.startsWith(Predictions.HEADER + "\n"));
        assertEquals(Predictions.HEADER + "\n1700000000000,wood: cut 16,mine,\"mine iron_ore 3, \"\"deep\"\"\",12.3,15.0,31.7,2.0\n", text);
        assertFalse(Predictions.append(true, r).startsWith("time"), "no second header");
        int[] bad = {0};
        var rows = Predictions.parse(text + "garbage line\n", bad);
        assertEquals(1, rows.size());
        assertEquals(1, bad[0], "a bad line is counted, not hidden");
        assertEquals("mine iron_ore 3, \"deep\"", rows.get(0).step());
        assertEquals(12.3, rows.get(0).predictedS(), 1e-9);
    }

    @Test
    void errorFlagNeedsFiveSamplesAndMoreThan25Percent() {
        List<Predictions.Row> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(new Predictions.Row(i, "o", "mine", "mine x", 10, 14, 0, 0));       // +40%
        for (int i = 0; i < 4; i++) rows.add(new Predictions.Row(i, "o", "craft", "craft y", 1, 5, 0, 0));       // way off, 4 samples only
        for (int i = 0; i < 6; i++) rows.add(new Predictions.Row(i, "o", "goto", "goto 1 2 3", 10, 11, 0, 0));   // +10%
        var off = Predictions.off(rows);
        assertEquals(1, off.size());
        assertEquals("mine", off.get(0).key());
        assertEquals(0.4, off.get(0).error(), 1e-9);
        String t = Predictions.text(rows, 0, "x");
        assertTrue(t.contains("where the model is wrong"), t);
        assertTrue(t.contains("mine: 5 samples"), t);
        assertFalse(t.contains("craft: "), t);
        // the last 20 only
        List<Predictions.Row> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) many.add(new Predictions.Row(i, "o", "k", "step" + i, 10, 10, 0, 0));
        String m = Predictions.text(many, 0, "x");
        assertTrue(m.contains("last 20 of 30"));
        assertFalse(m.contains("step9:"));
        assertTrue(m.contains("step29:"));
        assertTrue(Predictions.text(List.of(), 0, "f").contains("no rows yet"));
    }
}
