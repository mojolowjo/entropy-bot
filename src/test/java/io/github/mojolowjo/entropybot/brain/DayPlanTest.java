package io.github.mojolowjo.entropybot.brain;

import io.github.mojolowjo.entropybot.camp.DayNight;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 0.26.3 the day plan, pure (docs/DAYPLAN_PLAN.md; rows from tests-d-gemini-pro.md D1/D2 and tests-gemini-flash.md D1/D2, the owner's decisions win). */
class DayPlanTest {

    static DayPlan.Facts facts(String stage) {
        DayPlan.Facts f = new DayPlan.Facts();
        f.stage = stage;
        f.stock.put("oak_log", 100);
        f.food = 100;
        f.campKnown = true;
        return f;
    }

    static List<String> texts(DayPlan.Facts f) { return DayPlan.candidates(f).stream().map(DayPlan.Candidate::text).toList(); }

    /** A selection where every candidate is plannable (chain "do <text>"). */
    static DayPlan.Selected selectAll(DayPlan p, DayPlan.Facts f, int max) {
        p.beginSelection(f, 0, "d1");
        for (String t : p.toPlan()) p.dryResult(t, "do " + t, null);
        return p.finishSelection(max, 1000, "d1");
    }

    static DayPlan.Tick tick(long dt, DayPlan.Facts f) { return new DayPlan.Tick(0, dt, false, null, true, false, 1, "1/3 cut 8 logs", null, f); }

    @Nested
    class CandidateTable {
        @Test void stageNothingStoneTools() { assertEquals(List.of("stone tools"), texts(facts("nothing"))); }
        @Test void stageStoneNoIronPickIronTools() { assertEquals(List.of("iron tools"), texts(facts("stone"))); }
        @Test void stageStoneWithIronPickNothing() {
            DayPlan.Facts f = facts("stone");
            f.stock.put("iron_pickaxe", 1);
            assertEquals(List.of(), texts(f));
        }
        @Test void diamondPickCountsAsIron() {
            DayPlan.Facts f = facts("stone");
            f.stock.put("diamond_pickaxe", 1);
            assertFalse(texts(f).contains("iron tools"));
        }
        @Test void iron31Ingots() {
            DayPlan.Facts f = facts("iron");
            f.stock.put("iron_ingot", 31);
            assertEquals(List.of("iron 32"), texts(f));
        }
        @Test void iron32IngotsNothing() {
            DayPlan.Facts f = facts("iron");
            f.stock.put("iron_ingot", 32);
            assertEquals(List.of(), texts(f));
        }
        @Test void baseNoFurnace() {
            DayPlan.Facts f = facts("diamond");
            f.baseKnown = true; f.tableNear = true; f.bedNear = true;
            assertEquals(List.of("furnace"), texts(f));
        }
        @Test void baseFurnaceNear() {
            DayPlan.Facts f = facts("diamond");
            f.baseKnown = true; f.tableNear = true; f.bedNear = true; f.furnaceNear = true;
            assertEquals(List.of(), texts(f));
        }
        @Test void noBaseNoFurnaceNoTableNoBed() { assertEquals(List.of(), texts(facts("diamond"))); }
        @Test void baseNoTable() {
            DayPlan.Facts f = facts("diamond");
            f.baseKnown = true; f.furnaceNear = true; f.bedNear = true;
            assertEquals(List.of("table"), texts(f));
        }
        @Test void baseNoBed() {
            DayPlan.Facts f = facts("diamond");
            f.baseKnown = true; f.furnaceNear = true; f.tableNear = true;
            assertEquals(List.of("bed"), texts(f));
        }
        @Test void wood63() {
            DayPlan.Facts f = facts("diamond");
            f.stock.clear();
            f.stock.put("oak_log", 30); f.stock.put("oak_planks", 33);
            assertEquals(List.of("wood 64"), texts(f));
        }
        @Test void wood64Nothing() {
            DayPlan.Facts f = facts("diamond");
            f.stock.clear();
            f.stock.put("birch_log", 32); f.stock.put("spruce_planks", 32);
            assertEquals(List.of(), texts(f));
        }
        @Test void stemsCountAsLogs() {
            DayPlan.Facts f = facts("diamond");
            f.stock.clear();
            f.stock.put("crimson_stem", 64);
            assertEquals(64, f.wood());
        }
        @Test void food31And32() {
            DayPlan.Facts f = facts("diamond");
            f.food = 31;
            assertEquals(List.of("food 32"), texts(f));
            f.food = 32;
            assertEquals(List.of(), texts(f));
        }
        @Test void darkMainArea() {
            DayPlan.Facts f = facts("diamond");
            f.darkMainArea = "base";
            assertEquals(List.of("light base"), texts(f));
        }
        @Test void campStoneNoCamp() {
            DayPlan.Facts f = facts("iron");
            f.stock.put("iron_ingot", 64);
            f.campKnown = false;
            assertEquals(List.of("camp"), texts(f));
        }
        @Test void campNotAtStageNothing() {
            DayPlan.Facts f = facts("nothing");
            f.campKnown = false;
            assertFalse(texts(f).contains("camp"));
        }
        @Test void everythingInPriorityOrder() {
            DayPlan.Facts f = new DayPlan.Facts();
            f.stage = "stone";
            f.baseKnown = true;
            f.darkMainArea = "base";
            List<DayPlan.Candidate> c = DayPlan.candidates(f);
            assertEquals(List.of("iron tools", "furnace", "table", "bed", "wood 64", "food 32", "light base", "camp"), c.stream().map(DayPlan.Candidate::text).toList());
            assertEquals(List.of(90, 80, 75, 70, 60, 50, 40, 20), c.stream().map(DayPlan.Candidate::priority).toList());
        }
        @Test void unknownFactsNoCandidates() {
            DayPlan.Facts f = facts("nothing");
            f.known = false;
            assertEquals(List.of(), texts(f));
        }
    }

    @Nested
    class Selection {
        DayPlan.Facts many() {
            DayPlan.Facts f = new DayPlan.Facts();
            f.stage = "nothing";
            f.baseKnown = true;
            return f;      // stone tools, furnace, table, bed, wood 64, food 32
        }

        @Test void maxGoalsOne() {
            DayPlan p = new DayPlan();
            selectAll(p, many(), 1);
            assertEquals(List.of("stone tools"), p.queueTexts());
        }
        @Test void maxGoalsThree() {
            DayPlan p = new DayPlan();
            DayPlan.Selected s = selectAll(p, many(), 3);
            assertEquals(List.of("stone tools", "furnace", "table"), p.queueTexts());
            assertEquals("today: stone tools, furnace, table", s.line());
        }
        @Test void maxGoalsFive() {
            DayPlan p = new DayPlan();
            selectAll(p, many(), 5);
            assertEquals(List.of("stone tools", "furnace", "table", "bed", "wood 64"), p.queueTexts());
        }
        @Test void firstGoalActivePending() {
            DayPlan p = new DayPlan();
            selectAll(p, many(), 3);
            assertEquals(DayPlan.State.PENDING, p.state());
            assertEquals("stone tools", p.active().text);
            assertEquals("do stone tools", p.active().chain);
        }
        @Test void unplannableDroppedNextBestPicked() {
            DayPlan p = new DayPlan();
            DayPlan.Facts f = facts("nothing");
            f.stock.clear();
            p.beginSelection(f, 0, "d1");
            assertEquals(List.of("stone tools", "wood 64"), p.toPlan());
            p.dryResult("stone tools", null, "no trees");
            p.dryResult("wood 64", "cut 64 logs", null);
            DayPlan.Selected s = p.finishSelection(3, 0, "d1");
            assertEquals(List.of("wood 64"), p.queueTexts());
            assertTrue(s.log().contains("day plan candidate stone tools dropped: unplannable (no trees)"), s.log().toString());
        }
        @Test void alreadyHaveItDropped() {
            DayPlan p = new DayPlan();
            p.beginSelection(facts("nothing"), 0, "d1");
            p.dryResult("stone tools", "", "I already have it");
            DayPlan.Selected s = p.finishSelection(3, 0, "d1");
            assertTrue(p.queueTexts().isEmpty());
            assertEquals(DayPlan.State.IDLE, p.state());
            assertTrue(s.log().get(0).contains("nothing to do"));
        }
        @Test void waitsUntilEveryDryRunAnswered() {
            DayPlan p = new DayPlan();
            p.beginSelection(many(), 0, "d1");
            assertEquals(DayPlan.State.SELECTING, p.state());
            assertFalse(p.selectionReady());
            for (String t : p.toPlan()) p.dryResult(t, "x", null);
            assertTrue(p.selectionReady());
        }
        @Test void unansweredDroppedOnTimeout() {
            DayPlan p = new DayPlan();
            p.beginSelection(many(), 0, "d1");
            p.dryResult("furnace", "craft furnace 1", null);
            DayPlan.Selected s = p.finishSelection(3, 60_000, "d1");
            assertEquals(List.of("furnace"), p.queueTexts());
            assertTrue(s.log().stream().anyMatch(l -> l.contains("stone tools dropped: the planner did not answer in time")));
        }
        @Test void noCandidatesIdle() {
            DayPlan p = new DayPlan();
            DayPlan.Selected s = selectAll(p, facts("diamond"), 3);
            assertEquals(DayPlan.State.IDLE, p.state());
            assertEquals("today: nothing to plan - idle list", s.line());
        }
        @Test void carryOverAtTheFront() {
            DayPlan p = new DayPlan();
            DayPlan.Facts f = facts("diamond");
            f.food = 10;
            selectAll(p, f, 3);
            assertEquals(List.of("food 32"), p.queueTexts());
            f.stage = "nothing";
            selectAll(p, f, 3);
            assertEquals(List.of("food 32", "stone tools"), p.queueTexts());
            assertTrue(p.goals().get(0).carried);
        }
        @Test void carriedQueueKeptInOrder() {
            DayPlan p = new DayPlan();
            DayPlan.Facts f = facts("iron");
            f.stock.clear();
            f.stock.put("iron_ingot", 0);
            selectAll(p, f, 2);
            assertEquals(List.of("iron 32", "wood 64"), p.queueTexts());
            selectAll(p, f, 3);
            assertEquals(List.of("iron 32", "wood 64"), p.queueTexts().subList(0, 2));
        }
        @Test void carriedNotDuplicated() {
            DayPlan p = new DayPlan();
            DayPlan.Facts f = facts("diamond");
            f.stock.clear();
            f.stock.put("oak_log", 10);
            selectAll(p, f, 3);
            selectAll(p, f, 3);
            assertEquals(List.of("wood 64"), p.queueTexts());
        }
        @Test void carriedDroppedWhenConditionGone() {
            DayPlan p = new DayPlan();
            DayPlan.Facts f = facts("diamond");
            f.food = 10;
            selectAll(p, f, 3);
            f.food = 50;
            p.beginSelection(f, 0, "d2");
            assertTrue(p.toPlan().isEmpty());
        }
        @Test void sameGoalKeepsLargerCount() {
            DayPlan p = new DayPlan();
            DayPlan.Goal g = new DayPlan.Goal("wood 32");
            g.state = DayPlan.GoalState.ACTIVE;
            p.goals.add(g);
            DayPlan.Facts f = facts("diamond");
            f.stock.clear();
            p.beginSelection(f, 0, "d2");
            assertEquals("wood 64", p.toPlan().get(0));      // the carried place, the larger count
            assertFalse(p.toPlan().contains("wood 32"));
        }
        @Test void maxGoalsCountsCarried() {
            DayPlan p = new DayPlan();
            DayPlan.Facts f = facts("diamond");
            f.food = 0;
            selectAll(p, f, 1);
            f.stage = "nothing";
            selectAll(p, f, 1);
            assertEquals(List.of("food 32"), p.queueTexts());
        }
    }

    @Nested
    class Rules {
        @Test void dawnCrossesIntoANewDay() {
            assertTrue(DayPlan.dawn(DayNight.key(18000), DayNight.key(24100)));      // n0 -> d1
            assertTrue(DayPlan.dawn(DayNight.key(5000), DayNight.key(23500)));       // /time set 23500 by day: d0 -> d1
        }
        @Test void noDawnOnFirstLookOrMidDay() {
            assertFalse(DayPlan.dawn(null, DayNight.key(100)));
            assertFalse(DayPlan.dawn(DayNight.key(1000), DayNight.key(6000)));
        }
        @Test void noDawnAtDusk() { assertFalse(DayPlan.dawn(DayNight.key(11999), DayNight.key(12600))); }
        @Test void sameGoalKey() {
            assertEquals(DayPlan.key("wood 64"), DayPlan.key("goal wood 32"));
            assertNotEquals(DayPlan.key("iron 32"), DayPlan.key("iron tools"));
            assertNotEquals(DayPlan.key("light base"), DayPlan.key("light yard"));
        }
        @Test void stallBoundary() {
            assertFalse(DayPlan.stalled(19 * 60_000L, 20));
            assertTrue(DayPlan.stalled(20 * 60_000L, 20));
            assertTrue(DayPlan.stalled(21 * 60_000L, 20));
            assertTrue(DayPlan.stalled(5 * 60_000L, 5));
            assertFalse(DayPlan.stalled(119 * 60_000L, 120));
            assertTrue(DayPlan.stalled(120 * 60_000L, 120));
        }
        @Test void targetFromStep() {
            assertArrayEquals(new int[]{10, 64, -20}, DayPlan.target("2/5 goto 10 64 -20"));
            assertNull(DayPlan.target("1/3 craft iron_pickaxe 1"));
        }
        @Test void darkEnough() {
            assertFalse(DayPlan.darkEnough(1, 3));
            assertTrue(DayPlan.darkEnough(1, 10));
            assertFalse(DayPlan.darkEnough(1, 11));
            assertFalse(DayPlan.darkEnough(0, 40));
        }
        @Test void holdsAndCounted() {
            DayPlan.Facts f = facts("iron");
            f.stock.put("iron_ingot", 40);
            assertFalse(DayPlan.holds("iron 32", f));
            assertTrue(DayPlan.holds("iron 64", f));
            assertEquals(40, DayPlan.counted("iron 64", f));
            assertEquals(-1, DayPlan.counted("bed", f));
            assertFalse(DayPlan.holds("stone tools", f));
        }
    }

    @Nested
    class Serving {
        DayPlan two() {
            DayPlan p = new DayPlan();
            DayPlan.Facts f = facts("diamond");
            f.stock.clear();
            f.food = 0;
            selectAll(p, f, 3);
            assertEquals(List.of("wood 64", "food 32"), p.queueTexts());
            return p;
        }

        DayPlan.Facts low() {
            DayPlan.Facts f = facts("diamond");
            f.stock.clear();
            f.food = 0;
            return f;
        }

        @Test void servingWhileItsJobRuns() {
            DayPlan p = two();
            p.update(tick(2000, low()), 20);
            assertEquals(DayPlan.State.SERVING, p.state());
        }
        @Test void interruptedByOtherJob() {
            DayPlan p = two();
            p.update(new DayPlan.Tick(0, 2000, false, null, false, true, -1, null, null, low()), 20);
            assertEquals(DayPlan.State.INTERRUPTED, p.state());
            assertEquals(0, p.active().noProgressMs);      // the stall clock waits
        }
        @Test void pendingWhenNothingRuns() {
            DayPlan p = two();
            p.update(new DayPlan.Tick(0, 2000, false, null, false, false, -1, null, null, low()), 20);
            assertEquals(DayPlan.State.PENDING, p.state());
            assertEquals(2000, p.active().noProgressMs);
        }
        @Test void stepFinishedIsProgress() {
            DayPlan p = two();
            p.update(tick(2000, low()), 20);
            p.active().noProgressMs = 19 * 60_000L;
            p.update(new DayPlan.Tick(0, 2000, false, null, true, false, 2, "2/3 craft planks 32", null, low()), 20);
            assertEquals(0, p.active().noProgressMs);
        }
        @Test void countRoseIsProgress() {
            DayPlan p = two();
            DayPlan.Facts f = low();
            p.update(tick(2000, f), 20);
            p.active().noProgressMs = 19 * 60_000L;
            f.stock.put("oak_log", 5);
            p.update(tick(2000, f), 20);
            assertEquals(0, p.active().noProgressMs);
        }
        @Test void moved32TowardTargetIsProgress() {
            DayPlan p = two();
            p.update(new DayPlan.Tick(0, 2000, false, null, true, false, 1, "1/2 goto 100 64 0", new int[]{0, 64, 0}, low()), 20);
            p.active().noProgressMs = 60_000;
            p.update(new DayPlan.Tick(0, 2000, false, null, true, false, 1, "1/2 goto 100 64 0", new int[]{31, 64, 0}, low()), 20);
            assertEquals(62_000, p.active().noProgressMs);
            p.update(new DayPlan.Tick(0, 2000, false, null, true, false, 1, "1/2 goto 100 64 0", new int[]{32, 64, 0}, low()), 20);
            assertEquals(0, p.active().noProgressMs);
        }
        @Test void nineteenMinutesNoAbandon() {
            DayPlan p = two();
            p.update(tick(2000, low()), 20);
            p.active().noProgressMs = 19 * 60_000L - 4000;
            DayPlan.Result r = p.update(tick(2000, low()), 20);
            assertTrue(r.whispers().isEmpty());
            assertEquals("wood 64", p.active().text);
        }
        @Test void twentyMinutesAbandonsAndAdvances() {
            DayPlan p = two();
            p.update(tick(2000, low()), 20);
            p.active().noProgressMs = 20 * 60_000L - 2000;
            DayPlan.Result r = p.update(tick(2000, low()), 20);
            assertEquals(List.of("gave up wood 64: no progress for 20 min"), r.whispers());
            assertEquals("food 32", p.active().text);
            assertEquals(List.of("food 32"), p.queueTexts());
            assertEquals(DayPlan.GoalState.FAILED, p.goals().get(0).state);
        }
        @Test void stallMinutesSetting() {
            DayPlan p = two();
            p.update(tick(2000, low()), 5);
            p.active().noProgressMs = 5 * 60_000L;
            assertEquals(List.of("gave up wood 64: no progress for 5 min"), p.update(tick(0, low()), 5).whispers());
        }
        @Test void lastAbandonEmptiesQueue() {
            DayPlan p = two();
            p.abandon("x");
            DayPlan.Result r = p.abandon("no source for iron_ore");
            assertEquals(List.of("gave up food 32: no source for iron_ore", DayPlan.QUEUE_EMPTY), r.whispers());
            assertEquals(DayPlan.State.IDLE, p.state());
        }
        @Test void doneWhenConditionMet() {
            DayPlan p = two();
            DayPlan.Facts f = low();
            f.stock.put("oak_log", 64);
            p.update(tick(2000, f), 20);
            assertEquals(DayPlan.GoalState.DONE, p.goals().get(0).state);
            assertEquals("food 32", p.active().text);
        }
        @Test void doneByChainAdvances() {
            DayPlan p = two();
            p.done("its chain finished");
            assertEquals("food 32", p.active().text);
            assertEquals(DayPlan.State.PENDING, p.state());
        }
        @Test void ownerGoalPausesQueueKept() {
            DayPlan p = two();
            p.update(new DayPlan.Tick(0, 2000, false, "furnace", false, true, -1, null, null, low()), 20);
            assertEquals(DayPlan.State.PAUSED_OWNER, p.state());
            assertEquals(List.of("wood 64", "food 32"), p.queueTexts());
            assertEquals(DayPlan.GoalState.PAUSED, p.active().state);
        }
        @Test void ownerGoalEndsResumes() {
            DayPlan p = two();
            p.update(new DayPlan.Tick(0, 2000, false, "furnace", false, true, -1, null, null, low()), 20);
            p.update(new DayPlan.Tick(0, 2000, false, null, false, false, -1, null, null, low()), 20);
            assertEquals(DayPlan.State.PENDING, p.state());
            assertEquals(DayPlan.GoalState.ACTIVE, p.active().state);
        }
        @Test void ownerGoalAlreadyQueuedBecomesActive() {
            DayPlan p = two();
            p.update(new DayPlan.Tick(0, 2000, false, "food 16", false, true, -1, null, null, low()), 20);
            assertEquals("food 32", p.active().text);
            assertEquals(List.of("food 32", "wood 64"), p.queueTexts());
        }
        @Test void ownerPauseDoesNotCountStall() {
            DayPlan p = two();
            for (int i = 0; i < 1000; i++) p.update(new DayPlan.Tick(0, 10_000, false, "bed", false, true, -1, null, null, low()), 20);
            assertEquals(0, p.active().noProgressMs);
        }
        @Test void nightPausesThenDawnCarries() {
            DayPlan p = two();
            p.update(new DayPlan.Tick(0, 2000, true, null, false, false, -1, null, null, low()), 20);
            assertEquals(DayPlan.State.PAUSED_NIGHT, p.state());
            DayPlan.Facts f = low();
            p.beginSelection(f, 0, "d2");
            assertEquals(List.of("wood 64", "food 32"), p.toPlan());
        }
        /** The owner's decision (carried goals keep their place at the front) wins over tests-d row D2-16. */
        @Test void dawnReselectionKeepsCarriedFirst() {
            DayPlan p = two();
            p.update(new DayPlan.Tick(0, 2000, true, null, false, false, -1, null, null, low()), 20);
            DayPlan.Facts f = low();
            f.stage = "nothing";
            selectAll(p, f, 3);
            assertEquals(List.of("wood 64", "food 32", "stone tools"), p.queueTexts());
        }
        @Test void clearEmpties() {
            DayPlan p = two();
            p.clear();
            assertTrue(p.goals().isEmpty());
            assertEquals(DayPlan.State.IDLE, p.state());
        }
        @Test void whyLine() {
            DayPlan p = new DayPlan();
            DayPlan.Facts f = new DayPlan.Facts();
            f.stage = "stone";
            f.baseKnown = true;
            f.furnaceNear = true; f.tableNear = true; f.stock.put("oak_log", 64); f.food = 64; f.campKnown = true;
            selectAll(p, f, 3);
            assertEquals(List.of("iron tools", "bed"), p.queueTexts());
            p.done("x");
            p.update(new DayPlan.Tick(0, 2000, false, null, true, false, 3, "3/7 craft iron_pickaxe 1", null, f), 20);
            assertEquals("day plan: bed (2/2), step 3/7 craft iron_pickaxe 1", p.whyLine());
        }
    }

    @Nested
    class Json {
        @Test void roundTrip() {
            DayPlan p = new DayPlan();
            DayPlan.Facts f = facts("diamond");
            f.food = 0;
            f.stock.clear();
            selectAll(p, f, 3);
            p.active().noProgressMs = 12345;
            DayPlan q = DayPlan.fromJson(p.toJson().toString());
            assertEquals(p.queueTexts(), q.queueTexts());
            assertEquals(p.state(), q.state());
            assertEquals("d1", q.dayKey());
            assertEquals(12345, q.active().noProgressMs);
            assertEquals("do wood 64", q.active().chain);
        }
        @Test void brokenFileThrows() {
            assertThrows(IllegalArgumentException.class, () -> DayPlan.fromJson("{\"goals\": 5"));
            assertThrows(IllegalArgumentException.class, () -> DayPlan.fromJson("[1,2]"));
            assertThrows(IllegalArgumentException.class, () -> DayPlan.fromJson("{\"state\":\"NAP\"}"));
        }
        @Test void emptyFileEmptyPlan() { assertEquals(DayPlan.State.IDLE, DayPlan.fromJson("").state()); }
        @Test void selectingReadsBackIdle() {
            DayPlan p = new DayPlan();
            p.beginSelection(facts("nothing"), 0, "d1");
            assertEquals(DayPlan.State.IDLE, DayPlan.fromJson(p.toJson().toString()).state());
        }
        @Test void cardStates() {
            DayPlan p = new DayPlan();
            DayPlan.Facts f = facts("diamond");
            f.food = 0;
            f.stock.clear();
            selectAll(p, f, 3);
            p.abandon("stalled");
            var j = p.brainJson();
            assertEquals("Failed", j.getAsJsonArray("goals").get(0).getAsJsonObject().get("state").getAsString());
            assertEquals("Active", j.getAsJsonArray("goals").get(1).getAsJsonObject().get("state").getAsString());
            assertEquals("food 32", j.get("active").getAsString());
            assertEquals("PENDING", j.get("state").getAsString());
            assertEquals(1000, j.get("madeAt").getAsLong());
        }
    }
}
