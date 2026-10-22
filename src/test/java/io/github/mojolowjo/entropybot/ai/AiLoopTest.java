package io.github.mojolowjo.entropybot.ai;

import io.github.mojolowjo.entropybot.ai.provider.Provider;
import io.github.mojolowjo.entropybot.ai.provider.ProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The loop with a fake clock: no network, no game. */
class AiLoopTest {
    static final AiLoop.World IDLE = new AiLoop.World(true, false, false), BUSY = new AiLoop.World(true, true, false);

    @TempDir
    Path dir;
    AiSettings s;
    AiLoop loop;
    UsageLedger ledger;
    final List<String> notices = new ArrayList<>(), logs = new ArrayList<>();
    long now = 1_000_000_000_000L;

    @BeforeEach
    void setUp() {
        s = new AiSettings();
        s.enabled = true;
        make();
    }

    void make() {
        ledger = new UsageLedger(dir.resolve("ai-usage.json"), ZoneId.of("UTC"));
        CommandGate gate = new CommandGate(s.extraAllow, s.extraDeny);
        loop = new AiLoop(s, gate, new PromptBuilder(PromptBuilder.bundledRules(), gate, false, "Bot", "owner"), new Budget(s, ledger), new AiLoop.Sink() {
            public void notice(String t) { notices.add(t); }
            public void log(long n, String l) { logs.add(l); }
            public void trial(long n, String w, String c, String a) {}
        });
        loop.enable(now);
    }

    static Provider.Completion said(String t) { return new Provider.Completion(t, 1000, 5, null, "stop", 300); }

    Provider.Request poll(AiLoop.World w) { return loop.poll(now, w); }

    @Test
    void idleWithoutGoalCostsNothing() {
        for (int i = 0; i < 10; i++) {
            now += 120_000;
            assertNull(poll(IDLE));
        }
    }

    @Test
    void ownerRequestThinksOnceAndRunsTheCommand() {
        assertTrue(loop.ownerRequest("build a house", now));
        Provider.Request r = poll(IDLE);
        assertNotNull(r);
        assertTrue(r.messages().get(r.messages().size() - 1).content().contains("build a house"));
        assertNull(poll(IDLE), "one request in flight");
        loop.ownerRequest("and more", now);
        assertNull(poll(IDLE), "a burst is still one call");
        assertEquals("aa want bed", loop.onCompletion(said("aa want bed"), now));
        loop.onCommandResult("aa want bed", "started: house", true, now);
        now += 5000;
        assertNotNull(poll(BUSY), "the second request (sent while thinking) is read now");
        assertNull(loop.onCompletion(said("wait"), now));
        now += 5000;
        assertNull(poll(BUSY), "no think while the job runs");
        now += 120_000;
        assertNull(poll(BUSY), "no heartbeat while busy");
        loop.onJobEnd("ok: house built", now);
        assertNotNull(poll(IDLE), "the job end thinks");
    }

    @Test
    void offStaysOff() {
        loop.disable(now);
        assertFalse(loop.ownerRequest("x", now));
        assertNull(poll(IDLE));
    }

    @Test
    void instantReplyThinksAgainAfterTheGap() {
        loop.ownerRequest("look around", now);
        poll(IDLE);
        assertEquals("status", loop.onCompletion(said("status"), now));
        loop.onCommandResult("status", "RESULT status: at 1 64 2", false, now);
        assertNull(poll(IDLE), "min gap");
        now += s.minGapMs;
        assertNotNull(poll(IDLE));
    }

    @Test
    void heartbeatOnlyWithAGoal() {
        loop.ownerRequest("x", now);
        poll(IDLE);
        loop.onCompletion(said("wait"), now);
        now += 30_000;
        assertNull(poll(IDLE));
        now += 31_000;
        assertNotNull(poll(IDLE), "heartbeat after 60 s idle with a goal");
    }

    @Test
    void waitThreeTimesThenNudgeThenStop() {
        loop.ownerRequest("dig a hole", now);
        for (int i = 0; i < 3; i++) {
            Provider.Request r = poll(IDLE);
            assertNotNull(r, "turn " + i);
            loop.onCompletion(said("wait"), now);
            now += 61_000;
        }
        // the nudge was asked straight after the third wait
        assertEquals(AiLoop.State.IDLE, loop.state());
        Provider.Request nudge = poll(IDLE);
        assertNotNull(nudge);
        assertTrue(nudge.messages().get(nudge.messages().size() - 1).content().contains("not finished"));
        loop.onCompletion(said("wait"), now);
        assertEquals(AiLoop.State.STOPPED, loop.state());
        assertTrue(loop.stopReason().startsWith("model idle"));
        assertTrue(notices.stream().anyMatch(n -> n.contains("model idle")));
    }

    @Test
    void waitWhileBusyIsFine() {
        loop.ownerRequest("x", now);
        for (int i = 0; i < 6; i++) {
            loop.onNews("hostile near " + i, now);
            Provider.Request r = poll(BUSY);
            if (r != null) loop.onCompletion(said("wait"), now);
            now += 31_000;
        }
        assertNotEquals(AiLoop.State.STOPPED, loop.state());
    }

    @Test
    void fatalStatusStopsAtOnce() {
        loop.ownerRequest("x", now);
        poll(IDLE);
        loop.onFailure(new ProviderException(ProviderException.Kind.AUTH, 401, "the key was refused"), now);
        assertEquals(AiLoop.State.STOPPED, loop.state());
        assertEquals("401: the key was refused", loop.stopReason());
    }

    @Test
    void failureStreakBacksOffThenStops() {
        s.resumeAfterOutage = false;
        loop.ownerRequest("x", now);
        poll(IDLE);
        loop.onCompletion(said(""), now);                       // 1: empty
        assertNull(poll(IDLE));
        now += 5_000;
        assertNotNull(poll(IDLE), "after 5 s");
        loop.onFailure(new ProviderException(ProviderException.Kind.SERVER, 503, "the service failed"), now);
        now += 19_000;
        assertNull(poll(IDLE));
        now += 1_000;
        assertNotNull(poll(IDLE), "after 20 s");
        loop.onFailure(new ProviderException(ProviderException.Kind.NETWORK, 0, "down"), now);
        assertEquals(AiLoop.State.STOPPED, loop.state());
        assertTrue(loop.stopReason().startsWith("failures: "));
    }

    @Test
    void outageWaitsFiveMinutesWithAGoal() {
        loop.ownerRequest("x", now);
        for (int i = 0; i < 3; i++) {
            Provider.Request r = poll(IDLE);
            assertNotNull(r, "try " + i);
            loop.onFailure(new ProviderException(ProviderException.Kind.NETWORK, 0, "the AI service is not answering"), now);
            now += 21_000;
        }
        assertEquals(AiLoop.State.COOLDOWN, loop.state());
        assertNull(poll(IDLE));
        now += AiLoop.OUTAGE_RETRY_MS;
        assertNotNull(poll(IDLE), "retried after 5 min");
        assertEquals("aa status", loop.onCompletion(said("aa status"), now));
        assertEquals(0, loop.failureStreak(), "a success resets the streak");
    }

    @Test
    void successResetsTheStreak() {
        loop.ownerRequest("x", now);
        poll(IDLE);
        loop.onCompletion(said(""), now);
        now += 5_000;
        poll(IDLE);
        assertEquals("status", loop.onCompletion(said("status"), now));
        assertEquals(0, loop.failureStreak());
    }

    @Test
    void invalidCommandsAreFedBackThenStop() {
        loop.ownerRequest("x", now);
        poll(IDLE);
        assertNull(loop.onCompletion(said("restart"), now));
        assertTrue(notices.get(notices.size() - 1).contains("not allowed"));
        now += s.minGapMs;
        Provider.Request r = poll(IDLE);
        assertNotNull(r, "corrective retry");
        assertTrue(r.messages().get(r.messages().size() - 1).content().contains("not allowed for the AI operator: \"restart\""));
        loop.onCompletion(said("flyy"), now);
        now += s.minGapMs;
        poll(IDLE);
        loop.onCompletion(said("restart"), now);
        assertEquals(AiLoop.State.STOPPED, loop.state());
        assertEquals(1, notices.stream().filter(n -> n.startsWith("the model wanted")).count(), "the deny whisper once per verb");
    }

    @Test
    void sameCommandAndSameReplyInARowEndsTheRequest() {
        loop.ownerRequest("x", now);
        for (int i = 0; i < 2; i++) {
            now += s.minGapMs;
            poll(IDLE);
            assertEquals("aa want oak_log 3", loop.onCompletion(said("aa want oak_log 3"), now), "run " + i);
            loop.onCommandResult("aa want oak_log 3", "error: no trees", false, now);
        }
        now += s.minGapMs;
        poll(IDLE);
        assertNull(loop.onCompletion(said("aa want oak_log 3"), now), "the third one with an unchanged result is not run");
        assertNull(loop.goal(), "the mod ends the request");
        assertTrue(notices.get(notices.size() - 1).contains("nothing changed"));
    }

    @Test
    void aRepeatWithANewResultOrAReadOnlyVerbIsFine() {
        loop.ownerRequest("x", now);
        for (int i = 0; i < 4; i++) {
            now += s.minGapMs;
            poll(IDLE);
            assertEquals("aa want oak_log 3", loop.onCompletion(said("aa want oak_log 3"), now), "run " + i);
            loop.onCommandResult("aa want oak_log 3", "cut: " + i + " logs", false, now);
        }
        for (int i = 0; i < 2; i++) {
            now += s.minGapMs;
            poll(IDLE);
            assertEquals("aa status", loop.onCompletion(said("aa status"), now));
            loop.onCommandResult("aa status", "same bag", false, now);
        }
        assertNotNull(loop.goal());
    }

    @Test
    void sameLineThreeTimesInARequestEndsIt() {
        loop.ownerRequest("x", now);
        for (String c : List.of("aa want oak_log 3", "aa drop oak_log", "aa want oak_log 3", "aa drop oak_log", "aa want oak_log 3", "aa drop oak_log")) {
            now += s.minGapMs;
            poll(IDLE);
            String ran = loop.onCompletion(said(c), now);
            if (ran != null) loop.onCommandResult(c, "r " + c, false, now);
        }
        now += s.minGapMs;
        assertNotNull(poll(IDLE));
        assertNull(loop.onCompletion(said("aa want oak_log 3"), now));
        assertTrue(notices.get(notices.size() - 1).contains("3 times"), notices.toString());
    }

    @Test
    void sayIsRefusedWithAReason() {
        loop.ownerRequest("cut 3 logs", now);
        poll(IDLE);
        assertNull(loop.onCompletion(said("say I have 3 oak_log"), now));
        assertTrue(loop.lastError().contains("aa want"), "the refusal names what is allowed: " + loop.lastError());
        loop.ownerRequest("tell Steve hello", now);
        poll(IDLE);
        assertNull(loop.onCompletion(said("say hello Steve"), now), "say is gone for the AI (0.38.c1)");
    }
    @Test
    void thinksPerGoalCap() {
        s.maxThinksPerGoal = 2;
        loop.ownerRequest("x", now);
        for (String c : List.of("status", "inv")) {
            now += s.minGapMs;
            poll(IDLE);
            loop.onCompletion(said(c), now);
            loop.onCommandResult(c, "r", false, now);
        }
        now += s.minGapMs;
        assertNull(poll(IDLE));
        assertNull(loop.goal());
        assertTrue(notices.get(notices.size() - 1).contains("model calls"));
    }

    @Test
    void goalMetHintAndSummary() {
        loop.ownerRequest("cut 3 logs", now);
        assertNotNull(loop.poll(now, new AiLoop.World(true, false, false, java.util.Map.of("oak_log", 2))));
        loop.onCompletion(said("aa want oak_log 3"), now);
        loop.onCommandResult("aa want oak_log 3", "RESULT cut: 3 oak_log", false, now);
        now += s.minGapMs;
        Provider.Request r = loop.poll(now, new AiLoop.World(true, false, false, java.util.Map.of("oak_log", 5)));
        assertTrue(r.messages().get(r.messages().size() - 1).content().contains("the request looks done"));
        loop.onCompletion(said("finished"), now);
        String done = notices.get(notices.size() - 1);
        assertTrue(done.startsWith("done: cut 3 logs - ran aa want oak_log 3"), done);
    }

    @Test
    void ownerStopPausesUntilTheNextRequest() {
        loop.ownerRequest("x", now);
        poll(IDLE);
        loop.ownerStop(now);
        assertNull(loop.onCompletion(said("status"), now), "the answer in flight is dropped");
        now += 120_000;
        assertNull(poll(IDLE));
        assertEquals(AiLoop.State.PAUSED, loop.state());
        loop.ownerRequest("go on", now);
        assertNotNull(poll(IDLE));
    }

    @Test
    void confirmIsTheOwnersAndWaits() {
        loop.ownerRequest("clear it", now);
        poll(IDLE);
        assertEquals("aa want stone", loop.onCompletion(said("aa want stone"), now));
        loop.onCommandResult("aa want stone", "confirm? break 400 blocks - say \"confirm\" within 30 s (anything else cancels)", false, now);
        assertEquals(AiLoop.State.WAIT_CONFIRM, loop.state());
        assertTrue(notices.get(notices.size() - 1).contains("say confirm"));
        now += 10_000;
        assertNull(poll(IDLE));
        now += 60_000;
        assertNotNull(poll(IDLE), "after the window it thinks again");
    }

    @Test
    void tokenDayCapStopsAndPersists() {
        loop.ownerRequest("x", now);
        poll(IDLE);
        loop.onCompletion(said("status"), now);              // 1005 tokens
        s.tokensPerDay = 1100;
        loop.onCommandResult("status", "r", false, now);
        now += 2000;
        assertNull(poll(IDLE));
        assertEquals(AiLoop.State.STOPPED, loop.state());
        assertTrue(loop.stopReason().startsWith("cap: tokens/day"), loop.stopReason());
        // a restart of the game keeps the day's spending
        UsageLedger again = new UsageLedger(dir.resolve("ai-usage.json"), ZoneId.of("UTC"));
        assertEquals(1005, again.dayTokens(now));
        assertEquals(0, again.dayTokens(now + 86_400_000L), "a new day");
    }

    @Test
    void hourCapWaitsAndResumes() {
        s.requestsPerHour = 1;
        make();
        loop.ownerRequest("x", now);
        poll(IDLE);
        loop.onCompletion(said("status"), now);
        loop.onCommandResult("status", "r", false, now);
        now += 2000;
        assertNull(poll(IDLE));
        assertEquals(AiLoop.State.COOLDOWN, loop.state());
        now += 3_600_000L;
        assertNotNull(poll(IDLE), "the hour rolled");
    }

    @Test
    void usdCapForPaidModels() {
        s.paid = true;
        s.model = "openai/gpt-x";
        s.usdPerDay = 0.01;
        make();
        loop.ownerRequest("x", now);
        poll(IDLE);
        loop.onCompletion(new Provider.Completion("status", 100, 5, 0.02, "stop", 100), now);
        loop.onCommandResult("status", "r", false, now);
        now += 2000;
        assertNull(poll(IDLE));
        assertTrue(loop.stopReason().startsWith("cap: usd/day"), loop.stopReason());
    }

    @Test
    void notFreeModelRefusedWhenPaidIsOff() {
        s.model = "openai/gpt-x";
        make();
        assertEquals(AiLoop.State.STOPPED, loop.state());
        assertTrue(loop.stopReason().contains("not a free model"));
    }

    @Test
    void paidAnswerWhilePaidOffStops() {
        loop.ownerRequest("x", now);
        poll(IDLE);
        assertNull(loop.onCompletion(new Provider.Completion("status", 10, 1, 0.001, "stop", 10), now));
        assertTrue(loop.stopReason().contains("paid answer"));
    }

    @Test
    void watchdogCountsATimeout() {
        loop.ownerRequest("x", now);
        poll(IDLE);
        now += 90_000;
        assertFalse(loop.watchdog(now));
        now += 16_000;
        assertTrue(loop.watchdog(now));
        assertEquals(AiLoop.State.IDLE, loop.state());
        assertTrue(loop.lastError().contains("watchdog"));
    }

    @Test
    void finishedClearsTheGoal() {
        loop.ownerRequest("x", now);
        poll(IDLE);
        loop.onCompletion(said("finished"), now);
        assertNull(loop.goal());
        assertTrue(notices.get(notices.size() - 1).startsWith("done"));
        now += 600_000;
        assertNull(poll(IDLE), "quiet after the goal (autonomous off)");
    }

    @Test
    void autonomousPicksItsOwnGoal() {
        s.autonomous = true;
        make();
        assertNotNull(poll(IDLE));
        assertEquals(AiLoop.AUTO_GOAL, loop.goal());
    }

    @Test
    void deathIsNews() {
        loop.ownerRequest("x", now);
        poll(IDLE);
        loop.onCompletion(said("wait"), now);
        now += 1000;
        assertNull(loop.poll(now, new AiLoop.World(true, false, true)));
        now += 1000;
        Provider.Request r = poll(IDLE);
        assertNotNull(r);
        assertTrue(r.messages().get(r.messages().size() - 1).content().contains("died"));
    }

    @Test
    void statusHasNoSurprises() {
        loop.ownerRequest("x", now);
        String all = String.join("\n", loop.status(now));
        assertTrue(all.startsWith("AI operator: idle"), all);
        assertTrue(all.contains("goal: x"));
    }
}
