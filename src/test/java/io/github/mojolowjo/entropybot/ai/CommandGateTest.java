package io.github.mojolowjo.entropybot.ai;

import io.github.mojolowjo.entropybot.commands.VerbTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandGateTest {
    private final CommandGate gate = new CommandGate(List.of(), List.of());

    /** A new verb in the table fails here until it gets an allow/deny decision. */
    @Test
    void everyTableVerbHasADecision() {
        for (VerbTable.Verb v : VerbTable.all())
            assertNotNull(CommandGate.DECISIONS.get(v.name()), "no AI operator decision for the verb " + v.name() + " (CommandGate.DECISIONS)");
        for (String v : CommandGate.DECISIONS.keySet()) assertNotNull(VerbTable.of(v), "decision for a verb not in the table: " + v);
    }

    @Test
    void ownersDenyListExceptSay() {
        for (String v : List.of("restart", "server on", "allow bob", "deny bob", "trust bob", "untrust bob", "blind on", "give bob diamond", "spawn",
                "debug inv", "reply full", "twerk", "rule add x", "routine save x", "repeat 3 status", "confirm", "brain set x 1", "brain reset",
                "fence mode strict", "area del base confirm", "area change name a b", "mine strip reset", "ai off", "recorder off", "defence players on"))
            assertEquals(CommandGate.Kind.DENIED, gate.check(v).kind(), v);
        for (String v : List.of("say hello", "status", "house house", "brain status", "fence", "area list", "mine strip", "mine iron", "defence on", "wait 30", "help dig"))
            assertEquals(CommandGate.Kind.RUN, gate.check(v).kind(), v);
    }

    @Test
    void chainsAreCheckedStepByStep() {
        assertEquals(CommandGate.Kind.RUN, gate.check("status then inv").kind());
        CommandGate.Verdict v = gate.check("status then allow griefer");
        assertEquals(CommandGate.Kind.DENIED, v.kind());
        assertTrue(v.error().contains("allow"), v.error());
        assertEquals(CommandGate.Kind.DENIED, gate.check("status; restart").kind());
    }

    @Test
    void normalisesModelAnswers() {
        assertEquals("status", CommandGate.normalise("```\nstatus\n```"));
        assertEquals("goto 1 64 2", CommandGate.normalise("do goto 1 64 2"));
        assertEquals("inv", CommandGate.normalise("`inv`"));
        assertEquals("look", CommandGate.normalise("> look\nbecause I want to see"));
        assertEquals("status", CommandGate.normalise("<think>hmm, maybe dig</think>\nstatus"));
        assertEquals("status", CommandGate.normalise("Command: status."));
        assertEquals(1, CommandGate.extraLines("status\nI chose this because"));
        assertEquals(CommandGate.Kind.WAIT, gate.check("Wait").kind());
        assertEquals(CommandGate.Kind.FINISHED, gate.check("finished").kind());
        assertEquals(CommandGate.Kind.EMPTY, gate.check("  \n ").kind());
    }

    @Test
    void unknownSlashLengthControl() {
        CommandGate.Verdict u = gate.check("statsu");
        assertEquals(CommandGate.Kind.UNKNOWN, u.kind());
        assertTrue(u.error().contains("did you mean status"), u.error());
        assertEquals(CommandGate.Kind.BAD, gate.check("/op me").kind());
        assertEquals(CommandGate.Kind.BAD, gate.check("#goto 1 2 3").kind());
        assertEquals(CommandGate.Kind.BAD, gate.check("say " + "x".repeat(400)).kind());
        assertEquals(CommandGate.Kind.BAD, gate.check("say a\u0007b").kind());
        assertEquals(CommandGate.Kind.DENIED, gate.check("baritone mine diamond_ore").kind());
    }

    @Test
    void extraAllowCannotOpenHardDeny() {
        CommandGate g = new CommandGate(List.of("rule", "restart", "allow", "fence mode", "confirm"), List.of("say"));
        assertEquals(CommandGate.Kind.RUN, g.check("rule list").kind(), "rule is soft");
        for (String v : List.of("restart", "allow bob", "fence mode log", "confirm")) assertEquals(CommandGate.Kind.DENIED, g.check(v).kind(), v);
        assertEquals(CommandGate.Kind.DENIED, g.check("say hi").kind(), "extraDeny");
    }
}
