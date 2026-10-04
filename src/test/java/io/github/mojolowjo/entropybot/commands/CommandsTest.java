package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.commands.JobRequests.Request;
import io.github.mojolowjo.entropybot.commands.Chains.Reply;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** B7a: the PM texts, chat parsing, the job requests, chains, rules, the autominer, the death policy, the policy verbs. */
class CommandsTest {

    // ---- texts and chat ----

    @Test
    void chainsSplitAndFailuresAreSpotted() {
        assertEquals(List.of("deposit", "eat", "base"), Texts.splitChain("deposit then eat ; base"));
        assertTrue(Texts.stepFailed("error: busy"));
        assertTrue(Texts.stepFailed("ok: no path"), "\"ok: \" is dropped first");
        assertTrue(Texts.stepFailed("I have no routine called x"));
        assertFalse(Texts.stepFailed("started: waiting 2s"));
        assertFalse(Texts.stepFailed(null));
    }

    @Test
    void guestsGetTheReadOnlySet() {
        assertNull(Texts.guestRefusal("come", "", "come", "owner"));
        assertNull(Texts.guestRefusal("routine", "show night", "routine show night", "owner"));
        assertNull(Texts.guestRefusal("area", "list", "area list", "owner"));
        assertNotNull(Texts.guestRefusal("area", "add x here 5", "area add x here 5", "owner"));
        assertEquals("sorry, only owner can start chains", Texts.guestRefusal("come", "", "come then stop", "owner"));
        assertEquals("sorry, only owner can set the preferred ores", Texts.guestRefusal("ores", "prefer iron", "ores prefer iron", "owner"));
        assertTrue(Texts.guestRefusal("dig", "1 2 3 4 5 6", "dig 1 2 3 4 5 6", "owner").startsWith("sorry, only owner can use \"dig\""));
    }

    @Test
    void sayNeverSendsCommandsAndWhispersSplit() {
        assertNotNull(Texts.sayRefusal("/kill", "#"));
        assertNotNull(Texts.sayRefusal("#goto 0 0", "#"));
        assertNotNull(Texts.sayRefusal("!x", "!"));
        assertNull(Texts.sayRefusal("hi all", "#"));
        String longText = "word ".repeat(100);
        List<String> parts = Texts.whisperParts(longText);
        assertTrue(parts.size() >= 3);
        for (String p : parts) assertTrue(p.length() <= 200);
        assertEquals(longText.trim(), String.join(" ", parts));
    }

    @Test
    void whispersAreFoundInTheChatText() {
        ChatParse.Pm p = ChatParse.parse("mojolowjo whispers to you: come", "minecraft:system", null, null, "bot", "");
        assertEquals("mojolowjo", p.from());
        assertEquals("come", p.body());
        assertFalse(p.verified());
        assertNull(ChatParse.parse("bot whispers to you: hi", null, null, null, "bot", ""), "never the bot itself");
        assertNull(ChatParse.parse("<mojolowjo> come", "minecraft:chat", "mojolowjo", "come", "bot", ""), "public chat needs the prefix");
        assertEquals("come", ChatParse.parse("<mojolowjo> !come", "minecraft:chat", "mojolowjo", "!come", "bot", "!").body());
        assertNull(ChatParse.parse("someone whispers to you: x", null, "other", null, "bot", ""), "the verified sender must match the text");
        ChatParse.Pm v = ChatParse.parse("mojolowjo whispers to you: stop", "minecraft:msg_command_incoming", "mojolowjo", "stop", "bot", "");
        assertTrue(v.verified());
        assertEquals("[mojolowjo -> me] goto 1 2 3".replaceAll(".*\\] ", ""), ChatParse.parse("[mojolowjo -> me] goto 1 2 3", null, null, null, "bot", "").body());
    }

    // ---- the job requests (B7e: what is left of the bridge link) ----

    static final class Seen implements JobRequests.Listener {
        final List<String> log = new ArrayList<>();

        @Override public void finished(Request r) { log.add("done " + r.id + " " + r.doneMsg); }
    }

    @Test
    void aJobsRequestIsAnsweredAtOnceAndHeardAtItsEnd() {
        JobRequests q = new JobRequests();
        Seen s = new Seen();
        assertFalse(q.busy());
        Request r = q.local("pm", "o", "goto 1 2 3", "ok: going to 1 2 3", s);
        assertTrue(r.replied && r.started && r.open() && !r.finished, "a job's request starts answered");
        assertEquals("ok: going to 1 2 3", r.reply);
        assertTrue(q.busy());
        assertEquals(1, q.open());
        q.done(r.id, "ok: arrived");
        assertFalse(q.busy());
        assertTrue(r.finished && !r.open() && "ok: arrived".equals(r.doneMsg));
        q.done(r.id, "stopped: twice");
        assertEquals(List.of("done 1 ok: arrived"), s.log, "an end is heard once");
        q.done(42, "ok");
        assertEquals(1, s.log.size(), "an unknown id is ignored");
    }

    @Test
    void requestsCountUpAndAQuietEndIsSilent() {
        JobRequests q = new JobRequests();
        Request a = q.local("pm", "o", "goto 1 2 3", "ok", null);
        Request b = q.local("cmd", "o", "farm ", "started: farming", null);
        assertEquals(a.id + 1, b.id);
        assertEquals(2, q.open());
        q.done(a.id, JobRequests.REPLACED);                    // no listener: fine
        assertTrue(a.finished);
        assertTrue(JobRequests.quiet(JobRequests.REPLACED) && JobRequests.quiet(null) && !JobRequests.quiet("ok: arrived"));
        assertEquals(1, q.open());
    }

    @Test
    void unknownCmdTypesAndTheDebugLogCut() {
        assertEquals("error: unknown type eval", Texts.unknownType("eval"));
        String big = "x".repeat(5000);
        assertEquals("x".repeat(200) + "... (5000 chars)", Texts.cmdLogged("debug", "blocks 0 0 0 15 15 15", big));
        assertEquals("x".repeat(200) + "... (5000 chars)", Texts.cmdLogged("pm", "debug inv", big), "debug through pm too");
        assertEquals(big, Texts.cmdLogged("pm", "status", big), "other answers whole");
        assertEquals("short", Texts.cmdLogged("debug", "", "short"));
        assertNull(Texts.cmdLogged("debug", "", null));
    }

    // ---- chains, with a fake game ----

    /**
     * The command core played by the test: an instant verb answers now; any other starts a job whose request the
     * test ends ({@code link.done}). The next start's answer comes from {@code replies} (default "started: <text>");
     * an answer that fails ("error: ...") starts nothing.
     */
    static final class Fake implements Chains.Env {
        long tick, now = 1_000_000_000L;
        final List<String> whispers = new ArrayList<>(), ran = new ArrayList<>();
        final JobRequests link = new JobRequests();
        final java.util.ArrayDeque<String> replies = new java.util.ArrayDeque<>();
        /** Every job request made, in order; {@link #taken} of them have been looked at by {@link #takeNext}. */
        final List<Request> opened = new ArrayList<>();
        int taken;
        final Map<String, String> instant = new HashMap<>();
        float health = 20;
        boolean fighting, holding;
        int free = 20;
        JsonObject mine, supplies;
        Map<String, Integer> inv = new HashMap<>();
        Chains chains;

        @Override public long tick() { return tick; }
        @Override public long now() { return now; }
        @Override public ZoneId zone() { return ZoneId.of("UTC"); }
        @Override public String owner() { return "owner"; }
        @Override public void whisper(String to, String text) { whispers.add(text); }
        @Override public void log(String line) {}

        @Override
        public Reply dispatch(String from, String text, boolean internal, JobRequests.Listener l) {
            ran.add(text);
            String v = Texts.verbAndRest(text)[0];
            if (v.equals("stop")) {
                chains.clear();
                return Reply.now("ok: stopped everything");
            }
            if (instant.containsKey(v)) return Reply.now(instant.get(v));
            String reply = replies.isEmpty() ? "started: " + text : replies.poll();
            if (Texts.stepFailed(reply)) return Reply.now(reply);
            Request r = link.local("pm", from, text, reply, l);
            opened.add(r);
            return new Reply(reply, r);
        }

        /** The next job started since the last look (its request), or a failure when none. */
        Request takeNext() {
            assertTrue(taken < opened.size(), "a job was started");
            return opened.get(taken++);
        }

        @Override public boolean alive() { return true; }
        @Override public boolean holding() { return holding; }
        @Override public boolean fighting() { return fighting; }
        @Override public float health() { return health; }
        @Override public boolean busy() { return link.busy(); }
        @Override public int freeSlots() { return free; }
        @Override public int bagRoom() { return free; }
        @Override public Map<String, Integer> inventory() { return inv; }
        @Override public JsonObject supplies() { return supplies; }
        @Override public String orePrefer() { return null; }
        @Override public JsonObject minePlace() { return mine; }
        @Override public boolean inAreas(String dim, int x, int z) { return true; }
        @Override public String dim() { return "minecraft:overworld"; }
        @Override public void saved() {}
        int[] pos;                                            // (wave 1) where the bot stands; null = unknown
        @Override public int[] pos() { return pos; }
        int furnaceDue;                                       // (package D) how many step boundaries see a furnace due
        @Override public boolean furnaceDue() { return furnaceDue > 0 && furnaceDue-- > 0; }

        void run(int ticks) {
            for (int i = 0; i < ticks; i++) {
                tick++;
                if (tick % 5 == 3) chains.stepChain();
            }
        }
    }

    static Fake fake(JsonObject mem) {
        Fake f = new Fake();
        f.chains = new Chains(f, mem);
        f.instant.put("places", "base at 1 2 3");
        return f;
    }

    @Test
    void aChainRunsItsStepsAsLocalJobsAndReports() {
        JsonObject mem = new JsonObject();
        Fake f = fake(mem);
        assertEquals("started: chain - goto 1 2 3 > places > wait 2", f.chains.startChain("owner", "chain", "goto 1 2 3 then places then wait 2", 1));
        f.replies.add("ok: going to 1 2 3");
        f.replies.add("started: waiting 2s");
        f.run(5);
        assertEquals("goto 1 2 3", f.takeNext().text);
        f.run(10);
        assertTrue(mem.getAsJsonObject("run").get("idx").getAsInt() == 0, "the running step is what a restart runs again");
        assertTrue(f.busy(), "a job's request is open");
        f.link.done(1, "ok: arrived");
        f.run(10);
        assertEquals(List.of("goto 1 2 3", "places", "wait 2"), f.ran);
        assertEquals("wait 2", f.takeNext().text);
        f.link.done(2, "ok: waited");
        f.run(10);
        assertFalse(f.chains.running());
        assertFalse(f.busy());
        assertEquals("chain: done - waited", f.whispers.get(f.whispers.size() - 1));
        assertTrue(mem.get("run").isJsonNull());
    }

    @Test
    void aFailedStepEndsTheChainAndAFightIsRetried() {
        Fake f = fake(new JsonObject());
        f.chains.startChain("owner", "chain", "goto 1 2 3 then places", 1);
        f.replies.add("ok: going");
        f.run(5);
        f.link.done(f.takeNext().id, "stopped: attacked by zombie");
        f.run(5);
        assertTrue(f.chains.running(), "a fight is retried");
        f.fighting = true;
        f.run(400);
        assertEquals(1, f.ran.size(), "not while fighting");
        f.replies.add("error: no path to 1 2 3");
        f.fighting = false;
        f.run(10);
        assertEquals(2, f.ran.size(), "then the same step again");
        f.run(10);
        assertFalse(f.chains.running());
        assertEquals("chain: stopped at step 1 (goto 1 2 3): no path to 1 2 3", f.whispers.get(f.whispers.size() - 1));
    }

    @Test
    void aStepCutShortByTheDisconnectEndsTheChainWithTheReason() {
        // B7e: with the bridge gone a job ends only through its own finish (no "the bridge script reloaded" re-run)
        Fake f = fake(new JsonObject());
        f.chains.startChain("owner", "repeat", "farm", Chains.FOREVER);
        f.run(5);
        f.link.done(f.takeNext().id, "stopped: I was disconnected");
        f.run(5);
        assertFalse(f.chains.running());
        assertEquals("repeat: stopped at step 1 (farm): stopped: I was disconnected", f.whispers.get(f.whispers.size() - 1), f.whispers.toString());
        assertEquals(1, f.ran.size());
    }

    @Test
    void repeatRoundsAreAtLeastTenSecondsApart() {
        Fake f = fake(new JsonObject());
        f.chains.startChain("owner", "repeat", "places", 3);
        f.run(100);
        assertEquals(1, f.ran.size());
        f.run(150);
        assertEquals(2, f.ran.size());
        assertTrue(f.whispers.get(0).startsWith("repeat: round 1 done of 3"));
    }

    @Test
    void aRunIsResumedAfterARestartUnlessTooOld() {
        JsonObject mem = JsonParser.parseString("{\"run\":{\"name\":\"night\",\"text\":\"places then wait 5\",\"rounds\":\"forever\",\"round\":4,\"idx\":1,\"from\":\"owner\",\"savedAt\":999000000}}").getAsJsonObject();
        Fake f = fake(mem);
        f.chains.resumeRun();
        assertTrue(f.chains.running());
        assertEquals("carrying on with night (round 4, step 2: wait 5) after a restart", f.whispers.get(0));
        JsonObject old = JsonParser.parseString("{\"run\":{\"name\":\"old\",\"text\":\"places\",\"rounds\":1,\"round\":1,\"idx\":0,\"from\":\"owner\",\"savedAt\":1}}").getAsJsonObject();
        Fake g = fake(old);
        g.chains.resumeRun();
        assertFalse(g.chains.running());
        assertTrue(g.whispers.get(0).contains("too long ago"));
    }

    @Test
    void routinesExpandAndCannotTakeACommandName() {
        Fake f = fake(new JsonObject());
        assertTrue(f.chains.routineCommand("save night places then wait 5").startsWith("saved routine night: places > wait 5"));
        assertTrue(f.chains.routineCommand("save stop places").startsWith("error: \"stop\" is already a command"));
        assertEquals("started: x - places > wait 5 > places", f.chains.startChain("owner", "x", "night then places", 1));
        assertEquals("routines: night (routine show <name>)", f.chains.routineCommand(""));
    }

    @Test
    void deathsSetTheChainAsideFetchTheCorpseAndPark() {
        JsonObject mem = new JsonObject();
        Fake f = fake(mem);
        mem.add("lastDeath", JsonParser.parseString("{\"x\":1,\"y\":60,\"z\":1,\"dim\":\"minecraft:overworld\"}"));
        f.chains.startChain("owner", "repeat", "farm", Chains.FOREVER);
        f.run(5);
        f.takeNext();
        f.chains.noteDeath();
        assertFalse(f.chains.running());
        f.link.done(1, "stopped: the bot died");
        f.chains.deathTick(false);
        f.tick += 100;
        f.replies.add("started: going back");
        f.chains.deathTick(false);
        assertEquals("death", f.ran.get(f.ran.size() - 1), "it goes for the corpse");
        assertEquals("death", f.takeNext().text);
        f.link.done(2, "ok: got my things back");
        f.chains.deathTick(false);
        assertTrue(f.chains.running());
        assertTrue(f.whispers.contains("back from my corpse - carrying on with repeat"));
        f.run(5);
        assertEquals("farm", f.ran.get(f.ran.size() - 1), "the step the death cut short runs again");
        for (int i = 0; i < 4; i++) f.chains.noteDeath();
        assertTrue(f.chains.parked());
        assertTrue(f.chains.deathsCommand("").contains("PARKED"));
        assertTrue(f.chains.resumeCommand().startsWith("ok: back to work"));
    }

    @Test
    void rulesFireWhenIdleAndDue() {
        JsonObject mem = new JsonObject();
        Fake f = fake(mem);
        assertEquals("ok: rule #1: every 30m do places > wait 1", f.chains.ruleCommand("every 30m do places then wait 1"));
        assertEquals("ok: rule #2: at 06:30 do places", f.chains.ruleCommand("at 6:30 do places"));
        f.chains.rulesTick();
        assertFalse(f.chains.running(), "not due yet");
        f.now += 31 * 60000;
        f.chains.rulesTick();
        assertEquals("rule #1", f.chains.name());
        assertTrue(f.chains.ruleCommand("list").startsWith("#1 every 30m do places then wait 1 | #2 at 06:30"));
        assertTrue(f.chains.ruleCommand("delete 2").startsWith("deleted rule: at 06:30"));
        assertTrue(f.chains.ruleCommand("sometimes do x").startsWith("usage:"));
    }

    @Test
    void theAutominerDecidesAndSaysWhy() {
        JsonObject mem = new JsonObject();
        Fake f = fake(mem);
        assertTrue(f.chains.autominerCommand("on").startsWith("ok: autominer on"));
        f.free = 2;
        f.tick = 1000;
        f.chains.autominerTick();
        assertEquals("autominer", f.chains.name());
        assertTrue(f.chains.whyCommand().contains("deposit because my bag is nearly full (2 free slots)"));
        f.chains.clear();
        f.free = 20;
        f.mine = JsonParser.parseString("{\"x\":5,\"y\":-54,\"z\":5,\"dir\":\"west\"}").getAsJsonObject();
        f.tick += 300;
        f.chains.autominerTick();
        assertTrue(f.chains.whyCommand().contains("mine strip any 32 because my mine at 5 -54 5 is ready"), f.chains.whyCommand());
    }

    // ---- the policy verbs ----

    static final class FakeGuard implements PolicyCommands.Guard {
        JsonObject last;
        boolean strict;

        @Override public String[] apply(JsonObject policy, boolean s) {
            last = policy;
            strict = s;
            return new String[]{"ok: " + policy.getAsJsonArray("areas").size() + " areas", "ok: guard mode " + (s ? "strict" : "log")};
        }

        @Override public JsonObject status() { return null; }
        @Override public String vetoes(int max) { return "[]"; }
        @Override public String check(String dim, int x, int y, int z, String action) { return "ok"; }
    }

    @Test
    void areasAndProtectBoxesChangeThePolicy() {
        JsonObject p = new JsonObject();
        FakeGuard g = new FakeGuard();
        PolicyCommands pc = new PolicyCommands(p, g, () -> {});
        PolicyCommands.Pos here = new PolicyCommands.Pos(10, 64, 20, "minecraft:overworld");
        assertEquals("no areas set - area add <name> here <r>", pc.command("area", "list", true, "owner", here, here));
        assertEquals("ok: area home added (-20 -10 to 40 50) | mod: ok: 1 areas", pc.command("area", "add home here 30", true, "owner", here, here));
        assertTrue(pc.inAreas("minecraft:overworld", 0, 0));
        assertFalse(pc.inAreas("minecraft:overworld", 100, 0));
        assertEquals("only owner can change where I may go and dig", pc.command("area", "add x here 3", false, "owner", here, here));
        assertTrue(pc.command("area", "show home", false, "owner", here, here).contains("edges: west 30, east 30"));
        assertEquals("ok: protect base added (0 10 to 20 30, y 56..80) | mod: ok: 1 areas", pc.command("protect", "base here 10", true, "owner", here, here));
        assertTrue(pc.command("guard", "mode strict", true, "owner", here, here).endsWith("| mod: ok: guard mode strict"));
        assertTrue(g.strict);
        assertTrue(pc.command("guard", "mode log", true, "owner", here, here).startsWith("say \"guard mode log confirm\""));
        assertEquals("say \"area remove home confirm\" to remove it", pc.command("area", "remove home", true, "owner", here, here));
        assertTrue(pc.command("area", "remove home confirm", true, "owner", here, here).startsWith("ok: area home removed"));
        assertEquals(new JsonArray(), g.last.getAsJsonArray("areas"));
        assertTrue(pc.command("guard", "", true, "owner", here, here).startsWith("guard: mod not loaded"));
    }
}
