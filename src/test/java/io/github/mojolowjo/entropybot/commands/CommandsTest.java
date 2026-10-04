package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.commands.BridgeLink.Request;
import io.github.mojolowjo.entropybot.commands.Chains.Reply;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** B7a: the PM texts, chat parsing, the bridge link, chains, rules, the autominer, the death policy, the policy verbs. */
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

    // ---- the bridge link ----

    static final class Seen implements BridgeLink.Listener {
        final List<String> log = new ArrayList<>();

        @Override public void replied(Request r) { log.add("reply " + r.id + " " + r.reply + (r.started ? " [job]" : "")); }

        @Override public void finished(Request r) { log.add("done " + r.id + " " + r.doneMsg); }
    }

    @Test
    void requestsGoOutAnswerAndEnd() {
        BridgeLink b = new BridgeLink();
        Seen s = new Seen();
        b.report(new JsonObject(), 0);
        Request r = b.submit("pm", "o", "goto 1 2 3", false, null, s, 0);
        JsonObject out = JsonParser.parseString(b.next(1)).getAsJsonObject();
        assertEquals(r.id, out.get("id").getAsLong());
        assertEquals("", b.next(1));
        b.reply(r.id, "ok: going", true);
        assertTrue(b.busy(2));
        b.done(r.id, "ok: arrived");
        assertEquals(List.of("reply 1 ok: going [job]", "done 1 ok: arrived"), s.log);
        Request stop = b.submit("pm", "o", "farm", false, null, s, 3);
        b.submit("stop", "o", "stop", false, null, null, 3);
        assertEquals("stop", JsonParser.parseString(b.next(4)).getAsJsonObject().get("kind").getAsString(), "stop jumps the queue");
        b.dropQueued();
        assertTrue(stop.replied && "ok: stopped".equals(stop.reply));
    }

    @Test
    void aReloadEndsWhatTheBridgeHadAndSilenceTimesOut() {
        BridgeLink b = new BridgeLink();
        Seen s = new Seen();
        b.report(new JsonObject(), 0);
        Request r = b.submit("pm", "o", "farm", false, null, s, 0);
        b.next(1);
        b.reply(r.id, "started: farming", true);
        b.hello(10);
        assertEquals("done 1 " + BridgeLink.RELOADED, s.log.get(1));
        Request q = b.submit("pm", "o", "places", false, null, s, 20);
        b.next(20);
        b.tick(20 + BridgeLink.REPLY_TIMEOUT + 1);
        assertTrue(q.replied && q.reply.startsWith("error: no answer"));
    }

    @Test
    void aJobThatVanishesIsLetGoAfterTwoReports() {
        BridgeLink b = new BridgeLink();
        Seen s = new Seen();
        b.report(new JsonObject(), 0);
        Request r = b.submit("pm", "o", "goto 1 2 3", false, null, s, 0);
        b.next(0);
        b.reply(r.id, "ok", true);
        JsonObject rep = JsonParser.parseString("{\"job\":{\"type\":\"travel\",\"status\":\"going\",\"done\":false,\"req\":1}}").getAsJsonObject();
        b.report(rep, 20);
        assertFalse(r.finished);
        JsonObject other = JsonParser.parseString("{\"job\":{\"type\":\"travel\",\"status\":\"x\",\"done\":false,\"req\":9}}").getAsJsonObject();
        b.report(other, 40);
        b.report(other, 60);
        assertTrue(r.finished && r.doneMsg.startsWith("stopped: lost track of the job (going)"));
    }

    @Test
    void theModsOwnJobsIgnoreTheBridge() {
        BridgeLink b = new BridgeLink();
        Seen s = new Seen();
        b.report(new JsonObject(), 0);
        Request r = b.local("pm", "o", "goto 1 2 3", "ok: going to 1 2 3", s, 0);
        assertTrue(r.replied && r.started && b.busy(1));
        assertEquals("", b.next(1), "nothing for the bridge to do");
        b.hello(5);
        JsonObject other = JsonParser.parseString("{\"job\":{\"type\":\"farm\",\"status\":\"x\",\"done\":false,\"req\":9}}").getAsJsonObject();
        b.report(other, 20);
        b.report(other, 40);
        b.tick(40 + BridgeLink.LOST_AFTER + BridgeLink.REPLY_TIMEOUT);
        assertFalse(r.finished, "a reload, reports and silence leave it alone");
        b.done(r.id, "ok: arrived");
        assertEquals(List.of("done 1 ok: arrived"), s.log);
        assertTrue(BridgeLink.quiet(BridgeLink.REPLACED) && !BridgeLink.quiet("ok: arrived"));
    }

    // ---- chains, with a fake game ----

    static final class Fake implements Chains.Env {
        long tick, now = 1_000_000_000L;
        final List<String> whispers = new ArrayList<>(), ran = new ArrayList<>();
        final BridgeLink link = new BridgeLink();
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
        public Reply dispatch(String from, String text, boolean internal, BridgeLink.Listener l) {
            ran.add(text);
            String v = Texts.verbAndRest(text)[0];
            if (v.equals("stop")) {
                chains.clear();
                return Reply.now("ok: stopped everything");
            }
            if (instant.containsKey(v)) return Reply.now(instant.get(v));
            return new Reply(null, link.submit("pm", from, text, internal, null, l, tick));
        }

        @Override public boolean alive() { return true; }
        @Override public boolean holding() { return holding; }
        @Override public boolean fighting() { return fighting; }
        @Override public float health() { return health; }
        @Override public boolean busy() { return link.busy(tick); }
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

        /** The bridge takes the next request and answers; started = a job runs. */
        void bridgeTakes(String reply, boolean started) {
            String j = link.next(tick);
            assertFalse(j.isEmpty(), "a request is waiting");
            link.reply(JsonParser.parseString(j).getAsJsonObject().get("id").getAsLong(), reply, started);
        }

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
    void aChainRunsItsStepsThroughTheBridgeAndReports() {
        JsonObject mem = new JsonObject();
        Fake f = fake(mem);
        assertEquals("started: chain - goto 1 2 3 > places > wait 2", f.chains.startChain("owner", "chain", "goto 1 2 3 then places then wait 2", 1));
        f.run(5);
        f.bridgeTakes("ok: going to 1 2 3", true);
        f.run(10);
        assertTrue(mem.getAsJsonObject("run").get("idx").getAsInt() == 0, "the running step is what a restart runs again");
        f.link.done(1, "ok: arrived");
        f.run(10);
        assertEquals(List.of("goto 1 2 3", "places", "wait 2"), f.ran);
        f.bridgeTakes("started: waiting 2s", true);
        f.link.done(2, "ok: waited");
        f.run(10);
        assertFalse(f.chains.running());
        assertEquals("chain: done - waited", f.whispers.get(f.whispers.size() - 1));
        assertTrue(mem.get("run").isJsonNull());
    }

    @Test
    void aFailedStepEndsTheChainAndAFightIsRetried() {
        Fake f = fake(new JsonObject());
        f.chains.startChain("owner", "chain", "goto 1 2 3 then places", 1);
        f.run(5);
        f.bridgeTakes("ok: going", true);
        f.link.done(1, "stopped: attacked by zombie");
        f.run(5);
        assertTrue(f.chains.running(), "a fight is retried");
        f.fighting = true;
        f.run(400);
        assertEquals(1, f.ran.size(), "not while fighting");
        f.fighting = false;
        f.run(10);
        assertEquals(2, f.ran.size(), "then the same step again");
        f.bridgeTakes("error: no path to 1 2 3", false);
        f.run(10);
        assertFalse(f.chains.running());
        assertEquals("chain: stopped at step 1 (goto 1 2 3): no path to 1 2 3", f.whispers.get(f.whispers.size() - 1));
    }

    @Test
    void aReloadOfTheBridgeRunsTheStepAgain() {
        Fake f = fake(new JsonObject());
        f.chains.startChain("owner", "repeat", "farm", Chains.FOREVER);
        f.run(5);
        f.bridgeTakes("started: farming", true);
        f.link.hello(f.tick);
        f.run(5);
        assertTrue(f.whispers.get(0).startsWith("carrying on with repeat (round 1, step 1: farm) after a reload"), f.whispers.toString());
        f.run(5);
        assertEquals(2, f.ran.size());
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
        f.bridgeTakes("started: farming", true);
        f.chains.noteDeath();
        assertFalse(f.chains.running());
        f.link.done(1, "stopped: the bot died");
        f.chains.deathTick(false);
        f.tick += 100;
        f.chains.deathTick(false);
        assertEquals("death", f.ran.get(f.ran.size() - 1), "it goes for the corpse");
        f.bridgeTakes("started: going back", true);
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
