package io.github.mojolowjo.entropybot.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandGateTest {
    private final CommandGate gate = new CommandGate(List.of(), List.of());

    @Test
    void onlyAaWantDropAndQuestionsPass() {
        for (String v : List.of("aa want iron_ore 8", "aa want diamond_pickaxe", "aa drop iron_ore", "aa status", "aa why", "status", "aa", "AA  Want bed high"))
            assertEquals(CommandGate.Kind.RUN, gate.check(v).kind(), v);
        for (String v : List.of("say hello", "inv", "dig", "house house", "brain status", "brain set x 1", "fence", "area list", "mine iron", "defence on",
                "help dig", "restart", "server on", "allow bob", "give bob diamond", "confirm", "ai off", "idle", "aa on", "aa off", "aa like build 30",
                "goto 1 64 2", "queue aa want x", "repeat 3 status"))
            assertEquals(CommandGate.Kind.DENIED, gate.check(v).kind(), v);
        CommandGate.Verdict d = gate.check("dig");
        assertTrue(d.error().contains("aa want"), "the refusal tells the model what it may send: " + d.error());
    }

    @Test
    void chainsAreCheckedStepByStep() {
        assertEquals(CommandGate.Kind.RUN, gate.check("status then aa why").kind());
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
        assertTrue(gate.check("baritone mine diamond_ore").failed());
    }

    @Test
    void extraAllowCannotOpenHardDeny() {
        CommandGate g = new CommandGate(List.of("rule", "restart", "allow", "fence mode", "confirm"), List.of("say"));
        assertEquals(CommandGate.Kind.DENIED, g.check("rule list").kind(), "ai.json cannot open anything");
        for (String v : List.of("restart", "allow bob", "fence mode log", "confirm")) assertEquals(CommandGate.Kind.DENIED, g.check(v).kind(), v);
        assertEquals(CommandGate.Kind.DENIED, g.check("say hi").kind(), "extraDeny");
    }
}