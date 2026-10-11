package io.github.mojolowjo.entropybot.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.camp.DayNight;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.26.1 "steady brain": R1-R9 and the cost counter, from the test tables in brain-pack (tests-gemini-pro.md,
 * tests-gemini-flash.md), deduped, with the settled ambiguities of DEVNOTES (R1 "next" = the next sibling, then the needs
 * by score; R4 limits brain-picked jobs only).
 */
class SteadyBrainTest {

    static final long T = 1_790_000_000_000L;
    static final long DUSK = DayNight.NIGHT_FROM;

    // ---- helpers ----

    static BrainState calm() {
        BrainState s = BrainTest.calm();
        s.now = T;
        return s;
    }

    /** No idle-list job at all (no mine): nothing underground to pick. */
    static BrainState bare() {
        BrainState s = new BrainState();
        s.now = T;
        return s;
    }

    static BrainState shelterBand(BrainState s) { s.armourTier = 0; s.weaponTier = 0; return s; }

    static BrainState underBand(BrainState s) { s.armourTier = 2; s.weaponTier = 2; return s; }

    static BrainState night(BrainState s) { s.night = true; s.dayTime = 18000; return s; }

    static BrainState day(BrainState s) { s.night = false; s.dayTime = 1000; return s; }

    static BrainTree.Ctx ctx(BrainState s, BrainConfig c, BrainTree.Running r, FailMemory m) {
        // bare(): no mine marked and no idle list, so nothing runs underground unless a test adds it
        Needs.Scored sc = Needs.score(s, c, s.upkeep.mine == null ? List.of() : IdleList.DEFAULT, Map.of(), r == null ? null : r.need());
        return new BrainTree.Ctx(s, c, sc, r, false, m, new ArrayList<>());
    }

    static BrainTree.Decision decide(BrainState s) { return decide(s, BrainConfig.defaults(), null, null); }

    static BrainTree.Decision decide(BrainState s, BrainConfig c) { return decide(s, c, null, null); }

    static BrainTree.Decision decide(BrainState s, BrainConfig c, BrainTree.Running r, FailMemory m) {
        return new BrainTree().run(ctx(s, c, r, m));
    }

    static BrainTree.Running running(String chain) { return new BrainTree.Running("upkeep", chain, 15); }

    static BrainConfig cfg(String key, int v) { return BrainConfig.defaults().set(key, v); }

    // =====================================================================================================
    @Nested
    class R1FailureMemory {

        @Test
        void classifiesEachPreconditionReason() {
            assertEquals(FailMemory.Reason.NO_ITEM, FailMemory.classify("stopped at step 1 (light here 8): no torches on me"));
            assertEquals(FailMemory.Reason.OUTSIDE, FailMemory.classify("error: that is outside my areas - area here 16 x"));
            assertEquals(FailMemory.Reason.NONE_IN_CHESTS, FailMemory.classify("none of my chests has iron_ingot"));
            assertEquals(FailMemory.Reason.NO_PATH, FailMemory.classify("couldn't get there (stopped at 1 2 3: no path)"));
            assertEquals(FailMemory.Reason.NO_PATH, FailMemory.classify("no path to the mine"));
            assertEquals(FailMemory.Reason.NO_BED, FailMemory.classify("no bed within 24"));
            assertEquals(FailMemory.Reason.GUARD_REFUSED, FailMemory.classify("would refuse: in base (safe)"));
        }

        @Test
        void theShelterMaterialShortfallIsNoItem() {
            assertEquals(FailMemory.Reason.NO_ITEM, FailMemory.classify("error: I need 9 planks, cobblestone or dirt for a shelter - next: craft planks 80 then shelter (or dig some dirt)"));
        }

        @Test
        void theShelterShortfallClearsWhenShelterBlocksRise() {
            BrainState s = calm();
            s.shelterBlocks = 0;
            FailMemory m = new FailMemory();
            m.record("shelter 5m", "error: I need 9 planks, cobblestone or dirt for a shelter", s, T, 10);
            assertNotNull(m.blocks("shelter 5m", s, T + 1));
            s.shelterBlocks = 16;
            assertNull(m.blocks("shelter 5m", s, T + 2));
        }

        @Test
        void sleepWithNoBedCarriedIsNoBed() {
            assertEquals(FailMemory.Reason.NO_BED, FailMemory.classify("error: no bed within 24 blocks and I carry none - next: give me a bed"));
            assertEquals(FailMemory.Reason.NO_BED, FailMemory.classify("error: no bed within 24 blocks - next: go base then spawn"));
        }

        @Test
        void lightWithNoTorchesIsNoItem() {
            assertEquals(FailMemory.Reason.NO_ITEM, FailMemory.classify("error: I have no torches - next: craft torch 16 (coal or charcoal + sticks), then light here 8"));
        }

        @Test
        void aMissingPickaxeOrItemIsNoItem() {
            assertEquals(FailMemory.Reason.NO_ITEM, FailMemory.classify("I have no pickaxe that can mine iron_ore - next: craft stone_pickaxe 1"));
            assertEquals(FailMemory.Reason.NO_ITEM, FailMemory.classify("error: I have no cobblestone - next: get cobblestone 64"));
            assertEquals(FailMemory.Reason.NO_ITEM, FailMemory.classify("error: I need inferium_essence to upgrade essence and there is none in my bag, my chests"));
            assertEquals(FailMemory.Reason.NO_ITEM, FailMemory.classify("error: no furnace recipe makes stick"));
        }

        @Test
        void anUnmarkedPlaceIsNoPlace() {
            assertEquals(FailMemory.Reason.NO_PLACE, FailMemory.classify("I have no place called camp - next: place camp"));
            assertEquals(FailMemory.Reason.NO_PLACE, FailMemory.classify("error: I have no area called yard (area list)"));
            assertEquals(FailMemory.Reason.NO_PLACE, FailMemory.classify("I have no place or marker called base (places lists them)"));
            assertEquals(FailMemory.Reason.NO_PLACE, FailMemory.classify("no places yet - place base where you want my base"));
        }

        @Test
        void noPlaceOnlyExpires() {
            BrainState s = calm();
            FailMemory m = new FailMemory();
            m.record("go camp then shelter", "I have no place called camp", s, T, 10);
            s.torches += 5;
            s.botPos = new int[]{50, 64, 50};
            assertNotNull(m.blocks("go camp then shelter", s, T + 9 * 60_000));
            assertNull(m.blocks("go camp then shelter", s, T + 10 * 60_000));
        }

        @Test
        void noTreesIsNoPathAndClearsByWalking() {
            assertEquals(FailMemory.Reason.NO_PATH, FailMemory.classify("no trees in my areas (12 log columns looked at; too far)"));
        }

        @Test
        void aFightOrTheOwnerIsNeverAPrecondition() {
            assertNull(FailMemory.classify("stopped at step 2: attacked by a zombie"));
            assertNull(FailMemory.classify("stopped: your stop"));
            assertNull(FailMemory.classify("interrupted:owner dig"));
            assertNull(FailMemory.classify("stopped: low health"));
            assertNull(FailMemory.classify("something odd happened"));
            assertNull(FailMemory.classify(null));
        }

        @Test
        void aRememberedLeafIsSkippedAndTheNextSiblingAnswers() {
            BrainState s = night(calm());
            s.litHere = false;
            s.torches = 5;
            assertEquals("night.light", decide(s).branch());
            FailMemory m = new FailMemory();
            m.record("light here 8", "no torches on me", s, T, 10);
            BrainTree.Ctx c = ctx(s, BrainConfig.defaults(), null, m);
            BrainTree.Decision d = new BrainTree().run(c);
            assertNotEquals("night.light", d.branch());
            assertEquals("pick", d.branch(), "the needs answer next");
            assertEquals(List.of("light here 8 failed NO_ITEM 0 min ago"), c.skipped());
        }

        @Test
        void theSkipTextCountsTheMinutes() {
            BrainState s = night(calm());
            s.litHere = false;
            FailMemory m = new FailMemory();
            m.record("light here 8", FailMemory.Reason.NO_ITEM, s, T - 9 * 60_000, 10);
            BrainTree.Ctx c = ctx(s, BrainConfig.defaults(), null, m);
            new BrainTree().run(c);
            assertEquals(List.of("light here 8 failed NO_ITEM 9 min ago"), c.skipped());
        }

        @Test
        void theRecordExpiresAfterFailMemoryMinutes() {
            BrainState s = night(calm());
            s.litHere = false;
            FailMemory m = new FailMemory();
            m.record("light here 8", FailMemory.Reason.NO_ITEM, s, T - 10 * 60_000, 10);
            assertEquals("night.light", decide(s, BrainConfig.defaults(), null, m).branch());
            assertEquals(0, m.size());
        }

        @Test
        void zeroMinutesRecordsNothing() {
            FailMemory m = new FailMemory();
            assertNull(m.record("light here 8", "no torches", calm(), T, 0));
            assertEquals(0, m.size());
        }

        @Test
        void theSettingIsRangedZeroTo120() {
            BrainConfig c = BrainConfig.defaults();
            assertEquals(10, c.i("brain.failMemoryMinutes"));
            assertNull(c.set("brain.failMemoryMinutes", "0"));
            assertNull(c.set("brain.failMemoryMinutes", "120"));
            assertNotNull(c.set("brain.failMemoryMinutes", "121"));
        }

        @Test
        void noItemClearsWhenAnItemCountRose() {
            BrainState s = calm();
            s.torches = 0;
            FailMemory m = new FailMemory();
            m.record("light here 8", "no torches", s, T, 10);
            assertNotNull(m.blocks("light here 8", s, T + 1000));
            s.torches = 1;
            assertNull(m.blocks("light here 8", s, T + 2000));
            assertEquals(0, m.size());
        }

        @Test
        void noItemStaysWhileNothingRose() {
            BrainState s = calm();
            s.torches = 4;
            FailMemory m = new FailMemory();
            m.record("craft torch 4 then light here 8", "missing 1 coal", s, T, 10);
            s.torches = 3;
            assertNotNull(m.blocks("craft torch 4 then light here 8", s, T + 1000));
        }

        @Test
        void outsideClearsInsideAnArea() {
            BrainState s = calm();
            s.areasHere = false;
            FailMemory m = new FailMemory();
            m.record("cut 16", "outside my areas", s, T, 10);
            assertNotNull(m.blocks("cut 16", s, T + 1));
            s.areasHere = true;
            assertNull(m.blocks("cut 16", s, T + 2));
        }

        @Test
        void noPathNeedsSixteenBlocksMoved() {
            BrainState s = calm();
            s.botPos = new int[]{0, 64, 0};
            FailMemory m = new FailMemory();
            m.record("go base", "couldn't get there", s, T, 10);
            s.botPos = new int[]{15, 64, 0};
            assertNotNull(m.blocks("go base", s, T + 1), "15 blocks: still remembered");
            s.botPos = new int[]{16, 64, 0};
            assertNull(m.blocks("go base", s, T + 2), "16 blocks, by any cause (water too)");
        }

        @Test
        void noBedClearsWithABedNearOrInTheBag() {
            BrainState s = calm();
            s.bedNear = false;
            FailMemory m = new FailMemory();
            m.record("sleep", "no bed within 24", s, T, 10);
            assertNotNull(m.blocks("sleep", s, T + 1));
            s.bedInBag = true;
            assertNull(m.blocks("sleep", s, T + 2));
        }

        @Test
        void noneInChestsClearsAfterAScanOrDeposit() {
            BrainState s = calm();
            FailMemory m = new FailMemory();
            m.record("get iron_ingot 16", "none of my chests has iron_ingot", s, T, 10);
            assertNotNull(m.blocks("get iron_ingot 16", s, T + 1));
            s.storageSeq++;
            assertNull(m.blocks("get iron_ingot 16", s, T + 2), "the stock view changed");
            m.record("get iron_ingot 16", "none of my chests has iron_ingot", s, T, 10);
            m.storageChanged();
            assertEquals(0, m.size(), "the brain's own deposit or scan finished");
        }

        @Test
        void guardRefusedOnlyExpires() {
            BrainState s = calm();
            FailMemory m = new FailMemory();
            m.record("dig yard", "would refuse: in base (safe)", s, T, 10);
            s.torches += 10;
            s.botPos = new int[]{100, 64, 100};
            s.storageSeq++;
            assertNotNull(m.blocks("dig yard", s, T + 9 * 60_000));
            assertNull(m.blocks("dig yard", s, T + 10 * 60_000));
        }

        @Test
        void twoRecordsAreBothSkipped() {
            BrainState s = shelterBand(night(bare()));
            s.litHere = false;
            s.torches = 3;
            FailMemory m = new FailMemory();
            m.record("shelter", FailMemory.Reason.NO_PATH, s, T, 10);
            m.record("light here 8", FailMemory.Reason.NO_ITEM, s, T, 10);
            BrainTree.Ctx c = ctx(s, BrainConfig.defaults(), null, m);
            BrainTree.Decision d = new BrainTree().run(c);
            assertFalse(d.chain() != null && (d.chain().equals("shelter") || d.chain().equals("light here 8")), d.toString());
            assertTrue(c.skipped().size() >= 2, c.skipped().toString());
        }

        @Test
        void pickSkipsARememberedNeedAndTakesTheNextByScore() {
            BrainState s = calm();
            s.needs.add(new BrainState.NeedItem("minecraft:torch", 32, 0, 0));
            BrainTree.Decision first = decide(s);
            assertEquals("gather torch 32", first.chain());
            FailMemory m = new FailMemory();
            m.record("gather torch 32", FailMemory.Reason.NO_PATH, s, T, 10);
            BrainTree.Ctx c = ctx(s, BrainConfig.defaults(), null, m);
            BrainTree.Decision d = new BrainTree().run(c);
            assertEquals("pick", d.branch());
            assertEquals("upkeep", d.need(), "the next need by score");
            assertTrue(c.skipped().get(0).startsWith("gather torch 32 failed NO_PATH"));
        }

        @Test
        void aSuccessfulChainIsNotRecorded() {
            BrainTest.Fake f = BrainTest.on();
            Brain b = new Brain(f);
            f.loop();
            b.tick();
            assertEquals(1, f.started.size());
            f.end("done: lit 3 spots");
            f.loop();
            b.tick();
            assertEquals(0, b.failMemory().size());
        }

        @Test
        void aFailedBrainJobIsRememberedSkippedLoggedAndWhisperedOnce() {
            BrainTest.Fake f = BrainTest.on();
            Brain b = new Brain(f);
            f.loop();
            b.tick();
            String chain = f.started.get(0);
            f.end("stopped at step 1 (" + chain + "): couldn't get there (stopped at 1 2 3: no path)");
            f.loop();
            b.tick();
            assertEquals(2, b.failMemory().size());      // 0.26.2: the chain and its failing step
            assertEquals(1, f.started.size(), "not started again");
            assertTrue(b.why().contains("skipped: " + chain.toLowerCase() + " failed NO_PATH 0 min ago"), b.why());
            assertTrue(f.whispers.stream().anyMatch(w -> w.startsWith("brain: skipping " + chain.toLowerCase())), f.whispers.toString());
            boolean logged = f.sink.lines().stream().anyMatch(o -> o.has("skipped"));
            assertTrue(logged, "the decision log carries skipped");
            int whispers = f.whispers.size();
            for (int i = 0; i < 5; i++) { f.loop(); b.tick(); }
            assertEquals(whispers, f.whispers.size(), "one whisper per kind an hour");
            JsonArray sk = f.written.getAsJsonArray("skipped");
            assertTrue(sk.size() > 0 && sk.get(0).getAsString().contains("failed NO_PATH"));
        }

        @Test
        void movingSixteenBlocksLetsTheBrainTryAgain() {
            BrainTest.Fake f = BrainTest.on();
            Brain b = new Brain(f);
            f.loop();
            b.tick();
            f.end("couldn't get there (no path)");
            f.loop();
            b.tick();
            assertEquals(1, f.started.size());
            f.state.botPos = new int[]{20, 64, 0};
            f.loop();
            b.tick();
            assertEquals(2, f.started.size(), "the cause changed");
        }

        @Test
        void theOwnersStopIsNotRecorded() {
            BrainTest.Fake f = BrainTest.on();
            Brain b = new Brain(f);
            f.loop();
            b.tick();
            b.hold();
            f.end("stopped: your stop - no path");
            f.loop();
            b.tick();
            assertEquals(0, b.failMemory().size());
        }

        @Test
        void aFightIsNotRecorded() {
            BrainTest.Fake f = BrainTest.on();
            Brain b = new Brain(f);
            f.loop();
            b.tick();
            f.end("stopped at step 1: attacked by a spider");
            f.loop();
            b.tick();
            assertEquals(0, b.failMemory().size());
        }

        @Test
        void memoryOffStartsTheSameChainAgain() {
            BrainTest.Fake f = BrainTest.on();
            f.files.put("brain-config.json", "{\"brain.failMemoryMinutes\":0}");
            Brain b = new Brain(f);
            f.loop();
            b.tick();
            f.end("couldn't get there (no path)");
            f.loop();
            b.tick();
            assertEquals(0, b.failMemory().size());
            assertEquals(2, f.started.size());
        }

        @Test
        void ownerOrderedChainsNeverReachTheMemory() {
            BrainTest.Fake f = BrainTest.on();
            f.state.ownerJob = "craft diamond_pickaxe 1";
            Brain b = new Brain(f);
            f.loop();
            b.tick();
            assertEquals("owner", b.lastDecision().branch());
            assertEquals(0, b.failMemory().size());
            assertTrue(f.started.isEmpty());
        }
    }

    // =====================================================================================================
    @Nested
    class R2NightFeasibility {

        @Test
        void lightsWithTorches() {
            BrainState s = night(bare());
            s.litHere = false;
            s.torches = 10;
            assertEquals("light here 8", decide(s).chain());
        }

        @Test
        void craftsTorchesFromCoal() {
            BrainState s = night(bare());
            s.litHere = false;
            s.torches = 0;
            s.coal = 1;
            BrainTree.Decision d = decide(s);
            assertEquals("night.light.craft", d.branch());
            assertEquals("craft torch 4 then light here 8", d.chain());
        }

        @Test
        void craftsTorchesFromLogs() {
            BrainState s = night(bare());
            s.litHere = false;
            s.torches = 0;
            s.logs = 1;
            assertEquals("craft torch 4 then light here 8", decide(s).chain());
        }

        @Test
        void nothingToLightWithInTheShelterBandShelters() {
            BrainState s = shelterBand(night(bare()));
            s.litHere = false;
            s.torches = 0;
            BrainTree.Decision d = decide(s);
            assertTrue(d.chain().endsWith("shelter"), d.toString());
        }

        @Test
        void aRememberedShelterIsSkippedAndNoSurfaceJobStartsAtNight() {
            BrainState s = shelterBand(night(bare()));
            s.litHere = false;
            s.torches = 0;
            FailMemory m = new FailMemory();
            m.record("shelter", FailMemory.Reason.NO_PATH, s, T, 10);
            BrainTree.Ctx c = ctx(s, BrainConfig.defaults(), null, m);
            BrainTree.Decision d = new BrainTree().run(c);
            // night.shelter and night.light.shelter skipped; the shelter band bars the surface torch gather at night: idle
            assertEquals(BrainTree.Kind.NOTHING, d.kind(), d.toString());
            assertEquals(2, c.skipped().size(), c.skipped().toString());
            assertTrue(c.skipped().get(0).startsWith("shelter failed NO_PATH"));
        }

        @Test
        void aRememberedTorchCraftFallsThroughToTheNeeds() {
            BrainState s = night(bare());
            s.litHere = false;
            s.torches = 0;
            s.coal = 2;
            FailMemory m = new FailMemory();
            m.record("craft torch 4 then light here 8", FailMemory.Reason.NO_PATH, s, T, 10);
            BrainTree.Decision d = decide(s, BrainConfig.defaults(), null, m);
            assertNotEquals("night.light.craft", d.branch());
        }

        @Test
        void nothingToLightWithInTheNormalBandScoresTheTorchNeed() {
            BrainState s = night(bare());
            s.litHere = false;
            s.torches = 0;
            BrainTree.Decision d = decide(s);
            assertEquals("pick", d.branch());
            assertEquals("gather torch 16", d.chain());
            assertEquals("torch", d.need());
        }

        @Test
        void neverLightsALitSpot() {
            BrainState s = night(bare());
            s.torches = 10;
            assertNotEquals("night.light", decide(s).branch());
        }

        @Test
        void sleepsWithABedNear() {
            BrainState s = night(bare());
            s.othersSleeping = true;
            s.bedNear = true;
            assertEquals("sleep", decide(s).chain());
        }

        @Test
        void sleepsWithABedInTheBag() {
            BrainState s = night(bare());
            s.othersSleeping = true;
            s.bedNear = false;
            s.bedInBag = true;
            assertEquals("night.sleep", decide(s).branch());
        }

        @Test
        void noBedFallsThroughToShelterNobedInTheUndergroundBand() {
            BrainState s = underBand(night(bare()));
            s.othersSleeping = true;
            s.bedNear = false;
            assertEquals("night.shelter.nobed", decide(s).branch());
        }

        @Test
        void neverSleepsByDay() {
            BrainState s = day(bare());
            s.othersSleeping = true;
            assertNotEquals("night.sleep", decide(s).branch());
        }

        @Test
        void duskWithNoTorchesNorBedAndOverAnHourLeftGathersTorches() {
            BrainState s = shelterBand(bare());
            s.dayTime = DUSK - 1001;
            s.torches = 0;
            s.bedNear = false;
            BrainTree.Decision d = decide(s, BrainConfig.defaults(), running("cut 64"), null);
            assertEquals("job.dusk", d.branch());
            assertEquals("gather torch 16", d.chain());
        }

        @Test
        void duskAtOneHourLeftShelters() {
            BrainState s = shelterBand(bare());
            s.dayTime = DUSK - 1000;
            s.torches = 0;
            s.bedNear = false;
            BrainTree.Decision d = decide(s, BrainConfig.defaults(), running("cut 64"), null);
            assertEquals("job.dusk", d.branch());
            assertTrue(d.chain().endsWith("shelter"), d.chain());
        }

        @Test
        void duskWithTorchesSheltersAsBefore() {
            BrainState s = shelterBand(bare());
            s.dayTime = DUSK - 1500;
            s.torches = 10;
            s.bedNear = false;
            assertTrue(decide(s, BrainConfig.defaults(), running("cut 64"), null).chain().endsWith("shelter"));
        }

        @Test
        void duskWithABedSheltersAsBefore() {
            BrainState s = shelterBand(bare());
            s.dayTime = DUSK - 1500;
            s.torches = 0;
            s.bedInBag = true;
            assertTrue(decide(s, BrainConfig.defaults(), running("cut 64"), null).chain().endsWith("shelter"));
        }

        @Test
        void duskUndergroundSheltersAsBefore() {
            BrainState s = shelterBand(bare());
            s.dayTime = DUSK - 1500;
            s.torches = 0;
            s.bedNear = false;
            s.skyAbove = false;
            assertTrue(decide(s, BrainConfig.defaults(), running("cut 64"), null).chain().endsWith("shelter"));
        }

        @Test
        void aRunningTorchGatherIsKeptUntilTheLastHour() {
            BrainState s = shelterBand(bare());
            s.dayTime = DUSK - 1500;
            s.torches = 0;
            s.bedNear = false;
            assertEquals("job.keep", decide(s, BrainConfig.defaults(), new BrainTree.Running("torch", "gather torch 16", 70), null).branch());
            s.dayTime = DUSK - 900;
            assertEquals("job.dusk", decide(s, BrainConfig.defaults(), new BrainTree.Running("torch", "gather torch 16", 70), null).branch());
        }
    }

    // =====================================================================================================
    @Nested
    class R3PreDeposit {
        final int[] base = {0, 64, 0};

        BrainState bag(int free) {
            BrainState s = calm();
            s.basePos = base;
            s.freeSlots = free;
            return s;
        }

        @Test
        void atTheSettingDepositsFirst() { assertEquals("deposit then cut 64", PlayRules.preDeposit("cut 64", bag(8), BrainConfig.defaults())); }

        @Test
        void belowTheSettingDepositsFirst() { assertEquals("deposit then cut 64", PlayRules.preDeposit("cut 64", bag(7), BrainConfig.defaults())); }

        @Test
        void aboveTheSettingRunsAsIs() { assertNull(PlayRules.preDeposit("cut 64", bag(9), BrainConfig.defaults())); }

        @Test
        void aFullBagDepositsFirst() { assertEquals("deposit then cut 64", PlayRules.preDeposit("cut 64", bag(0), BrainConfig.defaults())); }

        @Test
        void anEmptyBagRunsAsIs() { assertNull(PlayRules.preDeposit("cut 64", bag(36), BrainConfig.defaults())); }

        @Test
        void zeroIsOff() { assertNull(PlayRules.preDeposit("cut 64", bag(1), cfg("brain.preDepositSlots", 0))); }

        @Test
        void noBaseRunsAsIs() {
            BrainState s = bag(1);
            s.basePos = null;
            assertNull(PlayRules.preDeposit("cut 64", s, BrainConfig.defaults()));
        }

        @Test
        void everyGatheringJobIsCovered() {
            for (String j : List.of("mine strip any 32", "mine cave any 20 10m", "mine iron_ore 8", "cut 16", "chop 8", "gather iron_ingot 16", "farm", "dig yard", "dig 10 60 10 20 64 20 ores"))
                assertEquals("deposit then " + j, PlayRules.preDeposit(j, bag(5), BrainConfig.defaults()), j);
        }

        @Test
        void otherJobsAreLeftAlone() {
            for (String j : List.of("deposit", "restock", "explore 5", "go base", "craft stone_pickaxe 1", "eat", "light here 8", "shelter", "sleep", "come", "mine strip status", "go base then deposit then smelt collect"))
                assertNull(PlayRules.preDeposit(j, bag(1), BrainConfig.defaults()), j);
        }

        @Test
        void theSettingIsRanged() {
            BrainConfig c = BrainConfig.defaults();
            assertEquals(8, c.i("brain.preDepositSlots"));
            assertNull(c.set("brain.preDepositSlots", "36"));
            assertNotNull(c.set("brain.preDepositSlots", "37"));
        }

        @Test
        void theBrainsPickRunsDepositThenTheJob() {
            BrainTest.Fake f = BrainTest.on();
            f.files.put("brain-config.json", "{\"bagSome\":0}");
            f.state.freeSlots = 8;
            f.state.basePos = base;
            Brain b = new Brain(f);
            f.loop();
            b.tick();
            assertEquals("pick", b.lastDecision().branch());
            assertTrue(f.started.get(0).contains("deposit then mine strip"), f.started.toString());
            assertTrue(b.why().contains("deposit first"), b.why());
        }

        @Test
        void aNightLeafIsNotPrefixed() {
            BrainTest.Fake f = BrainTest.on();
            f.state.freeSlots = 8;
            f.state.basePos = base;
            f.state.night = true;
            f.state.litHere = false;
            f.shape = s -> { s.night = true; s.litHere = false; s.dayTime = 18000; };
            Brain b = new Brain(f);
            f.loop();
            b.tick();
            assertEquals("light here 8", f.started.get(0));
        }
    }

    // =====================================================================================================
    @Nested
    class R4Storm {

        BrainState storm(BrainState s) { s.thundering = true; s.skyAbove = true; s.dayTime = 6000; return s; }

        @Test
        void theShelterBandSheltersAWhile() {
            BrainTree.Decision d = decide(shelterBand(storm(bare())));
            assertEquals("weather.storm.shelter", d.branch());
            assertTrue(d.chain().endsWith("shelter 5m"), d.chain());
        }

        @Test
        void theUndergroundBandStartsOnlyAnUndergroundJob() {
            BrainState s = underBand(storm(calm()));
            s.needs.add(new BrainState.NeedItem("minecraft:oak_log", 64, 0, 0));      // a surface job outscores the mine
            BrainTree.Decision d = decide(s);
            assertEquals("weather.storm.under", d.branch());
            assertTrue(d.chain().startsWith("mine strip"), d.chain());
        }

        @Test
        void theUndergroundBandWithNoUndergroundJobStartsNoSurfaceJob() {
            BrainState s = underBand(storm(bare()));
            s.needs.add(new BrainState.NeedItem("minecraft:oak_log", 64, 0, 0));
            BrainTree.Decision d = decide(s);
            assertEquals(BrainTree.Kind.NOTHING, d.kind(), d.toString());
        }

        @Test
        void theNormalBandPicksAsUsual() {
            BrainState s = storm(calm());
            assertEquals("pick", decide(s).branch());
        }

        @Test
        void offPicksAsUsual() {
            BrainState s = shelterBand(storm(calm()));
            assertEquals("pick", decide(s, cfg("weather.storm", 0)).branch());
        }

        @Test
        void rainAloneDoesNothing() {
            BrainState s = shelterBand(storm(calm()));
            s.thundering = false;
            assertEquals("pick", decide(s).branch());
        }

        @Test
        void underARoofNothingChanges() {
            BrainState s = shelterBand(storm(calm()));
            s.skyAbove = false;
            assertEquals("pick", decide(s).branch());
        }

        @Test
        void aRunningSurfaceJobGivesWayInTheShelterBand() {
            BrainTree.Decision d = decide(shelterBand(storm(calm())), BrainConfig.defaults(), running("cut 64"), null);
            assertEquals("job.storm", d.branch());
            assertEquals(BrainTree.Kind.SWITCH, d.kind());
            assertTrue(d.chain().endsWith("shelter 5m"));
        }

        @Test
        void aRunningSurfaceJobGivesWayToAnUndergroundOneInTheUndergroundBand() {
            BrainTree.Decision d = decide(underBand(storm(calm())), BrainConfig.defaults(), new BrainTree.Running("need:oak_log", "cut 64", 60), null);
            assertEquals("job.storm", d.branch());
            assertTrue(d.chain().startsWith("mine strip"), d.chain());
        }

        @Test
        void aRunningUndergroundJobGoesOn() {
            assertEquals("job.keep", decide(underBand(storm(calm())), BrainConfig.defaults(), running("mine cave any 20 10m"), null).branch());
        }

        @Test
        void aRunningShelterGoesOn() {
            assertEquals("job.keep", decide(shelterBand(storm(calm())), BrainConfig.defaults(), new BrainTree.Running("storm", "shelter 5m", 65), null).branch());
        }

        @Test
        void theOwnersOrderRuns() {
            BrainState s = shelterBand(storm(calm()));
            s.ownerJob = "gather oak_log 64";
            assertEquals("owner", decide(s).branch());
        }

        @Test
        void theSettingTakesOnOff() {
            BrainConfig c = BrainConfig.defaults();
            assertTrue(c.on("weather.storm"));
            assertNull(c.set("weather.storm", "off"));
            assertFalse(c.on("weather.storm"));
            assertNull(c.set("weather.storm", "on"));
            assertNotNull(c.set("weather.storm", "2"));
        }

        @Test
        void theDecisionLogStateCarriesTheStorm() {
            BrainState s = storm(calm());
            assertTrue(s.summary().get("storm").getAsBoolean());
            assertFalse(calm().summary().has("storm"));
        }
    }

    // =====================================================================================================
    @Nested
    class R5OwnerOffline {

        BrainState escort(long offMs) {
            BrainState s = calm();
            s.passive = "escorting mojolowjo";
            s.passiveOwnerBound = true;
            s.ownerOnline = false;
            s.ownerOfflineMs = offMs;
            return s;
        }

        @Test
        void pastTheTimeoutGoesHome() {
            BrainTree.Decision d = decide(escort(301_000));
            assertEquals("passive.leave", d.branch());
            assertEquals("dismiss then home", d.chain());
            assertEquals(BrainTree.LEFT_WHISPER, d.reason());
        }

        @Test
        void atTheTimeoutGoesHome() { assertEquals("passive.leave", decide(escort(300_000)).branch()); }

        @Test
        void belowTheTimeoutWaitsTheGrace() {
            BrainTree.Decision d = decide(escort(299_000));
            assertEquals("passive.grace", d.branch());
            assertEquals(BrainTree.Kind.NOTHING, d.kind());
            assertTrue(d.reason().contains("home after 300 s"), d.reason());
        }

        @Test
        void zeroIsOff() { assertEquals("passive", decide(escort(3_600_000), cfg("passive.ownerTimeoutS", 0)).branch()); }

        @Test
        void theOwnerOnlineIsPassive() {
            BrainState s = escort(0);
            s.ownerOnline = true;
            assertEquals("passive", decide(s).branch());
        }

        @Test
        void assistIsOwnerBoundToo() {
            BrainState s = escort(301_000);
            s.passive = "assisting";
            assertEquals("passive.leave", decide(s).branch());
        }

        @Test
        void defendAndGuardAreUnaffected() {
            for (String p : List.of("defending here", "guarding base")) {
                BrainState s = escort(1_000_000);
                s.passive = p;
                s.passiveOwnerBound = false;
                assertEquals("passive", decide(s).branch(), p);
            }
        }

        @Test
        void theSettingsEdgesWork() {
            assertEquals("passive.leave", decide(escort(30_000), cfg("passive.ownerTimeoutS", 30)).branch());
            assertEquals("passive.grace", decide(escort(3_599_000), cfg("passive.ownerTimeoutS", 3600)).branch());
            assertEquals("passive.leave", decide(escort(3_600_000), cfg("passive.ownerTimeoutS", 3600)).branch());
            assertNotNull(BrainConfig.defaults().set("passive.ownerTimeoutS", "3601"));
        }

        @Test
        void theBrainWhispersExactlyAndDismisses() {
            BrainTest.Fake f = BrainTest.on();
            f.state.passive = "escorting mojolowjo";
            f.state.passiveOwnerBound = true;
            f.state.ownerOfflineMs = 400_000;
            Brain b = new Brain(f);
            f.loop();
            b.tick();
            assertEquals(List.of("dismiss then home"), f.started);
            assertEquals(List.of("you left - going home"), f.whispers);
        }

        @Test
        void dangerStillComesFirst() {
            BrainState s = escort(400_000);
            s.danger = true;
            assertEquals("danger", decide(s).branch());
        }
    }

    // =====================================================================================================
    @Nested
    class R6Spawn {

        BrainState ready() {
            BrainState s = day(calm());
            s.bedNear = true;
            s.spawnNearBase = false;
            s.spawnDue = true;
            return s;
        }

        @Test
        void byDayWithABedNearSetsTheSpawn() {
            BrainTree.Decision d = decide(ready());
            assertEquals("spawn", d.branch());
            assertEquals("spawn", d.chain());
        }

        @Test
        void notAtNight() {
            BrainState s = night(ready());
            assertNotEquals("spawn", decide(s).branch());
        }

        @Test
        void notWithoutABedNear() {
            BrainState s = ready();
            s.bedNear = false;
            s.bedInBag = true;
            assertNotEquals("spawn", decide(s).branch());
        }

        @Test
        void notWhenAlreadyNearTheBase() {
            BrainState s = ready();
            s.spawnNearBase = true;
            assertNotEquals("spawn", decide(s).branch());
        }

        @Test
        void notWithinTheCooldown() {
            BrainState s = ready();
            s.spawnDue = false;
            assertNotEquals("spawn", decide(s).branch());
        }

        @Test
        void notWithTheTimeUnknown() {
            BrainState s = ready();
            s.dayTime = -1;
            assertNotEquals("spawn", decide(s).branch());
        }

        @Test
        void aRememberedNoBedFailureSkipsIt() {
            BrainState s = ready();
            FailMemory m = new FailMemory();
            m.record("spawn", FailMemory.Reason.NO_BED, s, T, 10);      // failed at the bed near
            BrainTree.Ctx c = ctx(s, BrainConfig.defaults(), null, m);
            assertNotEquals("spawn", new BrainTree().run(c).branch());
            assertEquals(List.of("spawn failed NO_BED 0 min ago"), c.skipped());
        }

        @Test
        void walkingAwayAndBackToABedClearsNoBed() {
            BrainState s = ready();
            FailMemory m = new FailMemory();
            m.record("spawn", FailMemory.Reason.NO_BED, s, T, 10);
            s.bedNear = false;
            assertNotNull(m.blocks("spawn", s, T + 1000));
            s.bedNear = true;
            assertNull(m.blocks("spawn", s, T + 2000));
            assertEquals("spawn", decide(s, BrainConfig.defaults(), null, m).branch());
        }

        @Test
        void cooldownIsInGameMinutes() {
            assertEquals(1_200_000, Brain.gameMinutesMs(1440), "one in-game day = 20 real minutes");
            assertEquals(50_000, Brain.gameMinutesMs(60));
            BrainConfig c = BrainConfig.defaults();
            assertEquals(1440, c.i("spawn.cooldownMinutes"));
            assertNotNull(c.set("spawn.cooldownMinutes", "59"));
            assertNull(c.set("spawn.cooldownMinutes", "10080"));
            assertNotNull(c.set("spawn.cooldownMinutes", "10081"));
        }

        @Test
        void theCooldownCountsFromTheLastTry() {
            BrainTest.Fake f = BrainTest.on();
            Brain b = new Brain(f);
            b.data().addProperty("spawnTriedAt", f.now - 1_199_000);
            BrainState s = calm();
            b.spawnFacts(s, f.now);
            assertFalse(s.spawnDue, "1439 in-game minutes");
            b.data().addProperty("spawnTriedAt", f.now - 1_200_000);
            b.spawnFacts(s, f.now);
            assertTrue(s.spawnDue, "1440 in-game minutes");
        }

        BrainTest.Fake spawnWorld() {
            BrainTest.Fake f = BrainTest.on();
            f.shape = s -> { s.dayTime = 1000; s.bedNear = true; };
            return f;
        }

        @Test
        void successSetsSpawnNearBase() {
            BrainTest.Fake f = spawnWorld();
            Brain b = new Brain(f);
            f.loop();
            b.tick();
            assertEquals("spawn", f.started.get(0));
            f.end("done: Respawn point set");
            f.loop();
            b.tick();
            assertTrue(b.data().has("spawnAt"));
            assertTrue(f.started.size() == 1 || !f.started.get(1).equals("spawn"));
            BrainState s = calm();
            b.spawnFacts(s, f.now);
            assertTrue(s.spawnNearBase);
        }

        @Test
        void aSpawnFarFromTheBaseIsNotNearIt() {
            BrainTest.Fake f = spawnWorld();
            Brain b = new Brain(f);
            JsonArray a = new JsonArray();
            a.add(500); a.add(64); a.add(500);
            b.data().add("spawnAt", a);
            BrainState s = calm();
            s.basePos = new int[]{0, 64, 0};
            b.spawnFacts(s, f.now);
            assertFalse(s.spawnNearBase);
        }

        @Test
        void failureIsRememberedAsNoBed() {
            BrainTest.Fake f = spawnWorld();
            Brain b = new Brain(f);
            f.loop();
            b.tick();
            f.end("error: the bed is obstructed");
            f.loop();
            b.tick();
            assertTrue(b.failMemory().describe(f.now).contains("spawn NO_BED"), b.failMemory().describe(f.now));
        }

        @Test
        void failureWithMemoryOffIsNotRemembered() {
            BrainTest.Fake f = spawnWorld();
            f.files.put("brain-config.json", "{\"brain.failMemoryMinutes\":0}");
            Brain b = new Brain(f);
            f.loop();
            b.tick();
            f.end("error: the bed is obstructed");
            f.loop();
            b.tick();
            assertEquals(0, b.failMemory().size());
            assertEquals(1, f.started.stream().filter("spawn"::equals).count(), "the cooldown still holds it");
        }
    }

    // =====================================================================================================
    @Nested
    class R7FullInterruptRemoved {

        BrainConfig load(String json) {
            BrainConfig c = BrainConfig.defaults();
            assertEquals("", c.load(JsonParser.parseString(json).getAsJsonObject()), "fullInterrupt is not 'ignored'");
            return c;
        }

        @Test
        void aLargerValueMovesIn() {
            BrainConfig c = load("{\"fullInterrupt\":10}");
            assertEquals(10, c.i("brain.bagFullSlots"));
            assertTrue(c.migrated().contains("moved into brain.bagFullSlots"), c.migrated());
        }

        @Test
        void aSmallerValueIsDropped() {
            BrainConfig c = load("{\"fullInterrupt\":2}");
            assertEquals(4, c.i("brain.bagFullSlots"));
            assertTrue(c.migrated().contains("dropped"));
        }

        @Test
        void anEqualValueIsDropped() { assertEquals(4, load("{\"fullInterrupt\":4}").i("brain.bagFullSlots")); }

        @Test
        void zeroIsDropped() { assertEquals(4, load("{\"fullInterrupt\":0}").i("brain.bagFullSlots")); }

        @Test
        void fiveMovesIn() { assertEquals(5, load("{\"fullInterrupt\":5}").i("brain.bagFullSlots")); }

        @Test
        void noOldKeyNoNote() {
            BrainConfig c = load("{\"floor\":12}");
            assertEquals(4, c.i("brain.bagFullSlots"));
            assertEquals("", c.migrated());
        }

        @Test
        void aSavedNewValueWinsWhenLarger() {
            assertEquals(12, load("{\"fullInterrupt\":10,\"brain.bagFullSlots\":12}").i("brain.bagFullSlots"));
            assertEquals(10, load("{\"brain.bagFullSlots\":3,\"fullInterrupt\":10}").i("brain.bagFullSlots"));
        }

        @Test
        void anOutOfRangeOldValueIsCapped() { assertEquals(36, load("{\"fullInterrupt\":50}").i("brain.bagFullSlots")); }

        @Test
        void theOldKeyIsGone() {
            assertFalse(BrainConfig.known("fullInterrupt"));
            assertFalse(BrainConfig.defaults().all().containsKey("fullInterrupt"));
            assertEquals("renamed: brain.bagFullSlots", BrainConfig.defaults().set("fullInterrupt", "10"));
        }

        @Test
        void getAndSetAnswerRenamed() {
            BrainTest.Fake f = BrainTest.on();
            Brain b = new Brain(f);
            assertEquals("renamed: brain.bagFullSlots", b.command("get fullInterrupt"));
            assertEquals("renamed: brain.bagFullSlots", b.command("get fullinterrupt"));
            assertEquals("error: renamed: brain.bagFullSlots", b.command("set fullInterrupt 10"));
            assertTrue(b.command("get brain.bagFullSlots").startsWith("brain.bagFullSlots = 4"));
        }

        @Test
        void theBrainRewritesTheFileWithoutTheOldKey() {
            BrainTest.Fake f = BrainTest.on();
            f.files.put("brain-config.json", "{\"fullInterrupt\":10}");
            Brain b = new Brain(f);
            assertEquals(10, b.loadConfig().i("brain.bagFullSlots"));
            JsonObject saved = JsonParser.parseString(f.files.get("brain-config.json")).getAsJsonObject();
            assertFalse(saved.has("fullInterrupt"));
            assertEquals(10, saved.get("brain.bagFullSlots").getAsInt());
        }

        @Test
        void theMigratedValueDrivesTheBagInterrupt() {
            BrainConfig c = load("{\"fullInterrupt\":10}");
            BrainState s = calm();
            s.freeSlots = 10;
            assertTrue(PlayRules.bagFull(s, c));
            s.freeSlots = 11;
            assertFalse(PlayRules.bagFull(s, c));
            BrainTree.Decision d = decide(calmFull(10), c, running("mine strip any 32"), null);
            assertEquals("job.interrupt", d.branch());
        }

        BrainState calmFull(int free) {
            BrainState s = calm();
            s.freeSlots = free;
            return s;
        }

        @Test
        void noNodeReadsTheOldKey() {
            for (String[] row : new BrainTree().describe()) assertFalse(row[4].contains("fullInterrupt"));
            JsonObject o = BrainTreeFile.export(new BrainTree(), BrainConfig.defaults(), IdleList.DEFAULT, "built-in", "", 0, "0.26.1");
            assertFalse(o.toString().contains("fullInterrupt"));
        }
    }

    // =====================================================================================================
    @Nested
    class R8NightBeforeNeeds {

        BrainState n() { return night(calm()); }

        @Test
        void eatsAtHungryPlusTwo() {
            BrainState s = n();
            s.food = 8;
            s.foodItems = 10;
            BrainTree.Decision d = decide(s);
            assertEquals("night.eat", d.branch());
            assertEquals("eat", d.chain());
        }

        @Test
        void eatsBelowIt() {
            BrainState s = n();
            s.food = 6;
            assertEquals("night.eat", decide(s).branch());
        }

        @Test
        void notAboveIt() {
            BrainState s = n();
            s.food = 9;
            assertNotEquals("night.eat", decide(s).branch());
        }

        @Test
        void notWithoutFood() {
            BrainState s = n();
            s.food = 6;
            s.foodItems = 0;
            assertNotEquals("night.eat", decide(s).branch());
        }

        @Test
        void followsHungryInterrupt() {
            BrainState s = n();
            s.food = 7;
            assertNotEquals("night.eat", decide(s, cfg("hungryInterrupt", 4)).branch());
            s.food = 6;
            assertEquals("night.eat", decide(s, cfg("hungryInterrupt", 4)).branch());
        }

        @Test
        void notByDay() {
            BrainState s = day(calm());
            s.food = 8;
            assertNotEquals("night.eat", decide(s).branch());
        }

        @Test
        void eatsBeforeTheShelter() {
            BrainState s = shelterBand(n());
            s.food = 8;
            assertEquals("night.eat", decide(s).branch());
        }

        BrainState worn(String stage, int pct, boolean mats) {
            BrainState s = n();
            s.stage = stage;
            s.food = 10;
            s.pickaxes = 1;
            s.pickPct = pct;
            s.pickMaterials = mats;
            return s;
        }

        @Test
        void craftsAWornPickaxeWithMaterials() {
            BrainTree.Decision d = decide(worn("iron", 14, true));
            assertEquals("night.tool", d.branch());
            assertEquals("craft iron_pickaxe 1", d.chain());
        }

        @Test
        void craftsAtWornPlusFive() { assertEquals("night.tool", decide(worn("iron", 15, true)).branch()); }

        @Test
        void notAboveWornPlusFive() { assertNotEquals("night.tool", decide(worn("iron", 16, true)).branch()); }

        @Test
        void notWithoutMaterials() { assertNotEquals("night.tool", decide(worn("iron", 5, false)).branch()); }

        @Test
        void craftsTheStagesTier() { assertEquals("craft stone_pickaxe 1", decide(worn("stone", 10, true)).chain()); }

        @Test
        void noTierNoCraft() { assertNotEquals("night.tool", decide(worn("nothing", 5, true)).branch()); }

        @Test
        void eatingComesBeforeCrafting() {
            BrainState s = worn("iron", 5, true);
            s.food = 6;
            assertEquals("night.eat", decide(s).branch());
            s.food = 9;
            assertEquals("night.tool", decide(s).branch());
        }

        @Test
        void neitherLetsTheNightNodesRun() {
            BrainState s = shelterBand(n());
            assertEquals("night.shelter", decide(s).branch());
        }

        @Test
        void pickMaterialsCountsTheBag() {
            assertTrue(PlayRules.pickMaterials("iron", Map.of("iron_ingot", 3, "stick", 2)));
            assertTrue(PlayRules.pickMaterials("iron", Map.of("iron_ingot", 3, "oak_log", 1)));
            assertFalse(PlayRules.pickMaterials("iron", Map.of("iron_ingot", 2, "stick", 2)));
            assertFalse(PlayRules.pickMaterials("iron", Map.of("iron_ingot", 3)));
            assertTrue(PlayRules.pickMaterials("stone", Map.of("cobblestone", 3, "oak_planks", 2)));
            assertTrue(PlayRules.pickMaterials("stone", Map.of("cobbled_deepslate", 5, "stick", 4)));
            assertTrue(PlayRules.pickMaterials("wooden", Map.of("oak_log", 2)));
            assertFalse(PlayRules.pickMaterials(null, Map.of("iron_ingot", 9)));
        }
    }

    // =====================================================================================================
    @Nested
    class R9OwnerPositionAndCopy {

        @Test
        void thePlaceholderPositionIsNotKnown() {
            BrainState s = calm();
            assertFalse(s.ownerKnown());
            s.ownerPos = new int[]{10_000_000, 64, 10_000_000};
            assertFalse(s.ownerKnown());
            s.ownerPos = new int[]{100, 64, 0};
            assertTrue(s.ownerKnown());
        }

        @Test
        void idleNearNeverComesToAPlaceholder() {
            BrainState s = bare();
            s.ownerOnline = true;
            s.ownerPos = new int[]{10_000_000, 64, 10_000_000};
            assertNotEquals("idle.near", decide(s).branch());
            assertFalse(s.bound());
        }

        @Test
        void idleNearNeverComesToAnUnknownPosition() {
            BrainState s = bare();
            s.ownerOnline = true;
            s.ownerPos = null;
            assertNotEquals("idle.near", decide(s).branch());
        }

        @Test
        void idleNearComesToARealFarOwner() {
            BrainState s = bare();
            s.ownerOnline = true;
            s.ownerPos = new int[]{100, 64, 0};
            BrainTree.Decision d = decide(s);
            assertEquals("idle.near", d.branch());
            assertEquals("come", d.chain());
        }

        @Test
        void theLogNamesTheSentinels() {
            BrainState s = calm();
            assertEquals(-1, s.summary().get("ownerDist").getAsInt());
            s.ownerPos = new int[]{10_000_000, 64, 10_000_000};
            assertEquals(-2, s.summary().get("ownerDist").getAsInt());
            s.ownerPos = new int[]{30, 64, 40};
            assertEquals(50, s.summary().get("ownerDist").getAsInt());
        }

        CopyRules.Activity cut() { return new CopyRules.Activity(CopyRules.Kind.CHOP, "minecraft:oak_log", "cut 16", "you are chopping"); }

        @Test
        void copyAsksTheGuard() {
            BrainState s = bare();
            s.ownerOnline = true;
            s.ownerPos = new int[]{5, 64, 5};
            s.copy = cut();
            assertEquals("cut 16", decide(s).chain());
            s.copyRefused = "outside my areas";
            Needs.Scored sc = Needs.score(s, BrainConfig.defaults(), IdleList.DEFAULT, Map.of());
            assertTrue(sc.skipped().contains("copy cut 16 outside my areas"), sc.skipped().toString());
            assertNotEquals("cut 16", decide(s).chain());
        }

        @Test
        void whyShowsTheCopySkip() {
            BrainTest.Fake f = BrainTest.on();
            f.state.ownerOnline = true;
            f.state.ownerPos = new int[]{5, 64, 5};
            f.state.copy = cut();
            f.state.copyRefused = "outside my areas";
            Brain b = new Brain(f);
            f.loop();
            b.tick();
            assertTrue(b.why().contains("skipped: copy cut 16 outside my areas"), b.why());
            assertFalse(f.started.contains("cut 16"));
        }

        @Test
        void copyWithAnUnknownOwnerPositionIsSkipped() {
            BrainState s = bare();
            s.copy = cut();
            Needs.Scored sc = Needs.score(s, BrainConfig.defaults(), IdleList.DEFAULT, Map.of());
            assertEquals(0, sc.scoreOf("copy"));
            assertTrue(sc.skipped().get(0).startsWith("copy cut 16"));
        }
    }

    // =====================================================================================================
    @Nested
    class CostAndExport {

        @Test
        void statusAndBrainJsonCarryTheLoopCost() {
            BrainTest.Fake f = BrainTest.on();
            Brain b = new Brain(f);
            for (int i = 0; i < 3; i++) { f.loop(); b.tick(); }
            assertTrue(b.status().contains(" | cost "), b.status());
            assertTrue(b.status().contains("ms a loop (max "), b.status());
            for (String k : List.of("loopMsAvg", "loopMsMax", "senseMs")) assertTrue(f.written.has(k), k);
            assertTrue(f.written.get("loopMsMax").getAsDouble() >= f.written.get("loopMsAvg").getAsDouble());
        }

        @Test
        void theCostWindowIsOneMinute() {
            BrainTest.Fake f = BrainTest.on();
            Brain b = new Brain(f);
            b.cost(1000, 5);
            b.cost(30_000, 1);
            assertEquals(3, b.costMinute(30_000)[0], 0.001);
            assertEquals(5, b.costMinute(30_000)[1], 0.001);
            assertEquals(1, b.costMinute(70_000)[1], 0.001, "the first fell out");
            assertEquals(0, b.costMinute(200_000)[0], 0.001);
        }

        @Test
        void theNewNodesExportWithUidsAndKnownKeys() {
            JsonObject o = BrainTreeFile.export(new BrainTree(), BrainConfig.defaults(), IdleList.DEFAULT, "built-in", "", 0, "0.26.1");
            Map<String, JsonObject> byId = new java.util.HashMap<>();
            Set<String> uids = new HashSet<>();
            walk(o.getAsJsonObject("root"), byId, uids);
            for (String id : List.of("passive.leave", "passive.grace", "job.storm", "weather.storm", "weather.storm.shelter", "weather.storm.under",
                    "night.eat", "night.tool", "night.light.craft", "night.light.shelter", "spawn", "need.torch")) {
                assertTrue(byId.containsKey(id), id);
                assertTrue(byId.get(id).get("uid").getAsString().startsWith("r"), id);
            }
            assertTrue(keys(byId.get("passive.leave")).contains("passive.ownerTimeoutS"));
            assertTrue(keys(byId.get("weather.storm.shelter")).contains("weather.storm"));
            assertTrue(keys(byId.get("spawn")).contains("spawn.cooldownMinutes"));
            assertTrue(keys(byId.get("pick")).containsAll(List.of("brain.failMemoryMinutes", "brain.preDepositSlots")));
            assertEquals("r/7/1", byId.get("spawn").get("uid").getAsString());
            assertEquals("r/5", byId.get("weather.storm").get("uid").getAsString());
            for (String k : List.of("brain.failMemoryMinutes", "brain.preDepositSlots", "weather.storm", "passive.ownerTimeoutS", "spawn.cooldownMinutes"))
                assertTrue(o.getAsJsonObject("keys").has(k), k);
            BrainTreeFile.Result r = BrainTreeFile.parse(o.toString());
            assertTrue(r.ok(), r.errors().toString());
            assertEquals(new BrainTree().pathTo("night.tool"), r.tree().pathTo("night.tool"));
            assertEquals("need.torch", BrainTreeFile.needNode("torch"));
        }

        void walk(JsonObject n, Map<String, JsonObject> byId, Set<String> uids) {
            assertTrue(uids.add(n.get("uid").getAsString()));
            byId.put(n.get("id").getAsString(), n);      // the leaf (visited last) wins over its sequence
            for (JsonElement k : n.getAsJsonArray("keys")) assertTrue(BrainConfig.known(k.getAsString()) || k.getAsString().equals("idle"), k.getAsString());
            for (JsonElement c : n.getAsJsonArray("children")) walk(c.getAsJsonObject(), byId, uids);
        }

        List<String> keys(JsonObject n) {
            List<String> out = new ArrayList<>();
            n.getAsJsonArray("keys").forEach(k -> out.add(k.getAsString()));
            return out;
        }

        @Test
        void brainJsonPathsReachTheNewBranches() {
            BrainTree t = new BrainTree();
            assertEquals(List.of("r", "r/3", "r/3/0", "r/3/0/1"), t.pathTo("passive.leave"));
            assertEquals("r/5/0/1", t.pathTo("weather.storm.shelter").get(3));
            assertEquals(List.of("r", "r/7", "r/7/1"), t.pathTo("spawn"));
        }
    }
}
