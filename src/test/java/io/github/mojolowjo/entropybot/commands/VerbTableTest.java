package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** B7e N item 1: the verb table covers every command word, and "help" answers each of them. */
class VerbTableTest {
    static final String OWNER = "mojolowjo";

    @Test
    void coversEveryBuiltinVerb() {
        for (String v : Texts.BUILTIN_VERBS) assertNotNull(VerbTable.of(v), "not in the verb table: " + v);
        for (String v : List.of("recorder", "debug", "mouse", "check", "confirm")) assertNotNull(VerbTable.of(v), v);
        assertTrue(VerbTable.of("dig").usage().contains("[floor [block]]"), "dig's floor option");
        assertTrue(VerbTable.of("dig").usage().contains("[water [large]]"), "dig's water option (water plan)");
        assertEquals("help", VerbTable.of("?").name());
        assertEquals("routine", VerbTable.of("ROUTINES").name());
    }

    @Test
    void everyVerbHasUsageExamplesAndASection() {
        for (VerbTable.Verb v : VerbTable.all()) {
            assertTrue(v.usage().toLowerCase().contains(v.name()), "usage names the verb: " + v.name());
            assertFalse(v.examples().isEmpty(), "an example: " + v.name());
            assertFalse(v.what().isEmpty(), v.name());
            assertEquals(v.who() == VerbTable.Who.PARTLY, v.guestForms() != null, "guest forms exactly for PARTLY: " + v.name());
            assertFalse(VerbTable.section(v.section()).isEmpty());
        }
    }

    @Test
    void helpForEachVerb() {
        Set<String> words = new LinkedHashSet<>(Texts.BUILTIN_VERBS);
        for (VerbTable.Verb v : VerbTable.all()) words.addAll(VerbTable.words(v));
        for (String w : words) {
            VerbTable.Verb v = VerbTable.of(w);
            String owner = HelpCommand.answer(w, true, OWNER);
            assertTrue(owner.startsWith(v.usage()), "owner help " + w + ": " + owner);
            assertTrue(owner.contains("e.g. " + v.examples().get(0)), w);
            assertTrue(Texts.whisperParts(owner).size() <= 10, "fits the whisper cap: " + w);
            String guest = HelpCommand.answer(w, false, OWNER);
            switch (v.who()) {
                case OWNER -> assertTrue(guest.contains("only for " + OWNER), "guest help " + w + ": " + guest);
                case PARTLY -> assertTrue(guest.startsWith("you can use: " + v.guestForms()), guest);
                case GUEST -> assertTrue(guest.startsWith(v.usage()) && !guest.contains("[allowed players"), guest);
            }
        }
    }

    @Test
    void pagesAndIndex() {
        String idx = HelpCommand.answer("", true, OWNER);
        assertTrue(idx.startsWith("help <page>: 1 moving, 2 chests"), idx);
        for (int i = 1; i <= VerbTable.SECTIONS.size(); i++) {
            String p = HelpCommand.answer(String.valueOf(i), true, OWNER);
            assertTrue(p.startsWith("help " + i + "/" + VerbTable.SECTIONS.size() + " " + VerbTable.SECTIONS.get(i - 1) + ": "), p);
            assertTrue(Texts.whisperParts(p).size() <= 10, "page " + i + " fits: " + Texts.whisperParts(p).size());
            assertEquals(p, HelpCommand.answer(VerbTable.SECTIONS.get(i - 1), true, OWNER), "a page by its name");
        }
        assertTrue(HelpCommand.answer("2", true, OWNER).contains("help 3 for crafting"));
        assertTrue(HelpCommand.answer("99", true, OWNER).startsWith("there are pages 1 to 8"));
        assertTrue(HelpCommand.answer("0", true, OWNER).startsWith("there are pages"));
        assertTrue(HelpCommand.answer("123456789012", true, OWNER).startsWith("there are pages"));
        String all = HelpCommand.answer("all", true, OWNER);
        assertEquals(VerbTable.SECTIONS.size(), all.split("\n").length);
        // every verb is on its page
        for (VerbTable.Verb v : VerbTable.all()) {
            String p = HelpCommand.answer(String.valueOf(VerbTable.SECTIONS.indexOf(v.section()) + 1), true, OWNER);
            assertTrue(p.contains(v.usage()), v.name());
        }
    }

    @Test
    void guestsHelpComesFromTheTable() {
        String g = HelpCommand.answer("", false, OWNER);
        assertTrue(g.startsWith("You can use: "), g);
        List<String> items = List.of(g.substring("You can use: ".length(), g.indexOf(" - help <command>")).split(", "));
        for (VerbTable.Verb v : VerbTable.all()) {
            if (v.who() == VerbTable.Who.GUEST) assertTrue(items.contains(v.usage()), v.name());
            if (v.who() == VerbTable.Who.PARTLY) assertTrue(g.contains(v.guestForms()), v.name());
            if (v.who() == VerbTable.Who.OWNER) assertFalse(items.contains(v.usage()), v.name());
        }
        assertEquals(g, HelpCommand.answer("2", false, OWNER), "guests have one page");
        assertTrue(HelpCommand.answer("dig", false, OWNER).contains("only for mojolowjo"));
    }

    /** The table's who matches what Texts.guestRefusal really lets guests do. */
    @Test
    void whoMatchesTheGuestRule() {
        for (VerbTable.Verb v : VerbTable.all()) {
            String bare = Texts.guestRefusal(v.name(), "", v.name(), OWNER);
            if (v.who() == VerbTable.Who.GUEST) assertNull(bare, "guests may: " + v.name());
            if (v.who() == VerbTable.Who.OWNER) assertNotNull(bare, "owner only: " + v.name());
            if (v.who() == VerbTable.Who.PARTLY) {
                for (String form : v.guestForms().split(",\\s*")) {
                    String raw = form.replaceAll("\\([^)]*\\)", "").replaceAll("\\[[^]]*]", "").replaceAll("<[^>]*>", "x").replaceAll("\\s+", " ").trim();
                    String[] vr = Texts.verbAndRest(raw);
                    assertNull(Texts.guestRefusal(vr[0], vr[1], vr[2], OWNER), "guests may use " + raw + " (" + v.name() + ")");
                }
            }
        }
        for (String g : Texts.GUEST_VERBS) {
            if (g.isEmpty()) continue;
            VerbTable.Verb v = VerbTable.of(g);
            assertNotNull(v, g);
            assertNotEquals(VerbTable.Who.OWNER, v.who(), g);
        }
    }

    @Test
    void suggestions() {
        assertEquals("mine", VerbTable.suggest("minee"));
        assertEquals("stripmine", VerbTable.suggest("stripmin"));
        assertEquals("deposit", VerbTable.suggest("deposti"));
        assertEquals("autominer", VerbTable.suggest("autom"));
        assertNull(VerbTable.suggest("mine"), "a known word needs no suggestion");
        assertNull(VerbTable.suggest("xyzzyq"));
        assertNull(VerbTable.suggest(""));
        assertTrue(HelpCommand.answer("minee", true, OWNER).contains("did you mean mine?"));
        assertTrue(HelpCommand.answer("qqqqqq", true, OWNER).contains("help lists the pages"));
    }

    /** Every command in docs/COMMANDS.md's command tables is in the verb table (skipped when the bot repo isn't there). */
    @Test
    void commandsGuideMatchesTheTable() throws Exception {
        Path doc = null;
        for (String c : List.of("../minecraft-bot/docs/COMMANDS.md", "../../minecraft-bot/docs/COMMANDS.md", "../../../minecraft-bot/docs/COMMANDS.md")) {
            Path p = Path.of(c);
            if (Files.exists(p)) {
                doc = p;
                break;
            }
        }
        Assumptions.assumeTrue(doc != null, "docs/COMMANDS.md not found next to the mod repo");
        List<String> missing = new ArrayList<>();
        int seen = 0;
        boolean inCommandTable = false;
        Pattern first = Pattern.compile("^\\|\\s*`([^`]+)`");
        for (String line : Files.readAllLines(doc, StandardCharsets.UTF_8)) {
            if (!line.startsWith("|")) {
                inCommandTable = false;
                continue;
            }
            if (line.matches("^\\|\\s*Command\\s*\\|.*")) {
                inCommandTable = true;
                continue;
            }
            if (!inCommandTable) continue;
            Matcher m = first.matcher(line);
            if (!m.find()) continue;
            String cmd = m.group(1).trim();
            if (cmd.contains(" then ")) continue;                // "a then b then c": a chain, not a word
            String word = cmd.split("\\s+")[0].toLowerCase();
            seen++;
            if (VerbTable.of(word) == null) missing.add(word);
        }
        assertTrue(seen > 50, "read the guide's tables (" + seen + ")");
        assertTrue(missing.isEmpty(), "in COMMANDS.md but not in the verb table: " + missing);
    }
}
