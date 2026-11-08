package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Owner 2026-10-22: aa help is written from the table that dispatches aa; the plain help pages carry no deleted verb. */
class AaHelpTest {

    @Test
    void everyDispatchedAaFormIsInTheHelp() {
        String help = AaCommands.help();
        for (var e : AaCommands.TABLE.entrySet()) assertTrue(help.contains("aa " + e.getKey()), "aa " + e.getKey() + " missing: " + help);
        for (String f : List.of("aa on", "aa off", "aa needs on|off", "aa only <need>|off", "aa want <item|group> [n]",
                "aa want strip <ore[ amount]>[,...] [<feet y>] [<heading>]", "aa goals", "aa drop <item>|all", "aa do <need>",
                "aa why", "aa status", "aa tune [<group>|<name> [<n>]]", "aa forget stations", "aa fair on|off"))
            assertTrue(help.contains(f), f + " missing: " + help);
        assertEquals(help, AaCommands.handle(null, ""), "the bare aa is the help");
        assertEquals(help, AaCommands.handle(null, "help"));
    }

    @Test
    void theHelpListsEveryFormOnceUnderItsHeadingAndNothingElse() {
        String[] lines = AaCommands.help().split("\n");
        List<String> want = new java.util.ArrayList<>();
        for (String g : AaCommands.GROUPS) {
            want.add(g + ":");
            if (g.equals(AaCommands.AREAS)) for (VerbTable.Form f : VerbTable.of("area").forms()) want.add("  " + f.form() + " - " + f.meaning());
            else for (AaCommands.Form f : AaCommands.allForms()) if (f.group().equals(g)) want.add("  aa " + f.form() + " - " + f.meaning());
        }
        assertEquals(want, List.of(lines).subList(1, lines.length), "the help is exactly the table, grouped");
        int listed = 0;
        for (String l : lines) if (l.startsWith("  aa ")) listed++;
        assertEquals(AaCommands.allForms().size(), listed, "every aa form once, no dead one");
        for (AaCommands.Form f : AaCommands.allForms()) assertTrue(f.meaning().length() <= AaCommands.MEANING_MAX, f.form());
        for (VerbTable.Form f : VerbTable.of("area").forms()) assertTrue(f.meaning().length() <= VerbTable.MEANING_MAX, f.form());
        assertTrue(VerbTable.of("area").usage().contains("area tag <name> <tag...> | area untag <name> <tag...>"));
        List<String> order = List.of("want <item|group>", "want <item> [n] [-d]", "want strip", "drop ", "goals", "go ", "skip ", "do ", "use ", "pick ", "ask ");
        int at = -1;
        for (String o : order) {
            int i = -1;
            for (int k = 0; k < lines.length; k++) if (lines[k].startsWith("  aa " + o)) { i = k; break; }
            assertTrue(i > at, "orders in the owner's order: " + o);
            at = i;
        }
    }

    @Test
    void slashBotCompletesAaAndAreaWords() {
        assertTrue(HelpCommand.completions("aa st").containsAll(List.of("status", "stats")));
        assertTrue(HelpCommand.completions("area t").contains("tag"));
        assertTrue(HelpCommand.completions("area ").containsAll(List.of("here", "untag", "list", "candidates")));
        assertFalse(HelpCommand.completions("area ").contains("x"));
        assertTrue(HelpCommand.completions("ar").contains("area"));
        assertTrue(HelpCommand.completions("aa want ").isEmpty());
    }

    @Test
    void theOwnerHelpListsEveryLiveVerbWithAShortMeaning() {
        String list = HelpCommand.verbList();
        for (VerbTable.Verb v : VerbTable.all()) {
            if (HelpCommand.deleted(v)) assertFalse(list.contains("\n  " + v.name() + " - "), v.name());
            else assertTrue(list.contains("  " + v.name() + " - " + VerbTable.meaning(v)), v.name());
            assertTrue(VerbTable.meaning(v).length() <= VerbTable.MEANING_MAX, v.name());
        }
        assertTrue(HelpCommand.answer("", true, "owner").endsWith(list));
    }

    @Test
    void thePlainHelpCarriesNoDeletedVerb() {
        String all = HelpCommand.answer("all", true, "owner");
        for (String line : all.split("\n")) {
            String body = line.substring(line.indexOf(':') + 1);
            for (String usage : body.split(";")) {
                for (String form : usage.split(" \\| ")) {
                    String w = form.trim().split("\\s+")[0];
                    assertFalse(HelpCommand.DELETED.contains(w), "a deleted verb in the help: " + form);
                    assertFalse(form.trim().startsWith("mine strip"), "mine strip in the help: " + form);
                }
            }
        }
        assertTrue(HelpCommand.answer("gather", true, "owner").contains("aa help"));
    }
}
