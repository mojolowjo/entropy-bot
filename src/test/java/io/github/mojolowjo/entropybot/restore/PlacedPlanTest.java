package io.github.mojolowjo.entropybot.restore;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** aa-litter: which ledger entries "restore placed" takes down, the kept notes, the words. */
class PlacedPlanTest {
    static final String DIM = "minecraft:overworld";

    static Ledger.Entry add(Ledger l, int x, int y, int z, String why) {
        Ledger.Entry e = l.add(x, y, z, DIM, "minecraft:cobblestone", "minecraft:cobblestone", List.of(), why, 1, "walk", why, 1, 1);
        e.pending = false;
        return e;
    }

    @Test void onlyStandingLitterNearestFirst() {
        Ledger l = new Ledger();
        add(l, 0, 70, 0, PlacedPlan.LITTER);
        add(l, 0, 66, 0, PlacedPlan.LITTER);
        add(l, 0, 68, 0, PlacedPlan.LITTER);
        add(l, 1, 64, 0, "built: station");                                  // a station is not litter
        add(l, 2, 64, 0, PlacedPlan.keptWhy("bridge", 2, 64, 0, "reuse"));    // a kept structure is never taken down
        add(l, 3, 64, 0, Ledger.PATH);                                       // a broken block is the restore's, not ours
        RestorePlan.Plan p = PlacedPlan.plan(l.confirmed(), new int[]{0, 64, 0}, DIM, 24);
        assertEquals(3, p.actions().size());
        assertEquals(66, p.actions().get(0).entry().y);                      // the foot of the column first
        assertEquals(68, p.actions().get(1).entry().y);
        assertEquals(70, p.actions().get(2).entry().y);
        assertTrue(p.left().isEmpty());
    }

    @Test void farOnesAndOtherDimensionsAreLeft() {
        Ledger l = new Ledger();
        add(l, 100, 64, 0, PlacedPlan.LITTER);
        add(l, 2, 64, 0, PlacedPlan.LITTER);
        Ledger.Entry nether = l.add(1, 64, 1, "minecraft:the_nether", "minecraft:netherrack", "minecraft:netherrack", List.of(), PlacedPlan.LITTER, 1, "walk", PlacedPlan.LITTER, 1, 1);
        nether.pending = false;
        RestorePlan.Plan p = PlacedPlan.plan(l.confirmed(), new int[]{0, 64, 0}, DIM, 24);
        assertEquals(1, p.actions().size());
        assertEquals(2, p.left().size());
        assertEquals("too far", p.left().get(0).why());
    }

    @Test void pendingLitterWaitsForTheLedgerToConfirmIt() {
        Ledger l = new Ledger();
        l.add(0, 64, 0, DIM, "minecraft:cobblestone", "minecraft:cobblestone", List.of(), PlacedPlan.LITTER, 1, "walk", PlacedPlan.LITTER, 1, 1);
        assertTrue(PlacedPlan.plan(l.all(), new int[]{0, 64, 0}, DIM, 24).actions().isEmpty());
    }

    @Test void restorePlanNeverReplacesLitterOrKept() {
        Ledger l = new Ledger();
        add(l, 0, 64, 0, PlacedPlan.LITTER);
        add(l, 1, 64, 0, PlacedPlan.keptWhy("pillar", 1, 64, 0, null));
        RestorePlan.Plan p = RestorePlan.plan(l.confirmed(), new int[]{0, 64, 0}, DIM, 24, java.util.Map.of("minecraft:cobblestone", 9));
        assertTrue(p.actions().isEmpty());
        assertTrue(p.left().isEmpty());
    }

    @Test void keptNoteAndPredicates() {
        String w = PlacedPlan.keptWhy("bridge", 10, 64, -3, "reuse 120 s vs cleanup 40 s");
        assertEquals("built: kept bridge at 10 64 -3 (reuse 120 s vs cleanup 40 s)", w);
        assertTrue(w.startsWith("built:"));                                   // RestorePlan and the tree finder see it as the bot's own
        assertEquals("built: kept structure at 1 2 3", PlacedPlan.keptWhy(" ", 1, 2, 3, ""));
        Ledger l = new Ledger();
        assertTrue(PlacedPlan.kept(add(l, 0, 64, 0, w)));
        assertFalse(PlacedPlan.litter(add(l, 1, 64, 0, w)));
        assertTrue(PlacedPlan.litter(add(l, 2, 64, 0, PlacedPlan.LITTER)));
        assertFalse(PlacedPlan.litter(null));
    }

    @Test void summaryAndFailure() {
        assertNull(PlacedPlan.summary(0, List.of()));
        assertEquals("took down 1 block", PlacedPlan.summary(1, List.of()));
        assertEquals("took down 3 blocks", PlacedPlan.summary(3, new ArrayList<>()));
        Ledger l = new Ledger();
        var left = List.of(new RestorePlan.Left(add(l, 0, 64, 0, PlacedPlan.LITTER), "couldn't reach"));
        assertEquals("took down 2 blocks, 1 left: couldn't reach (restore placed)", PlacedPlan.summary(2, left));
        assertTrue(PlacedPlan.failed(2, left));                               // blocks left standing fail the step (Failures records it)
        assertFalse(PlacedPlan.failed(2, List.of()));
    }

    @Test void litterTextListsStandingAndKept() {
        Ledger l = new Ledger();
        assertEquals("aa litter: nothing placed and left standing", PlacedPlan.text(l.all()));
        add(l, 0, 65, 0, PlacedPlan.LITTER);
        add(l, 0, 66, 0, PlacedPlan.LITTER);
        add(l, 5, 64, 0, PlacedPlan.keptWhy("bridge", 5, 64, 0, "reuse 90 s vs cleanup 40 s"));
        String t = PlacedPlan.text(l.all());
        assertTrue(t.startsWith("aa litter: 2 to take down, 1 kept"), t);
        assertTrue(t.contains("standing: 2 cobblestone (last at 0 66 0"), t);
        assertTrue(t.contains("kept: bridge at 5 64 0 (reuse 90 s vs cleanup 40 s) - 1 block"), t);
    }

    @Test void restoreWordsPlacedAndKeep() {
        assertEquals(RestoreArgs.Kind.PLACED, RestoreArgs.parse("placed").kind());
        assertEquals(PlacedPlan.DEFAULT_RADIUS, RestoreArgs.parse("placed").n());
        assertEquals(40, RestoreArgs.parse("placed 40").n());
        assertEquals(RestoreArgs.Kind.USAGE, RestoreArgs.parse("placed 0").kind());
        assertEquals(RestoreArgs.Kind.USAGE, RestoreArgs.parse("placed 65").kind());
        assertEquals(RestoreArgs.Kind.USAGE, RestoreArgs.parse("placed x").kind());
        assertTrue(RestoreArgs.isJob("placed"));
        assertTrue(RestoreArgs.isJob("now"));
        assertFalse(RestoreArgs.isJob("keep bridge"));
        var k = RestoreArgs.parse("keep bridge");
        assertEquals(RestoreArgs.Kind.KEEP, k.kind());
        assertEquals("bridge", k.mode());
        assertEquals(RestoreArgs.Kind.USAGE, RestoreArgs.parse("keep").kind());
        assertEquals(RestoreArgs.Kind.USAGE, RestoreArgs.parse("keep ;rm").kind());
        assertTrue(RestoreArgs.USAGE.contains("restore placed"));
    }
}
