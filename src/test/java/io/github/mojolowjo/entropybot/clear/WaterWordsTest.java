package io.github.mojolowjo.entropybot.clear;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Water plan, the dig's side: the words, when it gives up, the end messages, the junk it keeps and picks up. */
class WaterWordsTest {
    @Test
    void digWords() {
        DigArgs a = DigArgs.parse("247 -46 853 310 -44 855 floor junk drop water");
        assertTrue(a.water() && !a.large() && a.floor() && a.junkDrop());
        assertNull(a.floorBlock(), "water is a word, not the floor's block");
        DigArgs b = DigArgs.parse("1 2 3 4 5 6 water large");
        assertTrue(b.water() && b.large());
        DigArgs c = DigArgs.parse("1 2 3 4 5 6 large floor");
        assertTrue(c.water() && c.large(), "large alone means water too");
        assertFalse(DigArgs.parse("1 2 3 4 5 6 ores").water());
        assertTrue(DigArgs.USAGE.endsWith("[water [large]]"));
    }

    @Test
    void itGivesUpAfterRoundsThatGetNowhere() {
        WaterPlan.Tally t = new WaterPlan.Tally();
        assertNull(WaterPlan.giveUp(t, 0), "the first round may start where the bot stands at the water");
        t.rounds = 1;
        assertNull(WaterPlan.giveUp(t, 0));
        assertNull(WaterPlan.giveUp(t, 0));
        assertEquals("sealing got me no further (3 rounds in a row)", WaterPlan.giveUp(t, 0));
        assertNull(WaterPlan.giveUp(t, 5), "a dig that got on starts the count again");
        t.rounds = WaterPlan.MAX_ROUNDS;
        assertEquals("gave up on the water after " + WaterPlan.MAX_ROUNDS + " rounds", WaterPlan.giveUp(t, 5));
    }

    @Test
    void endMessages() {
        String clear = "blocked by water at 377 -45 854 - broke 21 blocks; 515 left";
        assertEquals(clear + " - 3 source blocks inside the dig - couldn't seal it: nowhere dry to stand within reach of 378 -45 856",
                WaterPlan.blockedMessage(clear, "3 source blocks inside the dig", "nowhere dry to stand within reach of 378 -45 856", null));
        assertEquals(clear + " - a large body of water (65+ source blocks) - confirm? ...",
                WaterPlan.blockedMessage(clear, "a large body of water (65+ source blocks)", WaterPlan.LARGE_ASK, "confirm? ..."));
        WaterPlan.Tally t = new WaterPlan.Tally();
        assertEquals("ok: done digging x - broke 4 blocks", WaterPlan.endMessage("ok: done digging x - broke 4 blocks", t, 4, true));
        t.broken = 100;
        t.add(new WaterPlan.Round(null, WaterScan.Kind.SOURCES_INSIDE,
                List.of(new WaterPlan.Placement(new Pos(377, -45, 854), null, "minecraft:cobbled_deepslate", WaterShell.Role.FILL)), false, 1, 1, ""),
                null, ClearBox.of(0, -46, 853, 500, -44, 855));
        assertEquals("ok: done digging x - broke 104 blocks; sealed 1 water cell (1 source) at x 377",
                WaterPlan.endMessage("ok: done digging x - broke 4 blocks", t, 4, true));
        assertEquals("ok: done digging x - broke 4 blocks; sealed 1 water cell (1 source) at x 377",
                WaterPlan.endMessage("ok: done digging x - broke 4 blocks", t, 4, false), "a floor dig's fill counts the blocks itself");
        assertEquals("dig 1 2 3 4 5 6 water large", WaterPlan.largeLine("dig 1 2 3 4 5 6 water"));
        assertEquals("dig 1 2 3 4 5 6 water large", WaterPlan.largeLine("dig 1 2 3 4 5 6 water large"));
    }

    @Test
    void aWaterDigWithJunkDropPicksJunkUpWhileShort() {
        ClearJob water = ClearJob.start(new ClearJob.Options().box(ClearBox.of(0, 0, 0, 9, 2, 2)).junkDrop(true).water(true, false), null, null);
        ClearJob plain = ClearJob.start(new ClearJob.Options().box(ClearBox.of(0, 0, 0, 9, 2, 2)).junkDrop(true), null, null);
        Map<String, Integer> few = Map.of("minecraft:cobbled_deepslate", 64 + 20);
        Map<String, Integer> lots = Map.of("minecraft:cobbled_deepslate", 64 + 200);
        assertTrue(JunkDrop.chase("minecraft:cobbled_deepslate", water, few), "20 placeable: below twice the reserve");
        assertFalse(JunkDrop.chase("minecraft:cobbled_deepslate", water, lots));
        assertFalse(JunkDrop.chase("minecraft:cobbled_deepslate", plain, few), "without water: junk stays on the ground");
        assertTrue(water.water);
    }
}
