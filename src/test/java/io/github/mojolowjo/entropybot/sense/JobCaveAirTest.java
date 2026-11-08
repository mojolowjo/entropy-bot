package io.github.mojolowjo.entropybot.sense;

import io.github.mojolowjo.entropybot.cave.BandPrice;
import io.github.mojolowjo.entropybot.cave.Tunnel;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Job-caveair (run JBA 08:17:12-08:19:05): the air a tunnel broke into is handed to the cave index (a sealed room becomes a
 * known cave with the tunnel as its lip; too few cells is a pocket and the tunnel goes on); one air cell holds no other
 * heading; the band way never offers a peak above the surface (y 232 from y 16 under a surface of y 63).
 */
class JobCaveAirTest {

    @Test
    void sealedRoom_handedOver_isAKnownCave() {
        CaveIndex i = J6CaveIndexTest.index();
        int b = i.volumeAt(11, 21, 11);
        assertFalse(i.isCave(b), "B has no sky opening: the rays never make it known (the run's cause)");
        CaveIndex.Lip lip = i.lipCredit(11, 21, 11, 40);
        assertEquals(CaveIndex.Credit.KNOWN, lip.what());
        assertEquals(80, lip.cells());
        assertNotNull(lip.known());
        assertEquals(List.of(11, 21, 11), List.of(lip.known().x(), lip.known().y(), lip.known().z()), "entered at the air the tunnel saw");
        assertEquals(Tunnel.Air.CAVE, Tunnel.air(lip.what(), 1, 40));
        assertEquals(CaveIndex.Credit.ALREADY_KNOWN, i.lipCredit(12, 22, 12, 40).what(), "one fact: told once");
        Sightings sg = new Sightings();
        assertNotNull(sg.knowCave("overworld", lip.known(), 0L), "stored in the one store");
        assertEquals(1, sg.allKnownCaves("overworld").size());
    }

    @Test
    void fewCells_isAPocket_theTunnelGoesOn() {
        CaveIndex i = J6CaveIndexTest.index();
        CaveIndex.Lip lip = i.lipCredit(11, 21, 11, 100);
        assertEquals(CaveIndex.Credit.DIP, lip.what());
        assertNull(lip.known());
        assertEquals(Tunnel.Air.POCKET, Tunnel.air(lip.what(), 200, 100));
        assertEquals("air at 11 21 11 is a pocket (80 cells): tunnelling on", Tunnel.pocketText(new int[]{11, 21, 11}, lip.cells()));
        assertEquals(Tunnel.Air.POCKET, Tunnel.air(CaveIndex.Credit.NO_VOLUME, 3, 40), "not indexed, a small local flood: a pocket");
        assertEquals(Tunnel.Air.NOT_INDEXED, Tunnel.air(CaveIndex.Credit.NO_VOLUME, 41, 40));
    }

    @Test
    void oneAirCell_holdsNoOtherHeading() {
        // the told air is in every later tunnel's known set: no other heading ends on it, and it is no held heading
        Set<Long> told = new HashSet<>();
        told.add(Tunnel.key(-127, 12, -37));
        Set<Long> known = new HashSet<>(told);
        assertNull(Tunnel.caveAir(List.of(new int[]{-127, 12, -37}), known), "west, east, south: the same air is not cave air again");
        var next = Tunnel.next(null, null, new int[]{-127, 12, -37}, false, false, Tunnel.heading("north"), new int[]{-124, 16, -85}, new int[]{-124, 16, -34});
        assertEquals(Tunnel.Act.END_FACT, next.act());
        assertTrue(Tunnel.heldHeadings(List.of("source x: " + next.why()), new int[]{-124, 16, -34}, 8).isEmpty(),
                "the cave-air end is a price fact, not a held heading");
    }

    @Test
    void peakAboveTheSurface_isNotOffered_theDigGoesDown() {
        assertNotNull(BandPrice.peakDrop(232, 63));
        assertTrue(BandPrice.peakDrop(232, 63).contains("above the surface at y 63"));
        assertNull(BandPrice.peakDrop(16, 63));
        assertNull(BandPrice.peakDrop(63, 63));
        assertEquals(16, BandPrice.digY(48, 16), "a peak above the feet: dug at the feet");
        assertEquals(-16, BandPrice.digY(-16, 16));
    }
}
