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
