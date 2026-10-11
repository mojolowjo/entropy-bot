package io.github.mojolowjo.entropybot.brain;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 0.26.5: learned scores (BRAIN_PLAN 4.5d). */
class OutcomeTableTest {
    static final OutcomeTable.Params P = OutcomeTable.Params.defaults();
    static final long T0 = 1_791_700_000_000L;
    static final long MIN = 60_000, DAY = 86_400_000L;
    static final String B = "stone|day|near";

    // ---- the key ----

    @Test void kindCutsNumbersAndDurations() { assertEquals("mine cave any", OutcomeTable.kind("mine cave any 32 20m")); }

    @Test void kindTakesTheFirstStep() { assertEquals("cut logs", OutcomeTable.kind("cut 8 logs then craft planks 32 then go camp then shelter")); }

    @Test void kindCutsNamespaces() { assertEquals("gather abyssal_stew", OutcomeTable.kind("gather arphex:abyssal_stew 1")); }

    @Test void kindSameForDifferentCounts() { assertEquals(OutcomeTable.kind("get food 4"), OutcomeTable.kind("get food 8")); }

    @Test void kindCutsCoordinates() { assertEquals("goto", OutcomeTable.kind("goto 10 87 -39")); }

    @Test void kindKeepsRoutineNames() { assertEquals("goal_bed", OutcomeTable.kind("goal_bed")); }

    @Test void kindAtMostThreeWords() { assertEquals("mine strip any", OutcomeTable.kind("mine strip any 32 north extra")); }

    @Test void kindLowerCase() { assertEquals("shelter", OutcomeTable.kind("Shelter 5m")); }

    @Test void kindNullForNothing() {
        assertNull(OutcomeTable.kind(null));
        assertNull(OutcomeTable.kind("  "));
    }

    // ---- buckets ----

    @Test void daypartNight() { assertEquals("night", OutcomeTable.daypart(true, 6000)); }

    @Test void daypartDusk() {
        assertEquals("dusk", OutcomeTable.daypart(false, 11000));
        assertEquals("dusk", OutcomeTable.daypart(false, 24000 + 12999));
    }

    @Test void daypartDay() {
        assertEquals("day", OutcomeTable.daypart(false, 10999));
        assertEquals("day", OutcomeTable.daypart(false, -1));
    }

    @Test void distBuckets() {
        assertEquals("near", OutcomeTable.dist(48));
        assertEquals("far", OutcomeTable.dist(49));
        assertEquals("unknown", OutcomeTable.dist(-1));
    }

    @Test void unknownStageIsQuestionMark() { assertEquals("?|day|near", OutcomeTable.bucket("netherite", "day", "near")); }

    @Test void bucketOfState() {
        BrainState s = new BrainState();
        s.stage = "iron";
        s.night = true;
        s.botPos = new int[]{100, 64, 0};
        s.basePos = new int[]{0, 64, 0};
        assertEquals("iron|night|far", OutcomeTable.bucket(s));
        s.basePos = null;
        assertEquals("iron|night|unknown", OutcomeTable.bucket(s));
    }

    @Test void bucketOfAnOldLogState() {
        JsonObject st = new JsonObject();
        st.addProperty("stage", "stone");
        st.addProperty("night", true);
        assertEquals("stone|night|unknown", OutcomeTable.bucket(st));
    }

    @Test void bucketOfANewLogState() {
        BrainState s = new BrainState();
        s.dayTime = 11500;
        s.basePos = new int[]{0, 64, 10};
        assertEquals("stone|dusk|near", OutcomeTable.bucket(s.summary()));
    }

    @Test void outcomeClasses() {
        assertEquals("ok", OutcomeTable.cls("finished"));
        assertEquals("failed", OutcomeTable.cls("failed:stopped at step 1"));
        assertEquals("interrupted", OutcomeTable.cls("interrupted:outscored by food"));
        assertEquals("interrupted", OutcomeTable.cls("overridden_by_owner:stop"));
        assertNull(OutcomeTable.cls("weird"));
    }

    // ---- the multiplier ----

    @Test void underMinIsOne() { assertEquals(1.0, OutcomeTable.multiplier(2, 2, P)); }

    @Test void atMinUsesThePrior() { assertEquals(0.6 + 0.7 * 4 / 5.0, OutcomeTable.multiplier(3, 3, P), 1e-9); }

    @Test void allFailedTowardsTheFloor() {
        assertEquals(0.6 + 0.7 / 5, OutcomeTable.multiplier(0, 3, P), 1e-9);
        assertEquals(0.6, OutcomeTable.multiplier(0, 10_000, P), 0.001);
    }

    @Test void allOkTowardsTheCeil() { assertEquals(1.3, OutcomeTable.multiplier(10_000, 10_000, P), 0.001); }

    @Test void halfOkIsTheMiddle() { assertEquals(0.95, OutcomeTable.multiplier(5, 10, P), 1e-9); }

    @Test void clampedToTheBounds() {
        OutcomeTable.Params p = new OutcomeTable.Params(true, 1, 0.6, 1.3, 7);
        double m = OutcomeTable.multiplier(1, 1, p);
        assertTrue(m >= 0.6 && m <= 1.3);
    }

    // ---- updates and decay ----

    static OutcomeTable with(String chain, String bucket, String... outcomes) {
        OutcomeTable t = new OutcomeTable();
        long at = T0;
        for (String o : outcomes) {
            t.decision(at, chain, bucket);
            assertTrue(t.outcome(at + 5 * MIN, at, o, 7));
            at += 10 * MIN;
        }
        return t;
    }

    @Test void countsAnOutcome() {
        OutcomeTable t = with("mine cave any 32 20m", B, "finished", "failed:x", "interrupted:y");
        JsonObject r = t.rows(null, T0 + 30 * MIN, P, 10).get(0);
        assertEquals("mine cave any", r.get("kind").getAsString());
        assertEquals(1.0, r.get("ok").getAsDouble(), 0.05);
        assertEquals(1.0, r.get("failed").getAsDouble(), 0.05);
        assertEquals(1.0, r.get("interrupted").getAsDouble(), 0.05);
        assertEquals(5.0, r.get("meanMin").getAsDouble(), 0.1);
    }

    @Test void interruptedDoesNotCountAsATry() {
        OutcomeTable t = with("cut 8 logs", B, "interrupted:a", "interrupted:b", "interrupted:c", "interrupted:d");
        assertEquals(1.0, t.adjust("cut 3 logs", B, T0 + DAY, P).m());
    }

    @Test void unknownRefIsSkipped() {
        OutcomeTable t = new OutcomeTable();
        assertFalse(t.outcome(T0, 42, "finished", 7));
        assertEquals(0, t.keys());
    }

    @Test void olderOutcomeIsSkipped() {
        OutcomeTable t = with("farm", B, "finished");
        t.decision(T0 - DAY, "farm", B);
        assertFalse(t.outcome(T0 - DAY + MIN, T0 - DAY, "finished", 7));
    }

    @Test void decayHalvesAfterTheHalfLife() {
        OutcomeTable t = with("farm", B, "finished", "finished", "finished", "finished");
        double now = t.rows(null, T0 + 40 * MIN, P, 1).get(0).get("ok").getAsDouble();
        double later = t.rows(null, T0 + 40 * MIN + 7 * DAY, P, 1).get(0).get("ok").getAsDouble();
        assertEquals(now / 2, later, 0.1);
    }

    @Test void decayPullsTheMultiplierBackToOne() {
        OutcomeTable t = with("farm", B, "failed:a", "failed:b", "failed:c", "failed:d");
        assertTrue(t.adjust("farm", B, T0 + DAY, P).m() < 0.8);
        assertEquals(1.0, t.adjust("farm", B, T0 + 60 * DAY, P).m());
    }

    @Test void fallsBackToAllContexts() {
        OutcomeTable t = with("farm", "stone|night|far", "failed:a", "failed:b", "failed:c");
        OutcomeTable.Adjust a = t.adjust("farm", B, T0 + 40 * MIN, P);
        assertTrue(a.fallback());
        assertTrue(a.m() < 1.0);
        assertTrue(a.note().contains("all contexts"), a.note());
    }

    @Test void offMeansOne() {
        OutcomeTable t = with("farm", B, "failed:a", "failed:b", "failed:c");
        assertEquals(1.0, t.adjust("farm", B, T0 + DAY, new OutcomeTable.Params(false, 3, 0.6, 1.3, 7)).m());
    }

    // ---- the why text ----

    @Test void whyNote() {
        OutcomeTable t = with("mine cave any 32 20m", "stone|night|far", "finished", "finished", "failed:a", "failed:b", "failed:c", "failed:d", "failed:e");
        OutcomeTable.Adjust a = t.adjust("mine cave any 32 20m", "stone|night|far", T0 + 70 * MIN, P);
        assertEquals("x" + OutcomeTable.fmt(Math.round(a.m() * 100) / 100.0) + " (learned: mine cave any 2/7 ok, night, far)", a.note());
    }

    @Test void noNoteAtOne() { assertEquals("", new OutcomeTable.Adjust("farm", B, 1.0, 0, 0, false).note()); }

    @Test void pickReasonCarriesTheNote() {
        BrainState s = new BrainState();
        s.learn = with("farm", OutcomeTable.bucket(s), "finished", "finished", "finished", "finished");
        s.now = T0 + DAY;
        assertTrue(s.learned("farm").note().startsWith("x1.1"), s.learned("farm").note());
    }

    // ---- the idle list and the pick ----

    @Test void idleListOrderedByTheMultiplier() {
        OutcomeTable t = with("mine strip any 32", B, "failed:a", "failed:b", "failed:c", "failed:d");
        List<IdleList.Pick> picks = List.of(new IdleList.Pick("strip", "mine strip any 32", "", null),
                new IdleList.Pick("cave", "mine cave any 32 20m", "", null));
        List<IdleList.Pick> out = t.order(picks, B, T0 + DAY, P);
        assertEquals("cave", out.get(0).item());
    }

    @Test void idleListKeepsItsOrderWithoutData() {
        OutcomeTable t = new OutcomeTable();
        List<IdleList.Pick> picks = List.of(new IdleList.Pick("strip", "mine strip any 32", "", null),
                new IdleList.Pick("cave", "mine cave any 32 20m", "", null));
        assertEquals("strip", t.order(picks, B, T0, P).get(0).item());
    }

    @Test void needsUseTheOrderedIdleList() {
        BrainState s = new BrainState();
        s.now = T0 + DAY;
        s.upkeep.now = s.now;
        s.upkeep.mine = new int[]{10, 40, 10};
        s.upkeep.mineReady = true;
        s.learn = with("mine strip any 32", OutcomeTable.bucket(s), "failed:a", "failed:b", "failed:c", "failed:d");
        Needs.Scored sc = Needs.score(s, BrainConfig.defaults(), List.of("strip", "cave"), Map.of(), null);
        Needs.Option up = null;
        for (Needs.Option o : sc.options()) if (o.need().equals("upkeep")) up = o;
        assertNotNull(up);
        assertTrue(up.chain().startsWith("mine cave"), up.chain());
    }

    @Test void pickOrderUsesScoreTimesM() {
        BrainState s = new BrainState();
        s.now = T0 + DAY;
        s.learn = with("cut 8 logs", OutcomeTable.bucket(s), "failed:a", "failed:b", "failed:c", "failed:d", "failed:e", "failed:f");
        List<Needs.Option> opts = List.of(new Needs.Option("wood", 40, "cut 8 logs", "", null, 0),
                new Needs.Option("food", 35, "get food 4", "", null, 0));
        assertEquals("food", BrainTree.learnedOrder(opts, s).get(0).need());
        s.learn = null;
        assertEquals("wood", BrainTree.learnedOrder(opts, s).get(0).need());
    }

    // ---- replay ----

    static final List<String> SAMPLE = List.of(
            "{\"t\":1000,\"tick\":1,\"branch\":\"pick\",\"scores\":{},\"chosen\":\"mine strip any 32\",\"reason\":\"r\",\"state\":{\"stage\":\"stone\",\"night\":false},\"outcome\":null}",
            "{\"t\":61000,\"ref\":1000,\"outcome\":\"failed:stopped at step 1\"}",
            "{\"t\":62000,\"tick\":2,\"branch\":\"pick\",\"scores\":{},\"chosen\":\"mine strip any 32\",\"reason\":\"r\",\"state\":{\"stage\":\"stone\",\"night\":false},\"outcome\":null}",
            "{\"t\":63000,\"ref\":62000,\"outcome\":\"failed:no pickaxe\"}",
            "{\"t\":64000,\"tick\":3,\"branch\":\"pick\",\"scores\":{},\"chosen\":\"gather torch 4\",\"reason\":\"r\",\"state\":{\"stage\":\"stone\",\"night\":true},\"outcome\":null}",
            "{\"t\":184000,\"ref\":64000,\"outcome\":\"finished\"}",
            "not json at all",
            "{\"t\":185000,\"branch\":\"autominer\",\"chosen\":\"farm\",\"reason\":\"old\",\"outcome\":\"finished\",\"history\":true}",
            "{\"t\":186000,\"tick\":4,\"branch\":\"night.shelter\",\"scores\":{},\"chosen\":\"shelter 5m\",\"reason\":\"r\",\"state\":{\"stage\":\"wood\",\"night\":true,\"daypart\":\"night\",\"baseDist\":100},\"outcome\":null}",
            "{\"t\":190000,\"ref\":186000,\"outcome\":\"interrupted:outscored by food\"}");

    @Test void replayBuildsTheExpectedTable() {
        OutcomeTable t = new OutcomeTable();
        assertEquals(4, t.replay(SAMPLE, 7));
        assertEquals(3, t.keys());
        Map<String, JsonObject> rows = new LinkedHashMap<>();
        for (JsonObject r : t.rows(null, 190000, P, 20)) rows.put(r.get("kind").getAsString() + "|" + r.get("bucket").getAsString(), r);
        assertEquals(2.0, rows.get("mine strip any|stone|day|unknown").get("failed").getAsDouble(), 0.01);
        assertEquals(1.0, rows.get("gather torch|stone|night|unknown").get("ok").getAsDouble(), 0.01);
        assertEquals(2.0, rows.get("gather torch|stone|night|unknown").get("meanMin").getAsDouble(), 0.01);
        assertEquals(1.0, rows.get("shelter|wood|night|far").get("interrupted").getAsDouble(), 0.01);
        assertEquals(190000, t.lastOutcomeT());
    }

    @Test void replaySkipsHistoryAndTornLines() {
        OutcomeTable t = new OutcomeTable();
        t.replay(SAMPLE, 7);
        for (JsonObject r : t.rows(null, 190000, P, 20)) assertNotEquals("farm", r.get("kind").getAsString());
    }

    static final class Sink implements DecisionLog.Sink {
        final Map<String, List<String>> files = new LinkedHashMap<>();
        @Override public void append(String day, String line) { files.computeIfAbsent(day, d -> new ArrayList<>()).add(line); }
        @Override public long size(String day) { return files.containsKey(day) ? files.get(day).size() * 100L : 0; }
        @Override public List<String> days() { return new ArrayList<>(files.keySet()); }
        @Override public void delete(String day) { files.remove(day); }
        @Override public List<String> read(String day) throws IOException {
            if (day.equals("2026-01-01")) throw new IOException("locked");
            return files.getOrDefault(day, List.of());
        }
    }

    @Test void recentLinesNewestKeptOldestFirst() {
        Sink s = new Sink();
        s.files.put("2026-10-10", new ArrayList<>(List.of("a", "b")));
        s.files.put("2026-10-11", new ArrayList<>(List.of("c", "d")));
        s.files.put("2026-09-01", new ArrayList<>(List.of("old")));
        DecisionLog log = new DecisionLog(s, ZoneId.of("UTC"));
        long now = java.time.LocalDate.parse("2026-10-11").atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli() + 1000;
        assertEquals(List.of("a", "b", "c", "d"), log.recentLines(now, 14, 100));
        assertEquals(List.of("b", "c", "d"), log.recentLines(now, 14, 3));
    }

    @Test void recentLinesSkipsAnUnreadableDay() {
        Sink s = new Sink();
        s.files.put("2026-01-01", new ArrayList<>(List.of("x")));
        s.files.put("2026-01-02", new ArrayList<>(List.of("y")));
        DecisionLog log = new DecisionLog(s, ZoneId.of("UTC"));
        long now = java.time.LocalDate.parse("2026-01-02").atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli();
        assertEquals(List.of("y"), log.recentLines(now, 14, 100));
        assertEquals(1, log.errors());
    }

    @Test void listenerSeesDecisionsAndOutcomes() {
        DecisionLog log = new DecisionLog(new Sink(), ZoneId.of("UTC"));
        OutcomeTable t = new OutcomeTable();
        log.listener(new DecisionLog.Listener() {
            @Override public void decision(long ref, String chosen, JsonObject state) { t.decision(ref, chosen, OutcomeTable.bucket(state)); }
            @Override public void outcome(long at, long ref, String outcome) { t.outcome(at, ref, outcome, 7); }
        });
        long ref = log.decision(T0, 1, "pick", Map.of(), "farm", "r", new BrainState().summary());
        log.outcome(T0 + MIN, ref, "finished");
        assertEquals(1, t.keys());
    }

    // ---- outcomes.json ----

    @Test void jsonRoundTrip() {
        OutcomeTable t = with("farm", B, "finished", "failed:a", "finished");
        OutcomeTable u = OutcomeTable.fromJson(t.toJson().toString());
        assertEquals(t.keys(), u.keys());
        assertEquals(t.adjust("farm", B, T0 + DAY, P).m(), u.adjust("farm", B, T0 + DAY, P).m(), 1e-9);
        assertEquals(t.lastOutcomeT(), u.lastOutcomeT());
    }

    @Test void brokenJsonThrows() {
        assertThrows(IllegalArgumentException.class, () -> OutcomeTable.fromJson("{not json"));
        assertThrows(IllegalArgumentException.class, () -> OutcomeTable.fromJson("{\"version\":1}"));
        assertThrows(IllegalArgumentException.class, () -> OutcomeTable.fromJson("{\"cells\":{\"farm\":[1,0,0,0,0,0]}}"));
        assertThrows(IllegalArgumentException.class, () -> OutcomeTable.fromJson("{\"cells\":{\"farm|stone|day|near\":[-1,0,0,0,0,0]}}"));
    }

    @Test void showListsAndFilters() {
        OutcomeTable t = with("farm", B, "finished", "finished", "finished");
        assertTrue(t.show(null, T0 + DAY, P).contains("farm [stone|day|near]"));
        assertTrue(t.show("cut logs", T0 + DAY, P).startsWith("learned: nothing for cut logs"));
    }

    @Test void resetEmpties() {
        OutcomeTable t = with("farm", B, "finished");
        t.reset();
        assertEquals(0, t.keys());
    }

    // ---- the brain ----

    static BrainTest.Fake brain(java.util.function.Consumer<BrainTest.Fake> before, Brain[] out) {
        BrainTest.Fake f = new BrainTest.Fake();
        before.accept(f);
        Brain b = new Brain(f);
        b.learnExecutor(Runnable::run);
        b.command("on");
        f.loop();
        b.tick();
        out[0] = b;
        return f;
    }

    @Test void brainRebuildsABrokenFileAndSaysSo() {
        Brain[] b = new Brain[1];
        BrainTest.Fake f = brain(x -> x.files.put(Brain.OUTCOMES_FILE, "{broken"), b);
        assertNotNull(b[0].learned());
        assertTrue(b[0].findings().stream().anyMatch(a -> a[0].equals("brainlearned") && a[1].contains("broken")));
        assertTrue(f.files.get(Brain.OUTCOMES_FILE).contains("\"cells\""), "rebuilt and saved");
    }

    @Test void brainLoadsTheFileAndCountsLiveOutcomes() {
        Brain[] b = new Brain[1];
        BrainTest.Fake f = brain(x -> { x.now = T0 + DAY; x.files.put(Brain.OUTCOMES_FILE, with("farm", B, "finished").toJson().toString()); }, b);
        assertEquals(1, b[0].learned().keys());
        assertTrue(b[0].status().contains("learned: 1 keys"), b[0].status());
        long ref = f.log.decision(f.now, 1, "pick", Map.of(), "cut 8 logs", "r", new BrainState().summary());
        f.log.outcome(f.now + MIN, ref, "finished");
        assertEquals(2, b[0].learned().keys());
        assertTrue(f.files.get(Brain.OUTCOMES_FILE).contains("cut logs"));
    }

    @Test void brainLearnedVerb() {
        Brain[] b = new Brain[1];
        brain(x -> x.files.put(Brain.OUTCOMES_FILE, with("farm", B, "finished").toJson().toString()), b);
        assertTrue(b[0].command("learned").contains("farm"));
        assertTrue(b[0].command("learned reset").startsWith("ok"));
        assertEquals(0, b[0].learned().keys());
    }
}
