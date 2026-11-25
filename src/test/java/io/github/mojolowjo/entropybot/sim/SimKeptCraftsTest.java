package io.github.mojolowjo.entropybot.sim;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-kept-crafts review 2: the line the review named ("oak_log 16 -d" with a torch 4, no logs at the start; spruce here, the
 * map's trees) played by the simulator's runner (SimGame: the trips' legs through the planner worker's path, the runner's
 * holds with its own start bag, Goals.RUN_START_FACT for the planner's keptOff and the placing reserves). The log order
 * is met by the trip's cut (18), the run ends on "goal reached" and the torch's run starts with the met 16 in its start
 * bag; its charcoal takes one of the 2 spare logs, and the chest gets the 16 whole. The window the review named (a
 * sibling met while the run goes on) is covered by aa/KeptCraftsTest.aSiblingMetDuringTheRun_isHeldWhole_runnerAndPlannerAlike.
 */class SimKeptCraftsTest {
    @AfterEach
    void clear() {
        io.github.mojolowjo.entropybot.aa.brain.Only.set("off");
        io.github.mojolowjo.entropybot.aa.brain.BatchWay.clear();
        io.github.mojolowjo.entropybot.aa.moves.FactChange.clear();
        TestData.clear();
        io.github.mojolowjo.entropybot.aa.brain.Tuning.reset();
    }

    @Test
    void aLogOrderMetBeforeTheTorchsRun_reachesTheChestWhole() {
        SimWorld w = SimWorld.flat(63, "minecraft:grass_block", 40);
        for (int i = 0; i < 4; i++) w.column(10 + 2 * i, 64, 3, 6, "minecraft:spruce_log");
        for (int x = -12; x <= -6; x++) for (int z = -3; z <= 3; z++) for (int y = 64; y <= 66; y++) w.set(x, y, z, "minecraft:stone");
        w.set(3, 64, -5, "minecraft:chest");
        SimGame g = new SimGame(w, new int[]{0, 64, 0});
        g.mainAt = "3 64 -5";
        g.order("spruce_log", 16, "b1", true);
        g.order("torch", 4, "b1", false);
        String end = g.play(900);
        String t = g.timelineText();
        System.out.println(t);
        assertEquals("done", end, t);
        assertEquals(16, g.chest.getOrDefault("minecraft:spruce_log", 0), "the met log order's 16 reach the chest whole: " + g.chest);
        assertEquals(4, g.bag.getOrDefault("minecraft:torch", 0), "the torch order's 4 stay on the bot: " + g.bag);
        assertTrue(g.planner.drops.isEmpty(), "planner drops: " + g.planner.drops);
        assertFalse(t.contains("smelted 1 charcoal from {minecraft:spruce_log=1}") && g.chest.getOrDefault("minecraft:spruce_log", 0) < 16, "no kept log smelted: " + t);
    }
}
