package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.io.BotFiles;
import io.github.mojolowjo.entropybot.memory.Knowledge;
import io.github.mojolowjo.entropybot.memory.Limits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Package H: what happens at each cap (the oldest go, or a refusal that says the limit). The timings are in LimitsStressTest. */
class LimitsTest {
    static final String OW = "minecraft:overworld";

    @TempDir
    Path dir;

    static JsonObject note(long seen) {
        return JsonParser.parseString("{\"dim\":\"" + OW + "\",\"items\":{\"minecraft:dirt\":1},\"seen\":" + seen + "}").getAsJsonObject();
    }

    @Test
    void chestNotesPastTheCapDropTheOldestButNeverAnUntrustedOne() {
        Knowledge k = new Knowledge();
        k.load(new BotFiles(dir));
        JsonObject cs = new JsonObject();
        for (int i = 0; i < Limits.CHESTS; i++) cs.add(i + " 0 0", note(1000 + i));
        JsonObject untrusted = note(1);                    // the oldest of all, but the owner said "untrust"
        untrusted.addProperty("trusted", false);
        cs.add("-1 0 0", untrusted);
        JsonObject ch = new JsonObject();
        ch.add("chests", cs);
        k.put(ch.toString(), 1);
        assertEquals(Limits.CHESTS, k.chests().size());
        assertTrue(k.chests().containsKey("-1 0 0"), "an untrusted chest stays");
        assertFalse(k.chests().containsKey("0 0 0"), "the oldest trusted note went");
        // the mod's own note (the food run) is the one kept even when it is old
        k.noteChest("9 9 9", note(5), 2);
        assertTrue(k.chests().containsKey("9 9 9"));
        assertEquals(Limits.CHESTS, k.chests().size());
    }

    @Test
    void chestNotesNearAMarkedPlaceAreKept() {
        Knowledge k = new Knowledge();
        k.load(new BotFiles(dir));
        k.put("{\"places\":{\"base\":{\"x\":-27,\"y\":53,\"z\":187,\"dim\":\"" + OW + "\"}}}", 1);
        JsonObject cs = new JsonObject();
        cs.add("-20 54 190", note(1));                      // the oldest of all, but a base chest
        cs.add("-60 54 190", note(2));                      // old and 33 blocks from the base: may go
        for (int i = 0; i < Limits.CHESTS; i++) cs.add(i + " 0 1000", note(1000 + i));
        JsonObject ch = new JsonObject();
        ch.add("chests", cs);
        k.put(ch.toString(), 2);
        assertTrue(k.chests().containsKey("-20 54 190"), "a chest by the base stays");
        assertFalse(k.chests().containsKey("-60 54 190"));
        assertEquals(Limits.CHESTS, k.chests().size());
        assertTrue(k.takePruneNote().startsWith("dropped the oldest 2 chest notes"), "the pruning is logged");
        assertNull(k.takePruneNote(), "once");
    }

    @Test
    void aFileOverTheCapIsTrimmedAtLoad() throws Exception {
        JsonObject cs = new JsonObject();
        for (int i = 0; i < Limits.CHESTS + 20; i++) cs.add(i + " 0 0", note(i));
        Files.writeString(dir.resolve(Knowledge.CHESTS), cs.toString());
        Knowledge k = new Knowledge();
        String line = k.load(new BotFiles(dir));
        assertEquals(Limits.CHESTS, k.chests().size());
        assertTrue(line.contains("dropped the 20 oldest"), line);
        assertTrue(k.chests().containsKey((Limits.CHESTS + 19) + " 0 0"));
    }

    @Test
    void rsReadingsKeepTheGridInUse() {
        Knowledge k = new Knowledge();
        k.load(new BotFiles(dir));
        k.noteRs("0 0 0", note(1), 1);                     // the oldest, but the grid "rs" uses
        for (int i = 1; i <= Limits.RS_READINGS + 3; i++) k.put("{\"rs\":{\"" + i + " 0 0\":" + note(100 + i) + "}}", 2);
        assertEquals(Limits.RS_READINGS, k.rs().size());
        assertTrue(k.rs().containsKey("0 0 0"));
        assertEquals("0 0 0", k.rsGrid());
    }

    @Test
    void aRuleOrARunRefusedByTheStepCapSaysSo() {
        JsonObject mem = new JsonObject();
        CommandsTest.Fake f = CommandsTest.fake(mem);
        f.chains.routineCommand("save low " + String.join(" then ", Collections.nCopies(10, "wait 1")));
        f.chains.routineCommand("save mid " + String.join(" then ", Collections.nCopies(10, "low")));
        assertTrue(f.chains.ruleCommand("every 1m do mid").startsWith("ok: rule #1"));
        // the routine grows afterwards: the rule's chain is now 110 steps
        assertTrue(f.chains.routineCommand("save low " + String.join(" then ", Collections.nCopies(11, "wait 1"))).startsWith("saved"));
        f.chains.rules().get(0).getAsJsonObject().addProperty("last", 0);
        f.chains.rulesTick();
        assertFalse(f.chains.running());
        assertEquals(1, f.whispers.size(), f.whispers.toString());
        assertTrue(f.whispers.get(0).startsWith("rule #1 (every 1m) didn't start: that is 110 steps"), f.whispers.get(0));
        f.chains.rules().get(0).getAsJsonObject().addProperty("last", 0);
        f.chains.rulesTick();
        assertEquals(1, f.whispers.size(), "said once");
        // a saved run that is too long now: dropped, and said
        JsonObject run = new JsonObject();
        run.addProperty("name", "mid");
        run.addProperty("text", "mid");
        run.addProperty("rounds", "1");
        run.addProperty("round", 1);
        run.addProperty("idx", 3);
        run.addProperty("from", "owner");
        run.addProperty("savedAt", f.now - 60000);
        mem.add("run", run);
        f.chains.resumeRun();
        assertFalse(f.chains.running());
        assertTrue(mem.get("run").isJsonNull(), "the run record is cleared");
        assertTrue(f.whispers.get(1).startsWith("I won't carry on with \"mid\" after the restart: that is 110 steps"), f.whispers.get(1));
    }

    @Test
    void aFullOutboxStillTakesAOneLineMessageToTheOwnerAndSaysWhatItDropped() {
        Outbox o = new Outbox();
        String tenParts = "word ".repeat(400);                 // ~2000 characters: 10 whispers
        for (int i = 0; i < Limits.OUTBOX / Limits.WHISPER_PARTS; i++) assertTrue(o.add("guest", tenParts, "owner").isEmpty());
        assertEquals(Limits.OUTBOX, o.size());
        List<String> lost = o.add("guest", tenParts, "owner");
        assertEquals(Limits.WHISPER_PARTS, lost.size(), "a long answer past the cap is dropped");
        assertEquals(1, o.add("guest", "hello", "owner").size(), "a line to someone else too");
        assertTrue(o.add("owner", "I died at 1 2 3 - going back for my corpse", "owner").isEmpty(), "a one-line message to the owner gets in");
        String[] first = o.poll();
        assertEquals("owner", first[0]);
        assertEquals("(11 whispers dropped: too many at once - the game log has them)", first[1], "the note is at the front");
        String[] m, last = null;
        while ((m = o.poll()) != null) last = m;
        assertEquals("I died at 1 2 3 - going back for my corpse", last[1]);
        // the next burst gets a fresh note
        for (int i = 0; i < Limits.OUTBOX / Limits.WHISPER_PARTS + 1; i++) o.add("guest", tenParts, "owner");
        assertEquals("(10 whispers dropped: too many at once - the game log has them)", o.poll()[1]);
    }

    @Test
    void routinesPastTheCapAreRefusedAndAKnownOneMayChange() {
        JsonObject mem = new JsonObject();
        CommandsTest.Fake f = CommandsTest.fake(mem);
        for (int i = 0; i < Limits.ROUTINES; i++) assertTrue(f.chains.routineCommand("save r" + i + " say hi").startsWith("saved"));
        String r = f.chains.routineCommand("save one_more say hi");
        assertTrue(r.startsWith("error: I keep at most " + Limits.ROUTINES + " routines - delete one first"), r);
        assertTrue(f.chains.routineCommand("save r5 say hello then wait 1").startsWith("saved"), "replacing one is fine");
        assertTrue(f.chains.routineCommand("delete r1").startsWith("deleted"));
        assertTrue(f.chains.routineCommand("save one_more say hi").startsWith("saved"));
        r = f.chains.routineCommand("save r7 say " + "x".repeat(Limits.ROUTINE_TEXT));
        assertTrue(r.startsWith("error: that is ") && r.contains("the most I keep for one is " + Limits.ROUTINE_TEXT), r);
    }

    @Test
    void chainsOfTooManyStepsAreRefusedAndLongOnesListedShort() {
        JsonObject mem = new JsonObject();
        CommandsTest.Fake f = CommandsTest.fake(mem);
        String ten = String.join(" then ", Collections.nCopies(10, "say x"));
        assertTrue(f.chains.routineCommand("save low " + ten).startsWith("saved"));
        String r = f.chains.routineCommand("save mid " + String.join(" then ", Collections.nCopies(10, "low")));
        assertTrue(r.startsWith("saved"), "100 steps is fine: " + r);
        r = f.chains.routineCommand("save top mid then mid");
        assertTrue(r.startsWith("error: that is 200 steps"), r);
        r = f.chains.startChain("owner", "x", "mid then say y", 1);
        assertTrue(r.startsWith("error: that is 101 steps with the routines in it, the most I run in one chain is " + Limits.CHAIN_STEPS), r);
        assertFalse(f.chains.running());
        r = f.chains.startChain("owner", "mid", "mid", 1);
        assertTrue(r.startsWith("started: mid - say x > ") && r.contains("> ... (+" + (100 - Limits.LIST_STEPS) + " more, 100 steps)"), r);
        assertTrue(r.length() < 300, r.length() + " characters");
    }

    @Test
    void rulesPastTheCapAreRefused() {
        JsonObject mem = new JsonObject();
        CommandsTest.Fake f = CommandsTest.fake(mem);
        for (int i = 0; i < Limits.RULES; i++) assertTrue(f.chains.ruleCommand("every " + (10 + i) + "m do farm").startsWith("ok: rule #"));
        String r = f.chains.ruleCommand("every 5m do deposit");
        assertTrue(r.startsWith("error: I keep at most " + Limits.RULES + " rules - delete one first"), r);
        assertEquals(Limits.RULES, f.chains.rules().size());
        assertTrue(Texts.whisperParts(f.chains.ruleCommand("")).size() <= 5, "the list stays short");
    }

    @Test
    void longAnswersAreCutToTheWhisperCapWithANote() {
        List<String> parts = Texts.whisperParts("word ".repeat(2000));
        assertTrue(parts.size() > Limits.WHISPER_PARTS);
        List<String> cut = Limits.capParts(parts, Limits.WHISPER_PARTS);
        assertEquals(Limits.WHISPER_PARTS, cut.size());
        assertTrue(cut.get(cut.size() - 1).startsWith("(+" + (parts.size() - Limits.WHISPER_PARTS + 1) + " more lines cut"), cut.get(cut.size() - 1));
        assertEquals(3, Limits.capParts(parts.subList(0, 3), Limits.WHISPER_PARTS).size(), "a short answer is left alone");
    }

    @Test
    void namedThingsPastTheirCapAreRefusedWithTheLimit() {
        assertNull(Limits.full(false, Limits.PLACES - 1, Limits.PLACES, "places", "forget one first"));
        assertNull(Limits.full(true, Limits.PLACES, Limits.PLACES, "places", "forget one first"), "a known name may move");
        assertEquals("error: I keep at most " + Limits.PLACES + " places - forget one first", Limits.full(false, Limits.PLACES, Limits.PLACES, "places", "forget one first"));
        // the "places" answer at the cap fits the whisper cap (name + "x y z" ~ 25 characters each)
        List<String> pl = new ArrayList<>();
        for (int i = 0; i < Limits.PLACES; i++) pl.add("place_" + i + " " + (-1000 + i) + " 64 " + (2000 - i));
        assertTrue(Texts.whisperParts(String.join(" | ", pl)).size() < Limits.WHISPER_PARTS, Texts.whisperParts(String.join(" | ", pl)).size() + " parts");
        JsonArray areas = new JsonArray();
        for (int i = 0; i < Limits.AREAS; i++) areas.add(new JsonObject());
        assertTrue(PolicyCommands.areasFull(areas, -1).startsWith("error: I keep at most " + Limits.AREAS + " areas"));
        assertNull(PolicyCommands.areasFull(areas, 3), "replacing an area is fine");
    }
}
