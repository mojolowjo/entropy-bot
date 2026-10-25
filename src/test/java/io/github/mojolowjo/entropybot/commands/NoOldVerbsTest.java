package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Owner 2026-10-23 ("AA is the only driver"): the old owner verbs and the AI operator are deleted, not hidden. No table
 * names them, no source registers them, the typed steps never become verb text, and aa help (from its dispatch table)
 * shows only live forms.
 */
class NoOldVerbsTest {
    static final List<String> DELETED = List.of("deposit", "scan", "take", "put", "get", "give", "drop", "craft", "smelt", "cook", "cut",
            "dig", "gather", "hunt", "hunting", "fish", "farm", "explore", "go", "goto", "come", "follow", "flee", "climb", "burrow", "shelter",
            "house", "build", "light", "place", "compact", "restock", "restore", "repeat", "wait", "sleep", "eat", "attack", "upgrade", "pots",
            "rs", "goal", "plan", "need", "surface", "summary", "assist", "open", "close", "stop", "ai");

    static final Path MAIN = Path.of("src/main/java/io/github/mojolowjo/entropybot");

    @Test
    void noTableNamesADeletedVerb() {
        for (String w : DELETED) {
            assertNull(VerbTable.of(w), "verb table: " + w);
            assertFalse(Texts.BUILTIN_VERBS.contains(w), "BUILTIN_VERBS: " + w);
            assertFalse(Texts.MOD_JOB_VERBS.contains(w), "MOD_JOB_VERBS: " + w);
            assertFalse(Texts.MOD_VERBS.contains(w), "MOD_VERBS: " + w);
            assertTrue(HelpCommand.DELETED.contains(w), "help names the aa way for " + w);
            assertTrue(Hints.unknown(w).contains("was deleted"), w);
        }
    }

    @Test
    void noSourceRegistersADeletedVerb() throws Exception {
        Pattern reg = Pattern.compile("(?:verb|type)\\.equals\\(\"([a-z]+)\"\\)|case \"([a-z]+)\"(?:, \"[a-z]+\")* ->|verb\\(\"([a-z]+)\"");
        List<String> bad = new ArrayList<>();
        for (String f : List.of("commands/Commands.java")) {
            String src = Files.readString(MAIN.resolve(f));
            Matcher m = reg.matcher(src);
            while (m.find()) {
                String w = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
                if (DELETED.contains(w)) bad.add(f + ": " + m.group());
            }
            Matcher multi = Pattern.compile("case ((?:\"[a-z]+\",\\s*)+\"[a-z]+\") ->").matcher(src);
            while (multi.find())
                for (String w : multi.group(1).replace("\"", "").split(",\\s*")) if (DELETED.contains(w)) bad.add(f + ": " + multi.group());
        }
        assertTrue(bad.isEmpty(), "deleted verbs still dispatched: " + bad);
        assertFalse(Files.exists(MAIN.resolve("ai")), "the ai package is gone");
        assertFalse(Files.exists(MAIN.resolve("summary")), "the summary package is gone");
    }

    @Test
    void stepsNeverBecomeVerbText() throws Exception {
        assertFalse(Files.exists(MAIN.resolve("commands/StepCommands.java")), "StepCommands (step -> verb text) is gone");
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) assertFalse(Files.readString(p).contains("StepCommands"), p.toString());
        }
        for (String f : List.of("commands/StepHands.java", "commands/DepositLeg.java", "commands/StepRun.java")) {
            String src = Files.readString(MAIN.resolve(f));
            assertFalse(src.contains("c.handle(") || src.contains(".handle(c.owner()"), f + " runs no line through the verb runner");
            assertFalse(src.contains("Function<String, String> verb"), f + ": no verb-text callback");
        }
    }

    @Test
    void aaHelpShowsOnlyLiveForms() {
        String help = AaCommands.help();
        for (String f : List.of("aa scan", "aa eat", "aa restore", "aa want deposit", "aa want <item> [n], <item> [n]")) assertTrue(help.contains(f), f + ": " + help);
        for (String w : AaCommands.TABLE.keySet()) assertTrue(help.contains("aa " + w), w);
        // 0.39.z54 A6: "aa deposit [x y z]" is the main-stations form (z35, owner 2026-10-23: list the deposit chests, mark
        // the main), not the deleted deposit job (that is "aa want deposit")
        for (String w : DELETED) if (!List.of("scan", "eat", "restore", "go", "drop", "build", "deposit").contains(w)) assertNull(AaCommands.TABLE.get(w), "aa " + w);
    }

    @Test
    void wantListForms() {
        assertEquals(List.of("torch 16", "wooden_pickaxe", "stone tools"), AaCommands.wantList("torch 16, wooden_pickaxe, stone tools"));
        assertEquals(List.of("iron_ingot 3", "bucket"), AaCommands.wantList("iron_ingot 3, bucket"));
        assertEquals(List.of("torch 16 high", "bucket high"), AaCommands.wantList("torch 16, bucket high"));
        assertEquals(List.of("torch 16 low", "bucket high"), AaCommands.wantList("torch 16 low, bucket high"));
        assertEquals(List.of("torch 16"), AaCommands.wantList("torch 16"));
        assertEquals(List.of("strip iron_ore 64,coal_ore 16 east"), AaCommands.wantList("strip iron_ore 64,coal_ore 16 east"), "a strip order stays one");
        assertEquals(List.of("a", "b"), AaCommands.wantList("a, , b"));
    }
}
