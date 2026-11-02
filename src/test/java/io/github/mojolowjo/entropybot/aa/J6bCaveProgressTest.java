package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepMonitor;
import io.github.mojolowjo.entropybot.aa.moves.StepSignals;
import io.github.mojolowjo.entropybot.cave.MineRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round 17 package (2026-11-02; the owner: the caving step clears the cave and notes every ore it sees, it never leaves early
 * on a poor count). Three parts: (1) run HF 01:15-01:18: the caving step ended "no progress 30 s" while it walked branch to
 * branch seeing new cells - the record's cells seen are its progress signal for the monitor; (2) run HD (round 16): the
 * "mining the seen ore" phase sat 7 min on an ore Baritone could not reach - a vein's own no-progress clock; (3) the table
 * placement failures (2-4 per run) get a site probe, the fix waits for it.
 */
class J6bCaveProgressTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
    }

    static final int[] FEET = {43, 24, -29};
    static final Step CAVE = new Step.Cave(44, 62, 40, "iron", 6, 10);

    /** The caving step's signals: the goal changes every few seconds, the feet never farther than the start, the cells seen as given. */
    static StepSignals caveSig(int second, int seen) {
        return new StepSignals(true, null, FEET, 50, "branch" + (second / 4), false, true, false, 0, -1, -1, null, 0, seen, null, null);
    }

    /** (1) Run HF: a caving step that keeps seeing new cells is never a stall; one that sees none for 30 s is. */
    @Test
    void theCavingStepsCellsSeenAreItsProgress() {
        StepMonitor m = new StepMonitor();
        m.begin(CAVE, caveSig(0, 6557), 0);
        StepMonitor.Look l = null;
        for (int s = 1; s <= 90; s++) {
            l = m.look(caveSig(s, 6557 + 40 * s), s * 20L);
            assertEquals(StepMonitor.Verdict.CONTINUE, l.v(), "at " + s + " s: " + l.reason());
        }
        assertTrue(l.reason().startsWith("explored 40 new chunk(s) / cave cell(s)"), l.reason());
        StepMonitor frozen = new StepMonitor();
        frozen.begin(CAVE, caveSig(0, 6557), 0);
        int at = -1;
        for (int s = 1; s <= 90 && at < 0; s++) if (frozen.look(caveSig(s, 6557), s * 20L).v() == StepMonitor.Verdict.REPLAN) at = s;
        assertTrue(at >= 30 && at <= 32, "the stall at 30 s with the count frozen, was " + at);
    }

    /** (1) The hands pass the record's cells seen to the live read for the caving step only. */
    @Test
    void theHandsPassTheRecordsCellsSeen() throws IOException {
        String hands = Files.readString(Path.of("src/main/java/io/github/mojolowjo/entropybot/commands/StepHands.java"));
        assertTrue(hands.contains("return live.read(nl, ownWait(s), caveSeen(s));"), "the one live read takes the cave's count");
        assertTrue(hands.contains("if (!(s instanceof Step.Cave k) || cv == null) return -1;"), "-1 for every other step (rule 15)");
        assertTrue(hands.contains("return f == null ? -1 : f.cellCount() - f.unseen();"), "the record's cells seen");
        String live = Files.readString(Path.of("src/main/java/io/github/mojolowjo/entropybot/commands/StepSignalsLive.java"));
        assertTrue(live.contains("explores ? Mining.get().exploredChunks() : caveSeen"), "the explore's chunks, else the cave's cells");
    }

    /** (2) Run HD: the vein watch - feet, gone count, a break stage are progress; nothing for the stall ticks is the stall. */
    @Test
    void theVeinWatchStallsOnNoFeetNoGoneNoBreak() {
        MineRules.VeinWatch w = new MineRules.VeinWatch();
        int[] feet = {-86, 16, -101};
        assertFalse(w.stalled(0, feet, 0, -1, 600), "the first look sets the clock");
        for (long t = 20; t < 600; t += 20) assertFalse(w.stalled(t, feet, 0, -1, 600), "quiet but under the bound at " + t);
        assertTrue(w.stalled(600, feet, 0, -1, 600), "30 s with nothing moved");
        assertEquals(30, w.quietS(600));
        MineRules.VeinWatch moved = new MineRules.VeinWatch();
        moved.stalled(0, feet, 0, -1, 600);
        assertFalse(moved.stalled(580, new int[]{-85, 16, -101}, 0, -1, 600), "the feet on another cell: the clock starts again");
        assertFalse(moved.stalled(1170, new int[]{-85, 16, -101}, 0, -1, 600));
        assertTrue(moved.stalled(1180, new int[]{-85, 16, -101}, 0, -1, 600));
        MineRules.VeinWatch gone = new MineRules.VeinWatch();
        gone.stalled(0, feet, 0, -1, 600);
        assertFalse(gone.stalled(590, feet, 1, -1, 600), "a block of the vein gone");
        assertFalse(gone.stalled(1180, feet, 1, -1, 600));
        MineRules.VeinWatch breaking = new MineRules.VeinWatch();
        breaking.stalled(0, feet, 0, -1, 600);
        assertFalse(breaking.stalled(590, feet, 0, 3, 600), "a break under way (stage 3)");
        assertFalse(breaking.stalled(1180, feet, 0, -1, 600));
        assertTrue(breaking.stalled(1190, feet, 0, -1, 600));
    }

    /** (2) VeinMine.poll reads the watch once; both vein callers (the per-ore step, the caving step) poll with the player. */
    @Test
    void theVeinMineReadsTheWatch_andTheCallersPassThePlayer() throws IOException {
        String vein = Files.readString(Path.of("src/main/java/io/github/mojolowjo/entropybot/commands/VeinMine.java"));
        assertTrue(vein.contains("Car.Status poll(LocalPlayer p) {"));
        assertEquals(1, count(vein, "watch.stalled(tick, feet, gone, StepSignalsLive.destroyStage(p), Math.round(20 * Tuning.get(\"monitor.stallS\")))"), "the one read, the monitor's stall bound, the one stage read");
        assertTrue(vein.contains("\": no progress for \" + watch.quietS(tick) + \" s: the feet still at \""), "said with the reason");
        String hands = Files.readString(Path.of("src/main/java/io/github/mojolowjo/entropybot/commands/StepHands.java"));
        assertEquals(1, count(hands, "Car.Status st = vein.poll(p);"), "the per-ore step");
        assertEquals(1, count(hands, "Car.Status st = cv.vein.poll(p);"), "the caving step");
        assertFalse(hands.contains(".poll(p.level())") || vein.contains(".poll(p.level())"), "no caller polls on the level alone");
    }

    /** (3) Both place-near failure sites say the site through the one probe helper, behind the step probe. */
    @Test
    void thePlaceSiteProbeIsSaidAtBothFailureSites() throws IOException {
        String hands = Files.readString(Path.of("src/main/java/io/github/mojolowjo/entropybot/commands/StepHands.java"));
        assertEquals(1, count(hands, "placeSiteProbe(p, n.item(), r.why());"), "the start's failure");
        assertEquals(1, count(hands, "if (pl != null) placeSiteProbe(pl, n.item(), r.why());"), "the status's failure");
        int from = hands.indexOf("private static void placeSiteProbe(");
        assertTrue(from > 0);
        String body = hands.substring(from, hands.indexOf("probe place site:", from));
        assertTrue(body.contains("if (!StepProbe.on()) return;"), "behind debug.stepProbe");
        assertEquals(1, count(hands, "probe place site:"), "one line");
    }

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) n++;
        return n;
    }
}
