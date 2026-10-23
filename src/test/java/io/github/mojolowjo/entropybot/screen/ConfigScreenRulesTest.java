package io.github.mojolowjo.entropybot.screen;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConfigScreenRulesTest {
    // ---- allowed players ----

    @Test
    void emptyListAllowsNobodyInChat() {
        Set<String> told = new HashSet<>();
        assertEquals(AllowRule.Verdict.REFUSE_SAY, AllowRule.pm("mojolowjo", List.of(), told));
        assertFalse(AllowRule.onList("mojolowjo", List.of()));
        assertTrue(AllowRule.describe(List.of()).startsWith("nobody in chat"));
    }

    @Test
    void listedNameMayPm() {
        assertEquals(AllowRule.Verdict.ALLOW, AllowRule.pm("Steve", List.of("alex", "steve"), new HashSet<>()));
    }

    @Test
    void unlistedHearsNotAllowedOnce() {
        Set<String> told = new HashSet<>();
        assertEquals(AllowRule.Verdict.REFUSE_SAY, AllowRule.pm("Griefer", List.of("alex"), told));
        told.add("griefer");
        assertEquals(AllowRule.Verdict.REFUSE_QUIET, AllowRule.pm("Griefer", List.of("alex"), told));
        assertTrue(AllowRule.REFUSAL.contains("not allowed"));
    }

    @Test
    void parseNames() {
        List<String> bad = new ArrayList<>();
        assertEquals(List.of("alex", "Steve_2"), AllowRule.parse("alex, Steve_2 ALEX x !bad!", bad));
        assertEquals(List.of("x", "!bad!"), bad);
    }

    // ---- I drive ----

    @Test
    void driveEnterReleasesEverythingAndLeaveReappliesTheModsSettings() {
        DriveState d = new DriveState();
        assertNull(d.aaOnRefusal());
        List<DriveState.Action> in = d.enter(true, true);
        assertEquals(List.of(DriveState.Action.AA_OFF, DriveState.Action.STOP_ALL, DriveState.Action.RELEASE_KEYS, DriveState.Action.WATCH_OFF,
                DriveState.Action.BLIND_OFF, DriveState.Action.MOUSE_GRAB, DriveState.Action.REFLEXES_PAUSE), in);
        assertTrue(d.on());
        assertNotNull(d.aaOnRefusal());
        assertTrue(d.enter(true, true).isEmpty(), "a second enter does nothing");
        assertEquals(List.of(DriveState.Action.MOUSE_FREE, DriveState.Action.BLIND_ON, DriveState.Action.REFLEXES_RESUME), d.leave());
        assertFalse(d.on());
        assertNull(d.aaOnRefusal());
        assertTrue(d.leave().isEmpty());
    }

    @Test
    void driveOnlyPutsBackWhatWasTheMods() {
        DriveState d = new DriveState();
        List<DriveState.Action> in = d.enter(false, false);
        assertFalse(in.contains(DriveState.Action.MOUSE_GRAB));
        assertFalse(in.contains(DriveState.Action.BLIND_OFF));
        assertEquals(List.of(DriveState.Action.REFLEXES_RESUME), d.leave());
    }

    // ---- command box ----

    @Test
    void commandBoxUsesTheSameDispatchAsBot() {
        List<String> sent = new ArrayList<>();
        assertNull(CommandBox.submit("  aa status ", sent::add));
        assertNull(CommandBox.submit("/bot aa want torch 8", sent::add));
        assertNull(CommandBox.submit("/aa on", sent::add));
        assertEquals(List.of("aa status", "aa want torch 8", "aa on"), sent);
        assertNotNull(CommandBox.submit("   ", sent::add));
        assertEquals(3, sent.size());
        assertTrue(CommandBox.REPLIES.lines().contains("> aa on"));
    }

    @Test
    void ringKeepsTheLastLines() {
        CommandBox.Ring r = new CommandBox.Ring(2);
        r.add("a"); r.add("b"); r.add("c");
        assertEquals(List.of("b", "c"), r.lines());
    }

    // ---- dashboard ----

    @Test
    void dashboardStatusFromPidFile() {
        assertEquals("dashboard: off", DashboardProcess.statusText(null, null, false));
        assertEquals("dashboard: running on port 8765 (pid 42)", DashboardProcess.statusText(null, 42L, true));
        assertTrue(DashboardProcess.statusText(null, 42L, false).contains("gone"));
        assertTrue(DashboardProcess.statusText("no Dashboard.java in x", null, false).endsWith("no Dashboard.java in x"));
        assertEquals(42L, DashboardProcess.readPid(" 42\r\n"));
        assertNull(DashboardProcess.readPid("x"));
        assertNull(DashboardProcess.readPid(null));
    }

    @Test
    void dashboardFolderJavaAndCommand() {
        Path d = Path.of("C:\\bots\\dashboard");
        assertNull(DashboardProcess.folderProblem(d, p -> true));
        assertTrue(DashboardProcess.folderProblem(d, p -> false).startsWith("no Dashboard.java"));
        assertTrue(DashboardProcess.folderProblem(null, p -> true).startsWith("no dashboard folder"));
        assertTrue(DashboardProcess.javaProblem(Path.of("j"), p -> false).startsWith("java not found"));
        assertEquals(List.of("j", "Dashboard.java", Path.of("g").toString(), "8765"), DashboardProcess.command(Path.of("j"), Path.of("g"), 8765));
        assertEquals("http://h:8765/?key=k", DashboardProcess.link("h", 8765, "k"));
    }

    // ---- order end ----

    @Test
    void orderEndLine() {
        assertEquals("aa scan done - 3 chests", OrderEnd.line("aa scan", OrderEnd.kind(true, false, "3 chests"), "3 chests"));
        assertEquals(OrderEnd.End.STOPPED, OrderEnd.kind(false, true, "aa off"));
        assertEquals(OrderEnd.End.PARKED, OrderEnd.kind(false, false, "parked: no path"));
        assertEquals("aa eat failed - no food", OrderEnd.line("aa eat", OrderEnd.kind(false, false, "no food"), "no food"));
    }
}
