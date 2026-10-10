package io.github.mojolowjo.entropybot.brain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 0.25.1: direct JUnit tests for job.dusk, night.shelter.nobed, Interrupts.bagTrip,
 * PlayRules.caveTrip, PlayRules.woodEnough and PlayRules.woodJob.
 */
class JobDuskAndShelterTests {

    // ---- helpers ----

    static BrainState state() {
        BrainState s = new BrainState();
        s.basePos = new int[]{0, 64, 0};
        s.armourTier = 3;
        s.weaponTier = 3;
        return s;
    }

    static BrainTree.Ctx ctx(BrainState s, BrainConfig c) {
        return new BrainTree.Ctx(s, c, Needs.score(s, c, List.of(), Map.of()), null, false);
    }

    // ---- job.dusk tests ----

    @Test
    void jobDuskSwitchesToShelterWhenDuskNearAndShelterBand() {
        BrainTree t = new BrainTree();
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state();
        s.armourTier = 0;
        s.weaponTier = 0;
        s.dayTime = 11500;  // dusk at 13000: 1.5 hours away
        // Use a surface job (farm) so job.dusk can trigger
        s.brainJob = "farm";
        BrainTree.Running running = new BrainTree.Running("gather", "farm", 50);
        BrainTree.Ctx ctx = new BrainTree.Ctx(s, c, Needs.score(s, c, List.of(), Map.of()), running, false);
        BrainTree.Decision d = t.run(ctx);
        assertEquals("job.dusk", d.branch());
        assertTrue(d.chain().contains("shelter"), "job.dusk should switch to shelter chain");
        assertTrue(d.reason().contains("dusk is near"), d.reason());
    }

    @Test
    void jobDuskDoesNotSwitchWhenAlreadyUnderground() {
        BrainTree t = new BrainTree();
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state();
        s.armourTier = 0;
        s.weaponTier = 0;
        s.dayTime = 11500;  // dusk near
        s.brainJob = "mine cave any 20 10m";
        BrainTree.Running running = new BrainTree.Running("gather", "mine cave any 20 10m", 50);
        BrainTree.Ctx ctx = new BrainTree.Ctx(s, c, Needs.score(s, c, List.of(), Map.of()), running, false);
        BrainTree.Decision d = t.run(ctx);
        // Should not trigger job.dusk since the job is already underground
        assertNotEquals("job.dusk", d.branch(), "job.dusk should not trigger for underground jobs");
    }

    @Test
    void jobDuskDoesNotSwitchWhenNotShelterBand() {
        BrainTree t = new BrainTree();
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state();
        s.dayTime = 11500;  // dusk near
        s.brainJob = "mine strip";
        BrainTree.Running running = new BrainTree.Running("gather", "mine strip", 50);
        BrainTree.Ctx ctx = new BrainTree.Ctx(s, c, Needs.score(s, c, List.of(), Map.of()), running, false);
        BrainTree.Decision d = t.run(ctx);
        // Full iron gear means NORMAL band, not SHELTER
        assertNotEquals("job.dusk", d.branch(), "job.dusk should not trigger for NORMAL band");
    }

    @Test
    void jobDuskDoesNotSwitchWhenNightDoneHere() {
        BrainTree t = new BrainTree();
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state();
        s.armourTier = 0;
        s.weaponTier = 0;
        s.dayTime = 11500;  // dusk near
        s.brainJob = "mine strip";
        BrainTree.Running running = new BrainTree.Running("gather", "mine strip", 50);
        BrainTree.Ctx ctx = new BrainTree.Ctx(s, c, Needs.score(s, c, List.of(), Map.of()), running, true);
        BrainTree.Decision d = t.run(ctx);
        assertNotEquals("job.dusk", d.branch(), "job.dusk should not trigger when nightDoneHere");
    }

    // ---- night.shelter.nobed tests ----

    @Test
    void nightShelterNobedWhenUndergroundBandAndNoBed() {
        BrainTree t = new BrainTree();
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state();
        // Score = 2*3 + 2*2 + 2 + 1 + 1 = 12, which is in UNDERGROUND band (12 < 16)
        s.armourTier = 2;
        s.weaponTier = 2;
        s.night = true;
        // sleepAuto must be false to prevent "night.sleep" from triggering first
        // (sleep has score 60, shelter.nobed has score 55)
        s.sleepAuto = false;
        s.othersSleeping = true;
        s.bedNear = false;  // no bed nearby
        s.dayTime = 14000;
        BrainTree.Ctx ctx = ctx(s, c);
        BrainTree.Decision d = t.run(ctx);
        assertEquals("night.shelter.nobed", d.branch());
        assertTrue(d.chain().contains("shelter"), "night.shelter.nobed should use shelter chain");
    }

    @Test
    void nightShelterNobedDoesNotTriggerWhenSleepAutoOff() {
        BrainTree t = new BrainTree();
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state();
        s.armourTier = 1;
        s.weaponTier = 1;
        s.night = true;
        s.sleepAuto = false;
        s.othersSleeping = true;
        s.bedNear = false;
        s.dayTime = 14000;
        BrainTree.Ctx ctx = ctx(s, c);
        BrainTree.Decision d = t.run(ctx);
        // Should not trigger night.shelter.nobed when sleep auto is off
        assertNotEquals("night.shelter.nobed", d.branch());
    }

    @Test
    void nightShelterNobedDoesNotTriggerWhenHasUndergroundJob() {
        BrainTree t = new BrainTree();
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state();
        s.armourTier = 1;
        s.weaponTier = 1;
        s.night = true;
        s.sleepAuto = true;
        s.othersSleeping = true;
        s.bedNear = false;
        s.dayTime = 14000;
        s.needs.add(new BrainState.NeedItem("minecraft:iron_ore", 64, 0, 0));
        BrainTree.Ctx ctx = ctx(s, c);
        BrainTree.Decision d = t.run(ctx);
        // Should not trigger night.shelter.nobed when there's an underground job
        assertNotEquals("night.shelter.nobed", d.branch());
    }

    @Test
    void nightShelterNobedDoesNotTriggerWhenBedNear() {
        BrainTree t = new BrainTree();
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state();
        s.armourTier = 1;
        s.weaponTier = 1;
        s.night = true;
        s.sleepAuto = true;
        s.othersSleeping = true;
        s.bedNear = true;  // bed is nearby
        s.dayTime = 14000;
        BrainTree.Ctx ctx = ctx(s, c);
        BrainTree.Decision d = t.run(ctx);
        // Should not trigger night.shelter.nobed when bed is nearby
        assertNotEquals("night.shelter.nobed", d.branch());
    }

    // ---- Interrupts.bagTrip tests ----

    @Test
    void bagTripDetectsDepositChain() {
        assertTrue(Interrupts.bagTrip("deposit"));
        assertTrue(Interrupts.bagTrip("deposit then smelt collect"));
    }

    @Test
    void bagTripDetectsChainWithDeposit() {
        assertTrue(Interrupts.bagTrip("go base then deposit then smelt collect"));
        assertTrue(Interrupts.bagTrip("mine strip then deposit"));
    }

    @Test
    void bagTripDoesNotDetectNonDepositChain() {
        assertFalse(Interrupts.bagTrip("mine strip"));
        assertFalse(Interrupts.bagTrip("cut 16 logs"));
        assertFalse(Interrupts.bagTrip("go camp then shelter"));
        assertFalse(Interrupts.bagTrip(null));
    }

    @Test
    void bagTripPreventsFullBagInterrupt() {
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state();
        s.freeSlots = 4;  // bag is full
        s.brainJob = "go base then deposit then smelt collect";
        String[] m = Interrupts.midJob(s, c);
        assertNull(m, "bagTrip should prevent full bag interrupt when chain already has deposit");
    }

    @Test
    void fullBagInterruptsWhenNoDepositInChain() {
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state();
        s.freeSlots = 4;  // bag is full
        s.brainJob = "mine strip";
        String[] m = Interrupts.midJob(s, c);
        assertNotNull(m);
        assertEquals("FULL", m[0]);
        assertEquals("go base then deposit then smelt collect", m[1]);
    }

    // ---- PlayRules.caveTrip tests ----

    @Test
    void caveTripDetectsMineCaveChains() {
        assertTrue(PlayRules.caveTrip("mine cave any 20 10m"));
        assertTrue(PlayRules.caveTrip("mine cave"));
        // Note: "mine cave status", "mine cave ores", "mine cave turn" are explicitly excluded
        // as they are commands to query/modify cave settings, not actual cave trips
    }

    @Test
    void caveTripDoesNotDetectNonCaveChains() {
        assertFalse(PlayRules.caveTrip("mine strip"));
        assertFalse(PlayRules.caveTrip("cut 16 logs"));
        assertFalse(PlayRules.caveTrip("farm"));
        assertFalse(PlayRules.caveTrip(null));
    }

    @Test
    void caveTripDoesNotDetectStatusOresTurnWords() {
        // These are cave status/ores/turn commands, not actual cave trips
        assertFalse(PlayRules.caveTrip("mine cave status"));
        assertFalse(PlayRules.caveTrip("mine cave ores"));
        assertFalse(PlayRules.caveTrip("mine cave turn"));
    }

    // ---- PlayRules.woodEnough tests ----

    @Test
    void woodEnoughWithDefaultNeed() {
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state();
        s.woodItems = 20;
        assertTrue(PlayRules.woodEnough(s), "20 wood items should be enough (default 16)");
        s.woodItems = 15;
        assertFalse(PlayRules.woodEnough(s), "15 wood items is not enough");
    }

    @Test
    void woodEnoughRespectsWoodNeed() {
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state();
        s.woodItems = 20;
        s.needs.add(new BrainState.NeedItem("minecraft:oak_log", 64, 30, 0));
        // woodEnough uses Math.min(want, n.want()) where want starts at 16
        // min(16, 30) = 16, and 20 >= 16 is true
        assertTrue(PlayRules.woodEnough(s), "need wants 30 but min(16,30)=16, have 20");
    }

    @Test
    void woodEnoughWithWoodNeedLowerThanDefault() {
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state();
        s.woodItems = 20;
        s.needs.add(new BrainState.NeedItem("minecraft:oak_log", 64, 10, 0));
        // Need wants 10, which is less than default 16, so should use 10
        assertTrue(PlayRules.woodEnough(s), "need wants 10, have 20");
    }

    @Test
    void woodEnoughWithWoodNeedHigherThanDefault() {
        BrainConfig c = BrainConfig.defaults();
        BrainState s = state();
        s.woodItems = 20;
        s.needs.add(new BrainState.NeedItem("minecraft:oak_log", 64, 50, 0));
        // woodEnough uses Math.min(want, n.want()) where want starts at 16
        // min(16, 50) = 16, and 20 >= 16 is true
        assertTrue(PlayRules.woodEnough(s), "need wants 50 but min(16,50)=16, have 20");
    }

    // ---- PlayRules.woodJob tests ----

    @Test
    void woodJobDetectsCutChains() {
        assertTrue(PlayRules.woodJob("cut 16 logs"));
        assertTrue(PlayRules.woodJob("cut oak_logs 16"));
    }

    @Test
    void woodJobDetectsGatherChains() {
        // woodJob checks if the second word ends with wood suffixes (_log, _planks, _wood, _stem)
        // "logs" doesn't end with "_log" (it ends with "s"), but "oak_log" does
        assertTrue(PlayRules.woodJob("gather oak_log"));
        assertTrue(PlayRules.woodJob("gather oak_planks"));
        assertTrue(PlayRules.woodJob("gather oak_wood"));
        assertTrue(PlayRules.woodJob("gather spruce_stem"));
        // "gather logs" fails because "logs" doesn't end with "_log"
        assertFalse(PlayRules.woodJob("gather logs"));
    }

    @Test
    void woodJobDetectsChopChains() {
        assertTrue(PlayRules.woodJob("chop 16 logs"));
    }

    @Test
    void woodJobDoesNotDetectNonWoodJobs() {
        assertFalse(PlayRules.woodJob("mine strip"));
        assertFalse(PlayRules.woodJob("mine cave any 20 10m"));
        assertFalse(PlayRules.woodJob("farm"));
        assertFalse(PlayRules.woodJob(null));
    }

    @Test
    void woodJobDoesNotDetectGatherNonWood() {
        assertFalse(PlayRules.woodJob("gather iron_ore"));
        assertFalse(PlayRules.woodJob("gather diamond_ore"));
    }
}
