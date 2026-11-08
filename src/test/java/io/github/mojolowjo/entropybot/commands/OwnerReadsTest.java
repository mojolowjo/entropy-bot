package io.github.mojolowjo.entropybot.commands;

import baritone.api.process.Reserves;
import io.github.mojolowjo.entropybot.aa.StationBook;
import io.github.mojolowjo.entropybot.aa.moves.Reservations;
import io.github.mojolowjo.entropybot.aa.trail.Trail;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The owner's reads (aa stations, aa trail, aa routes, aa reserved) on hand-made state, in their text form. */
class OwnerReadsTest {
    private static final int JUMP = 40;

    @Test
    void stationsListsMineSeenAndGoneAndForgets() {
        StationBook b = new StationBook();
        long now = 10_000_000L;
        assertTrue(OwnerReads.stations("", b, now).startsWith("stations: none"), "empty book says none");
        b.placed("crafting_table", 1, 64, 2, now - 240_000);
        b.seen("minecraft:furnace", 5, 64, 6, false, now - 20_000);
        b.gone(5, 64, 6, "the block there is air now", now - 5_000);
        String t = OwnerReads.stations("", b, now);
        assertEquals("stations: 2 in the book\n"
                + "  crafting_table at 1 64 2: mine, there, last seen 4 min ago\n"
                + "  furnace at 5 64 6: seen, gone (the block there is air now), last seen 5 s ago", t);
        assertTrue(OwnerReads.stations("forget 1 64 2", b, now).startsWith("stations: forgot the crafting_table at 1 64 2"));
        assertEquals(1, b.all().size());
        assertTrue(OwnerReads.stations("forget 1 64 2", b, now).startsWith("stations: nothing in the book at 1 64 2"));
        assertTrue(OwnerReads.stations("forget 1 2", b, now).startsWith("usage:"));
    }

    @Test
    void trailTellsStateSegmentsAndPrice() {
        assertTrue(OwnerReads.trailText(null, null, "x").startsWith("trail: not readable"));
        Trail t = new Trail();
        assertTrue(OwnerReads.trailText(t, null, "x").startsWith("trail: not loaded"), "rule 7: not loaded is not none");
        t.loadedEmpty();
        String closed = OwnerReads.trailText(t, null, "x");
        assertTrue(closed.startsWith("trail: CLOSED, start none, 0 segments"), closed);
        assertTrue(closed.contains("segments: none"), closed);
        t.offer(new Trail.Cell(0, 64, 0), 0, true, JUMP);
        t.offer(new Trail.Cell(1, 64, 0), 20, false, JUMP);
        t.offer(new Trail.Cell(5, 64, 0), 100, false, JUMP);
        t.offer(new Trail.Cell(5, 62, 0), 140, false, JUMP);
        t.offer(new Trail.Cell(9, 62, 0), 220, false, JUMP);
        Trail.Price p = t.price(new Trail.Climb(2, 3), 240);
        String s = OwnerReads.trailText(t, p, null);
        assertTrue(s.startsWith("trail: OPEN, start 0 64 0, 2 segments"), s);
        assertTrue(s.contains("  1: 0 64 0 -> 5 64 0, walk, 5 s"), s);
        assertTrue(s.contains("  2: 5 64 0 -> 5 62 0, one-way drop 2 (2 cells), 2 s"), s);
        assertTrue(s.contains("home by the trail: 2 segments"), s);
        assertTrue(OwnerReads.trailText(t, null, "no player").contains("not priced (no player)"), "no price: the reason, not silence");
        assertTrue(t.clear("cleared by the owner").startsWith("trail: cleared, cleared by the owner (2 segments dropped)"));
        assertTrue(OwnerReads.trailText(t, null, "x").contains("segments: none"));
    }

    @Test
    void routesNumberedFromOneAndNotReadableSaid() {
        assertEquals(OwnerReads.ROUTES_MISSING, OwnerReads.routesText(null));
        assertTrue(OwnerReads.routesText(List.of()).startsWith("routes: none"));
        String t = OwnerReads.routesText(List.of("to GoalBlock{x=1,y=2,z=3} from 0 64 0, 12 cells, 80 ticks", "to GoalNear{x=9,y=9,z=9} from 4 64 4, 7 cells, 40 ticks"));
        assertEquals("routes: 2 stored by the fork\n  1: to GoalBlock{x=1,y=2,z=3} from 0 64 0, 12 cells, 80 ticks\n  2: to GoalNear{x=9,y=9,z=9} from 4 64 4, 7 cells, 40 ticks", t);
        assertTrue(OwnerReads.routes("forget x").startsWith("usage:"));
        assertTrue(OwnerReads.routes("bogus").startsWith("usage:"));
    }

    @Test
    void reservedOneLinePerClaim() {
        assertTrue(OwnerReads.reservedText(Reservations.NONE).startsWith("reserved: none"));
        assertTrue(OwnerReads.reservedText(null).startsWith("reserved: not readable"));
        Reservations r = new Reservations(List.of(
                Reserves.Reserve.of("minecraft:stick", List.of("minecraft:stick"), 2, Reserves.Kind.CONSUME, 3, "craft stone_pickaxe 1"),
                Reservations.order("cobblestone", List.of("cobblestone"), 64)), "step 5 (smelt iron): no member of logs in the tag index - not counted", null);
        assertEquals("reserved: 2 claims on the bag\n  stick 2 for craft stone_pickaxe 1\n  cobblestone 64 for order cobblestone\n"
                + "  not loaded: step 5 (smelt iron): no member of logs in the tag index - not counted", OwnerReads.reservedText(r));
    }
}
