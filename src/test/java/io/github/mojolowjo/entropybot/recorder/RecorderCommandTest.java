package io.github.mojolowjo.entropybot.recorder;

import io.github.mojolowjo.entropybot.recorder.RecorderCommand.Cmd;
import io.github.mojolowjo.entropybot.recorder.RecorderCommand.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecorderCommandTest {

    static Cmd p(String s) { return RecorderCommand.parse(s); }

    @Test
    void showAndPresets() {
        assertEquals(Kind.SHOW, p("").kind());
        assertEquals(Kind.SHOW, p("  status ").kind());
        for (String pr : RecorderSettings.PRESETS) {
            assertEquals(Kind.PRESET, p(pr).kind());
            assertEquals(pr, p(pr.toUpperCase()).word());
        }
    }

    @Test
    void timedBoosts() {
        assertEquals(new Cmd(Kind.BOOST, "detailed", 30), p("detailed for 30m"));
        assertEquals(new Cmd(Kind.BOOST, "max", 120), p("max for 2h"));
        assertEquals(new Cmd(Kind.BOOST, "light", 5), p("light  for 5 minutes"));
        assertEquals(Kind.ERROR, p("off for 30m").kind());
        assertEquals(Kind.ERROR, p("max for 0m").kind());
        assertEquals(Kind.ERROR, p("max for 25h").kind());
    }

    @Test
    void valuesAndTheirLimits() {
        assertEquals(new Cmd(Kind.RANGE, null, 6), p("range 6"));
        assertEquals(Kind.ERROR, p("range 0").kind());
        assertEquals(Kind.ERROR, p("range lots").kind());
        assertEquals(new Cmd(Kind.TRAIL, null, 20), p("trail 20"));
        assertEquals(new Cmd(Kind.TRAIL, null, 20), p("trail 1s"));
        assertEquals(new Cmd(Kind.TRAIL, null, 10), p("trail 0.5s"));
        assertEquals(new Cmd(Kind.TRAIL, null, 200), p("trail 10s"));
        assertEquals(new Cmd(Kind.TRAIL, null, 1), p("trail 1 tick"));
        assertEquals(Kind.ERROR, p("trail 11s").kind());
        assertEquals(Kind.ERROR, p("trail 0").kind());
        assertEquals(Kind.ERROR, p("trail 1.5").kind());
        assertEquals(new Cmd(Kind.SNAPSHOT, null, 8), p("snapshot 8"));
        assertEquals(Kind.SNAPSHOT_NOW, p("snapshot now").kind());
        assertEquals(Kind.ERROR, p("snapshot 33").kind());
        assertEquals(Kind.ERROR, p("snapshot 1").kind());
        assertEquals(new Cmd(Kind.STATES, null, 1), p("states on"));
        assertEquals(new Cmd(Kind.STATES, null, 0), p("states off"));
        assertEquals(Kind.ERROR, p("states maybe").kind());
        assertEquals(new Cmd(Kind.KEEP, null, 48), p("keep 48"));
        assertEquals(new Cmd(Kind.KEEP, null, 72), p("keep 3d"));
        assertEquals(new Cmd(Kind.KEEP, null, 6), p("keep 6h"));
        assertEquals(Kind.ERROR, p("keep 0").kind());
        assertEquals(Kind.ERROR, p("keep 31d").kind());
    }

    @Test
    void marksKeepTheirCase() {
        assertEquals(new Cmd(Kind.MARK, "Starting the DIG test", 0), p("mark Starting the DIG test"));
        assertEquals(Kind.ERROR, p("mark").kind());
        assertEquals(200, p("mark " + "x".repeat(300)).word().length());
    }

    @Test
    void unknownWordsGetTheUsage() {
        Cmd c = p("explode");
        assertEquals(Kind.ERROR, c.kind());
        assertTrue(c.word().startsWith("usage: recorder"));
    }

    static final class Fake implements Recorder, RecorderCommand.Controls {
        final List<Cmd> got = new ArrayList<>();

        @Override
        public String run(Cmd cmd) {
            got.add(cmd);
            return "ok: " + cmd.kind();
        }
    }

    @Test
    void ownerOnlyAndNotInstalled() {
        Fake f = new Fake();
        assertEquals("only mojolowjo can use the recorder", RecorderCommand.handle(f, "max", false, "mojolowjo"));
        assertTrue(f.got.isEmpty());
        assertEquals("the recorder is not installed", RecorderCommand.handle(Recorder.NONE, "max", true, "mojolowjo"));
        assertEquals("ok: PRESET", RecorderCommand.handle(f, "max", true, "mojolowjo"));
        assertTrue(RecorderCommand.handle(f, "range 99", true, "mojolowjo").startsWith("error: range is"));
        assertEquals(1, f.got.size(), "an error never reaches the recorder");
    }
}
