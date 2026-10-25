package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z56 item 1 (run O 22:11:46-22:14:51: the craft's walk to its table, the bot in water at -27 62 -55 with its feet at
 * y 62 / 63 in turn, nothing logged for 3 min): the step probe's pure parts - off by default, the still rule (a bob is no
 * progress), the phase and still line texts.
 */
class Z56StepProbeTest {
    @Test
    void offByDefault() {
        assertFalse(ProbeLines.stepOn());
        assertFalse(ProbeLines.loadOn());
        Tuning.set("debug.stepProbe", 1);
        assertTrue(ProbeLines.stepOn());
        Tuning.set("debug.stepProbe", 0);
        assertFalse(ProbeLines.stepOn());
        assertEquals(20, ProbeLines.everyTicks());
        assertEquals(5, ProbeLines.phaseGapTicks());
    }

    @Test
    void aBobInWaterIsNoProgressAndSaysOnceASecond() {
        ProbeLines.Still s = new ProbeLines.Still();
        int bag = 42;
        assertFalse(s.due(100, new int[]{-27, 62, -55}, bag, 20), "the first look sets the anchor");
        int lines = 0;
        for (long t = 101; t <= 160; t++) if (s.due(t, new int[]{-27, (t / 70) % 2 == 0 ? 62 : 63, -55}, bag, 20)) lines++;
        assertEquals(3, lines, "60 ticks of a bob = 3 still lines (t 120, 140, 160)");
        assertEquals(60, s.stillTicks(160));
        assertEquals("-27 62 -55", s.anchor());
    }

    @Test
    void aMoveOrABagChangeIsProgress() {
        ProbeLines.Still s = new ProbeLines.Still();
        s.due(0, new int[]{0, 64, 0}, 1, 20);
        assertTrue(s.due(20, new int[]{0, 64, 0}, 1, 20));
        assertFalse(s.due(40, new int[]{1, 64, 0}, 1, 20), "a step aside starts the count again");
        assertFalse(s.due(60, new int[]{1, 64, 0}, 2, 20), "the bag changed (a block dug or placed)");
        assertTrue(s.due(80, new int[]{1, 64, 0}, 2, 20));
        assertFalse(s.due(100, new int[]{1, 66, 0}, 2, 20), "two blocks up is a climb");
        s.reset();
        assertFalse(s.due(120, new int[]{1, 66, 0}, 2, 20), "a new step starts fresh");
        assertFalse(s.due(130, null, 2, 20), "no player: nothing");
    }

    @Test
    void thePhaseLineSaysWhatChanged() {
        ProbeLines.Phase a = new ProbeLines.Phase("crafting furnace", "use -34 70 -55 for the crafting table", "Interact started: use -34 70 -55", "null");
        ProbeLines.Phase b = new ProbeLines.Phase("crafting furnace", "use -34 70 -55 for the crafting table", "Interact walking within 4.5 m of crafting_table at -34 70 -55",
                "GoalReachBlock{...}");
        String start = ProbeLines.phase(5, new int[]{-35, 63, -56}, "craft", "craft furnace 1", null, a, 0);
        assertTrue(start.startsWith("t=5 feet -35 63 -56 | craft craft furnace 1 | start: status crafting furnace | hands use -34 70 -55"), start);
        String ch = ProbeLines.phase(9, new int[]{-34, 63, -56}, "craft", "craft furnace 1", a, b, 2);
        assertTrue(ch.contains("in control Interact started: use -34 70 -55 -> Interact walking within 4.5 m"), ch);
        assertTrue(ch.contains("goal null -> GoalReachBlock{...}"), ch);
        assertFalse(ch.contains("status crafting furnace ->"), "unchanged parts are not repeated: " + ch);
        assertTrue(ch.endsWith("(2 phase change(s) in between not printed: debug.stepProbePhaseGapTicks)"), ch);
    }

    @Test
    void theStillLineCarriesTheStallFacts() {
        Map<String, Integer> uses = new LinkedHashMap<>();
        uses.put("minecraft:cobblestone", 8);
        String needs = ProbeLines.needs(uses, id -> id.equals("minecraft:cobblestone") ? 8 : 0);
        assertEquals("cobblestone 8/8", needs);
        assertEquals("none", ProbeLines.needs(Map.of(), id -> 0));
        ProbeLines.Phase ph = new ProbeLines.Phase("crafting furnace", "use -34 70 -55", "Interact walking within 4.5 m of crafting_table at -34 70 -55", "GoalReachBlock");
        String l = new ProbeLines.StepStill(4000, new int[]{-27, 63, -55}, "craft", "craft furnace 1", 600, "-27 62 -55", ph,
                true, new int[]{-27, 62, -55}, new int[]{-33, 70, -55}, 0, 40, false, "CALC_FINISHED_NOW_EXECUTING", 75,
                "walking within 4.5 m of crafting_table at -34 70 -55", needs, 31, "dirt 8").line();
        assertTrue(l.contains("NO PROGRESS 30.0 s (feet within one block of -27 62 -55, bag unchanged)"), l);
        assertTrue(l.contains("path -27 62 -55 -> -33 70 -55 at 0/40"), l);
        assertTrue(l.contains("calc none"), l);
        assertTrue(l.contains("last path event CALC_FINISHED_NOW_EXECUTING 75 ticks ago"), l);
        assertTrue(l.contains("needs in bag cobblestone 8/8 | reserves 31 | spare throwaway in bag dirt 8"), l);
        String idle = new ProbeLines.StepStill(1, null, "craft", "c", 0, "-", ph, false, null, null, -1, 0, true, null, 0, "-", "none", 0, "none").line();
        assertTrue(idle.contains("path none executing | calc in progress | last path event none this step"), idle);
    }
}
