package io.github.mojolowjo.entropybot.blind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlindModeTest {
    @TempDir
    Path dir;

    @Test
    void onOffStatusAndSince() {
        BlindMode m = new BlindMode();
        assertFalse(m.enabled());
        assertTrue(m.status(10, 0).startsWith("blind mode: off"));
        assertTrue(m.set(true, 5000));
        assertFalse(m.set(true, 6000), "already on");
        assertEquals(5000, m.since());
        String s = m.status(10, 15000);
        assertTrue(s.startsWith("blind mode: on since "), s);
        assertTrue(s.contains("(10 s)"), s);
        assertTrue(s.contains("frames skipped 0") && s.contains("title on") && s.contains("sound pause") && s.contains("maxFps 10"), s);
        assertFalse(s.contains("options.txt"), "no hint at 10");
        assertTrue(m.set(false, 20000));
        assertEquals(0, m.since());
    }

    @Test
    void framesSkippedFromDeltas() {
        BlindMode m = new BlindMode();
        m.frames(100);
        assertEquals(0, m.framesSkipped(), "off: nothing counted");
        m.set(true, 1);
        m.frames(1000);             // the base
        assertEquals(0, m.framesSkipped());
        m.frames(1010);
        m.frames(1030);
        assertEquals(30, m.framesSkipped());
        m.frames(5);                // went backwards: new base
        m.frames(15);
        assertEquals(40, m.framesSkipped());
        m.set(false, 2);
        m.frames(100);
        assertEquals(40, m.framesSkipped(), "kept after off");
        m.set(true, 3);
        assertEquals(0, m.framesSkipped(), "a new blind run starts at 0");
    }

    @Test
    void configLoadSaveAndBroken() throws Exception {
        BlindMode m = new BlindMode();
        BlindMode.Config c = m.load(dir, 1);
        assertEquals(BlindMode.Config.DEFAULT, c, "missing file = defaults");
        assertNull(m.configProblem());
        m.set(true, 2);
        assertEquals("", m.save(dir));
        assertFalse(Files.exists(dir.resolve("blind.json.tmp")));
        BlindMode n = new BlindMode();
        n.load(dir, 3);
        assertTrue(n.enabled(), "enabled applies from the file");
        assertTrue(n.config().title());
        assertEquals("pause", n.config().sound());

        Files.writeString(dir.resolve("blind.json"), "{\"enabled\":false,\"title\":false,\"sound\":\"off\"}");
        n.load(dir, 4);
        assertFalse(n.enabled());
        assertFalse(n.config().title());
        assertFalse(n.config().pauseSound());

        Files.writeString(dir.resolve("blind.json"), "{ not json");
        BlindMode b = new BlindMode();
        b.load(dir, 5);
        assertEquals(BlindMode.Config.DEFAULT, b.config());
        assertFalse(b.enabled());
        assertNotNull(b.configProblem());
        assertTrue(b.status(10, 6).contains("broken"));
        Files.writeString(dir.resolve("blind.json"), "{\"enabled\":\"yes\"}");
        b.load(dir, 7);
        assertNotNull(b.configProblem(), "a wrong type is broken too");
        Files.writeString(dir.resolve("blind.json"), "{\"sound\":\"loud\"}");
        b.load(dir, 8);
        assertNotNull(b.configProblem());
        Files.writeString(dir.resolve("blind.json"), "[]");
        b.load(dir, 9);
        assertNotNull(b.configProblem());
    }

    @Test
    void title() {
        assertEquals("Entropy Bot 0Entropy1 | blind | 10 64 -20 | mine | 20 tps | 10 fps",
                BlindMode.title("0Entropy1", 10, 64, -20, "mine", 20, 10));
        assertEquals("Entropy Bot 0Entropy1 | blind | 1 2 3 | idle | 19 tps | 9 fps",
                BlindMode.title("0Entropy1", 1, 2, 3, null, 19, 9));
        assertTrue(BlindMode.title("x", 0, 0, 0, "  ", 20, 10).contains("| idle |"));
    }

    @Test
    void watchConflict() {
        assertNull(BlindMode.watchRefusal(false, ""));
        assertEquals("blind mode is on - watch needs rendering (blind off first)", BlindMode.watchRefusal(true, ""));
        for (String s : List.of("on", "tunnel", "distance 4", "cut on"))
            assertEquals(BlindMode.WATCH_REFUSED, BlindMode.watchRefusal(true, s), s);
        for (String s : List.of("off", "stop", "status", "tunnel status", "tunnel off"))
            assertNull(BlindMode.watchRefusal(true, s), s);
        assertTrue(BlindMode.WATCH_TURNED_OFF.contains("watch view is off"));
    }

    @Test
    void maxFpsHint() {
        assertEquals("", BlindMode.fpsHint(20));
        assertEquals("", BlindMode.fpsHint(10));
        assertTrue(BlindMode.fpsHint(60).contains("set maxFps 10-20 in options.txt"));
        BlindMode m = new BlindMode();
        assertTrue(m.status(260, 0).contains("maxFps 260 (set maxFps 10-20"));
    }

    @Test
    void noRenderResetFinding() {
        BlindMode m = new BlindMode();
        assertFalse(m.noRenderFound(false), "off: never");
        m.set(true, 1);
        assertFalse(m.noRenderFound(false));
        assertFalse(m.noRenderFound(true), "a good tick breaks the streak");
        assertFalse(m.noRenderFound(false));
        assertFalse(m.noRenderFound(false));
        assertTrue(m.noRenderFound(false), "3 in a row");
        assertTrue(m.resetSeen());
        assertFalse(m.noRenderFound(false), "reported once per streak");
    }

    @Test
    void stateJson() {
        BlindMode m = new BlindMode();
        m.set(true, 42);
        var o = m.stateJson();
        assertTrue(o.get("on").getAsBoolean());
        assertEquals(42, o.get("since").getAsLong());
        assertEquals(0, o.get("framesSkipped").getAsLong());
    }

    @Test
    void dashboardProject() {
        String fj = "{\"keyFile\":\"C:\\\\bots\\\\minecraft-bot\\\\dashboard\\\\key.txt\",\"port\":8766}";
        DashboardControl.Project p = DashboardControl.fromFastJson(fj, x -> true);
        assertTrue(p.ok());
        assertEquals(Path.of("C:\\bots\\minecraft-bot"), p.dir());
        assertEquals(Path.of("C:\\bots\\minecraft-bot\\bridge.ps1"), p.script());
        assertTrue(DashboardControl.fromFastJson(fj, x -> false).problem().startsWith("no bridge.ps1"));
        assertEquals("fast.json has no keyFile", DashboardControl.fromFastJson("{}", x -> true).problem());
        assertEquals("fast.json is not a JSON object", DashboardControl.fromFastJson("nope{", x -> true).problem());
        assertTrue(DashboardControl.find(dir).problem().startsWith("no entropybot\\fast.json"));
        assertEquals(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", p.script().toString(), "dashboard", "stop"),
                DashboardControl.command(p.script(), true));
        assertEquals("http://localhost:8765/?key=****", DashboardControl.maskedLink("localhost", 8765));
        assertTrue(DashboardControl.statusLine(true, "localhost").contains("?key=****"));
        assertTrue(DashboardControl.statusLine(false, "localhost").contains("not running"));
    }
}
