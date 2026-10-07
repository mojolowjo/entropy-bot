package io.github.mojolowjo.entropybot.restore;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.guard.Box;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** P1: the restore ledger: pending -> confirmed or dropped, the cap, the JSON round trip, restore.json's book. */
class LedgerTest {
    static final String OW = "minecraft:overworld";

    static Ledger.Entry add(Ledger l, int x, String block, String reason, long job, long tick) {
        String why = RestoreRules.whyNot(block, false);
        return l.add(x, 60, 0, OW, block, block + "[]", why == null ? RestoreRules.itemsFor(block) : List.of(), why, job, "mine iron_ore", reason, tick, 1000 + tick);
    }

    @Test
    void aBreakIsPendingUntilTheLevelShowsTheCellEmpty() {
        Ledger l = new Ledger();
        add(l, 1, "minecraft:stone", Ledger.PATH, 7, 100);
        add(l, 2, "minecraft:stone", Ledger.PATH, 7, 100);
        assertEquals(2, l.pending().size());
        assertTrue(l.forJob(7).isEmpty(), "pending entries are not restored yet");
        // too early: nothing looked at
        assertEquals(0, l.settle((d, x, y, z) -> Ledger.Cell.EMPTY, 102));
        // x 1 is empty now, x 2 still full (the server put it back)
        assertEquals(1, l.settle((d, x, y, z) -> x == 1 ? Ledger.Cell.EMPTY : Ledger.Cell.FULL, 106));
        assertEquals(1, l.forJob(7).size());
        assertEquals(1, l.pending().size());
        assertEquals(1, l.settle((d, x, y, z) -> Ledger.Cell.FULL, 100 + Ledger.CONFIRM_MAX));
        assertEquals(1, l.size(), "a break the server undid is dropped");
        assertEquals(1, l.waiting());
    }

    @Test
    void theFirstEntryOfACellIsKept() {
        Ledger l = new Ledger();
        assertNotNull(add(l, 1, "minecraft:stone", Ledger.PATH, 7, 1));
        assertNull(add(l, 1, "minecraft:cobblestone", Ledger.PATH, 8, 2), "the original terrain stays");
        assertEquals("minecraft:stone", l.all().get(0).block);
    }

    @Test
    void theCapDropsTheOldestAndSaysSo() {
        Ledger l = new Ledger(3);
        for (int i = 0; i < 5; i++) add(l, i, "minecraft:dirt", Ledger.PATH, 1, i);
        assertEquals(3, l.size());
        assertEquals(2, l.all().get(0).x, "x 0 and 1 went");
        assertEquals("dropped the 2 oldest restore entries (I keep at most 3)", l.takeNote());
        assertNull(l.takeNote());
    }

    @Test
    void forgetTakesTheOldestConfirmedOrAll() {
        Ledger l = new Ledger();
        for (int i = 0; i < 4; i++) add(l, i, "minecraft:stone", Ledger.ESCAPE, 1, 0);
        l.settle((d, x, y, z) -> Ledger.Cell.EMPTY, 10);
        assertEquals(2, l.forgetOldest(2));
        assertEquals(2, l.all().get(0).x);
        assertEquals(2, l.forgetAll());
        assertEquals(0, l.size());
    }

    @Test
    void roundTripKeepsConfirmedEntriesOnly() {
        Ledger l = new Ledger();
        add(l, 1, "minecraft:deepslate", Ledger.PATH, 42, 0);
        add(l, 2, "minecraft:iron_ore", Ledger.PATH, 42, 0);
        l.settle((d, x, y, z) -> Ledger.Cell.EMPTY, 10);
        add(l, 3, "minecraft:stone", Ledger.ESCAPE, 42, 10);       // still pending: not saved
        JsonObject j = JsonParser.parseString(l.toJson().toString()).getAsJsonObject();
        Ledger back = Ledger.fromJson(j, Ledger.CAP, new StringBuilder());
        assertEquals(2, back.size());
        Ledger.Entry d = back.all().get(0);
        assertEquals(List.of("minecraft:cobbled_deepslate", "minecraft:cobblestone"), d.items);
        assertEquals(42, d.job);
        assertEquals("mine iron_ore", d.jobLabel);
        assertFalse(d.pending);
        Ledger.Entry ore = back.all().get(1);
        assertFalse(ore.restorable());
        assertEquals("an ore", ore.why);
        Ledger.Entry next = add(back, 9, "minecraft:dirt", Ledger.PATH, 1, 0);
        assertTrue(next.seq > ore.seq, "the sequence goes on after a load");
    }

    @Test
    void aBrokenEntryIsSkippedNotFatal() {
        JsonObject o = JsonParser.parseString("{\"entries\":[{\"x\":1},{\"seq\":4,\"x\":1,\"y\":2,\"z\":3,\"items\":[\"minecraft:dirt\"]}]}").getAsJsonObject();
        StringBuilder note = new StringBuilder();
        Ledger l = Ledger.fromJson(o, Ledger.CAP, note);
        assertEquals(1, l.size());
        assertEquals("1 broken entry skipped", note.toString());
    }

    @Test
    void theBookKeepsModeAndHintsAcrossARestart() {
        RestoreBook b = new RestoreBook();
        b.mode = "manual";
        add(b.ledger, 1, "minecraft:stone", Ledger.PATH, 3, 0);
        b.ledger.settle((d, x, y, z) -> Ledger.Cell.EMPTY, 10);
        BuildSpotter.Hint h = new BuildSpotter.Hint(new int[]{10, 64, 20}, new int[]{5, 60, 15, 15, 68, 25}, 12, "build_10_20");
        assertNotNull(b.addHint(h, OW, 5));
        assertNull(b.addHint(h, OW, 6), "once per spot");
        RestoreBook back = RestoreBook.fromJson(JsonParser.parseString(b.toJson().toString()).getAsJsonObject(), null);
        assertEquals("manual", back.mode);
        assertEquals(1, back.ledger.size());
        assertEquals(1, back.openHints(List.of()).size());
        assertEquals("area 5 15 15 25 build_10_20 safe 60 68", back.hints.get(0).command());
        // a protect box over it closes the hint; so does "restore ignore"
        assertTrue(back.openHints(List.of(new Box("house", OW, 0, 0, 0, 30, 100, 30))).isEmpty());
        assertFalse(back.ignore(100, 64, 100));
        assertTrue(back.ignore(10, 64, 20));
        assertTrue(back.openHints(List.of()).isEmpty());
        assertTrue(RestoreBook.fromJson(back.toJson(), null).hints.get(0).ignored);
    }

    @Test
    void breakdownCountsByReasonAndJob() {
        Ledger l = new Ledger();
        add(l, 1, "minecraft:stone", Ledger.PATH, 1, 0);
        add(l, 2, "minecraft:stone", Ledger.PATH, 1, 0);
        l.add(3, 60, 0, OW, "minecraft:stone", null, List.of(RestoreRules.COBBLE), null, 2, "going to 1 2 3", Ledger.ESCAPE, 0, 0);
        l.settle((d, x, y, z) -> Ledger.Cell.EMPTY, 10);
        assertEquals("2 path (mine iron_ore), 1 escape (going to 1 2 3)", l.breakdown());
        assertEquals(Set.of(1L, 2L), Set.of(l.all().get(0).job, l.all().get(2).job));
    }
}
