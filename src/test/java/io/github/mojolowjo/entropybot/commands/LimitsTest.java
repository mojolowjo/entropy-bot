package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.cave.Caves;
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
    void cavesPastTheCapsDropTheOldestFinishedFirst() {
        Caves c = new Caves();
        c.load(new BotFiles(dir));
        Caves.Cave first = c.pick(null, OW, 0, 0, 0, 1, 1);      // the oldest, but with frontier left
        for (int i = 1; i < Caves.MAX_CAVES; i++) {
            Caves.Cave cv = c.pick(null, OW, i * 1000, 0, 0, 10 + i, 1);
            c.finish(cv, false, 10 + i, 1);
        }
        assertEquals(Caves.MAX_CAVES, c.toJson(false).getAsJsonObject("caves").size());
        Caves.Cave n = c.pick(null, OW, 999_000, 0, 0, 5000, 2);
        JsonObject all = c.toJson(false).getAsJsonObject("caves");
        assertEquals(Caves.MAX_CAVES, all.size());
        assertTrue(all.has(first.name), "a cave with frontier left outlives the finished ones");
        assertTrue(all.has(n.name));
        assertFalse(all.has("cave_2"), "the oldest finished cave went");
    }

    @Test
    void caveCellsInAllStayUnderTheBudgetAndTheCurrentCaveStays() {
        Caves c = new Caves();
        c.load(new BotFiles(dir));
        List<Caves.Cave> made = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            Caves.Cave cv = c.pick(null, OW, k * 5000, 0, 0, k, k);
            for (long i = 0; cv.visited.size() < Caves.MAX_CELLS / 4; i++) cv.visited.add(i * 31 + k);
            c.visit(cv, k * 5000, 0, 0, k, k);
            made.add(cv);
        }
        long cells = 0;
        JsonObject all = c.toJson(false).getAsJsonObject("caves");
        for (String name : all.keySet()) cells += all.getAsJsonObject(name).get("explored").getAsLong();
        assertTrue(cells <= Caves.MAX_CELLS, cells + " cells");
        assertTrue(all.has(made.get(5).name), "the cave being explored stays");
        assertFalse(all.has(made.get(0).name), "the oldest went");
        // one cave stops growing at its own cap
        Caves.Cave big = made.get(5);
        for (int s = 0; s < 3000; s++) c.visit(big, s * 9, 0, 0, 99, 99);
        assertTrue(big.visited.size() <= Caves.MAX_VISITED + 27);
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
    void whyShortensTheOlderDecisionsAndKeepsTheLatestWhole() {
        JsonObject mem = new JsonObject();
        CommandsTest.Fake f = CommandsTest.fake(mem);
        JsonObject a = new JsonObject();
        JsonArray log = new JsonArray();
        for (int i = 0; i < 20; i++) {
            JsonObject e = new JsonObject();
            e.addProperty("at", f.now);
            e.addProperty("what", "mine strip any 32");
            e.addProperty("why", "my mine is ready");
            e.addProperty("result", "r" + i + " " + "x".repeat(990));
            log.add(e);
        }
        a.add("log", log);
        mem.add("autominer", a);
        String why = f.chains.whyCommand();
        String latest = why.substring(why.indexOf("latest: "));
        assertTrue(latest.contains("r19 " + "x".repeat(990)), "the latest whole");
        assertTrue(why.substring(0, why.indexOf("\n")).length() < 4 * (Limits.WHY_OLDER + 10));
        assertTrue(Texts.whisperParts(why).size() <= Limits.WHISPER_PARTS, Texts.whisperParts(why).size() + " parts");
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
        assertEquals("error: I keep at most 100 places - forget one first", Limits.full(false, Limits.PLACES, Limits.PLACES, "places", "forget one first"));
        JsonArray areas = new JsonArray();
        for (int i = 0; i < Limits.AREAS; i++) areas.add(new JsonObject());
        assertTrue(PolicyCommands.areasFull(areas, -1).startsWith("error: I keep at most " + Limits.AREAS + " areas"));
        assertNull(PolicyCommands.areasFull(areas, 3), "replacing an area is fine");
    }
}
