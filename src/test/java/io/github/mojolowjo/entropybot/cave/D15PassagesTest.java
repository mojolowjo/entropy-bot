package io.github.mojolowjo.entropybot.cave;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.sense.Sightings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D15 (caves as passages, the owner's grid): the passage finder on small hand-built grids - a straight tunnel, a T
 * junction, a dead end with a bump, a vertical shaft, a wide room, the flood's cap - and the caving step's budget end as
 * a fact the planner re-plans on. No game: the air is a set of cells (feet y 40, head y 41, the floor y 39 not air).
 */
public class D15PassagesTest {
    @AfterEach
    void clear() {
        FactChange.clear();
        Tuning.reset();
    }

    static final int DROP = 3, FORK = 3, CAP = 4096; // structural: the Tuning defaults (cave.passageDrop / ForkLayers / Cells)

    /** A passage for the step's tests: far end {@code end}, {@code fromFeet} layers off, {@code unseen} cells not seen. */
    public static Passages.Passage passage(int id, int[] end, int fromFeet, int unseen) {
        return new Passages.Passage(id, -1, end, end, 1, 1, fromFeet, List.of(), unseen, 0, Passages.EndKind.DEAD_END);
    }

    /** A walkable cell's air: feet and head. */
    static void open(Set<Long> air, int x, int y, int z) {
        air.add(Sightings.cell(x, y, z));
        air.add(Sightings.cell(x, y + 1, z));
    }

    static Passages.Found build(Set<Long> air, int[] feet) { return build(air, feet, Set.of(), c -> false, DROP, CAP); }

    static Passages.Found build(Set<Long> air, int[] feet, Set<Long> ore, Passages.Seen seen, int drop, int cap) {
        return Passages.build((x, y, z) -> air.contains(Sightings.cell(x, y, z)), seen, feet, ore, drop, FORK, cap);
    }

    @Test
    void aStraightTunnel_isOnePassage_toItsDeadEnd() {
        Set<Long> air = new HashSet<>();
        for (int x = 0; x <= 20; x++) open(air, x, 40, 0);
        var f = build(air, new int[]{0, 40, 0});
        assertNull(f.why());
        assertEquals(1, f.passages().size(), "one tunnel, one passage");
        var p = f.passages().get(0);
        assertEquals(Passages.EndKind.DEAD_END, p.endKind());
        assertArrayEquals(new int[]{20, 40, 0}, p.end());
        assertEquals(21, p.length());
        assertEquals(42, p.unseen(), "feet and head cells, none seen");
        assertTrue(p.open());
        // from its middle: two passages, one each way
        assertEquals(3, build(air, new int[]{10, 40, 0}).passages().size(), "the feet's own layer, then a fork into the two ways");
    }

    @Test
    void aTJunction_forksIntoTwoArms() {
        Set<Long> air = new HashSet<>();
        for (int x = 0; x <= 20; x++) open(air, x, 40, 0);
        for (int z = 1; z <= 10; z++) open(air, 10, 40, z);
        var ps = build(air, new int[]{0, 40, 0}).passages();
        assertEquals(3, ps.size(), "the trunk and two arms: " + ps);
        assertEquals(Passages.EndKind.FORK, ps.get(0).endKind());
        Set<String> ends = new HashSet<>();
        for (int i = 1; i < 3; i++) {
            assertEquals(0, ps.get(i).parent(), "each arm forks from the trunk");
            assertEquals(Passages.EndKind.DEAD_END, ps.get(i).endKind());
            int[] e = ps.get(i).end();
            ends.add(e[0] + " " + e[1] + " " + e[2]);
        }
        assertEquals(Set.of("20 40 0", "10 40 10"), ends);
    }

    @Test
    void aDeadEndWithABump_isOnePassage_theBumpFolded() {
        Set<Long> air = new HashSet<>();
        for (int x = 0; x <= 8; x++) open(air, x, 40, 0);
        open(air, 3, 40, 1);                                                    // a one-cell alcove: a bump, no branch
        var ps = build(air, new int[]{0, 40, 0}).passages();
        assertEquals(1, ps.size(), "a bump in the wall is not a branch: " + ps);
        assertEquals(Passages.EndKind.DEAD_END, ps.get(0).endKind());
        assertTrue(ps.get(0).cells().contains(Sightings.cell(3, 40, 1)), "the bump's cells are the passage's");
        // walked: every cell seen - not open
        Set<Long> all = new HashSet<>(ps.get(0).cells());
        var seen = build(air, new int[]{0, 40, 0}, Set.of(), all::contains, DROP, CAP).passages().get(0);
        assertEquals(0, seen.unseen());
        assertFalse(seen.open(), "a dead end walked to its end is done");
    }

    @Test
    void aVerticalShaft_joinsTheTwoFloors_withinTheDrop_bothWays() {
        Set<Long> air = new HashSet<>();
        for (int x = 0; x <= 5; x++) open(air, x, 50, 0);                       // the upper tunnel, floor y 49
        for (int y = 47; y <= 51; y++) air.add(Sightings.cell(6, y, 0));        // the shaft, floor y 46
        for (int x = 7; x <= 12; x++) open(air, x, 47, 0);                      // the lower tunnel
        var down = build(air, new int[]{0, 50, 0}).passages();
        assertEquals(1, down.size(), "one passage down the shaft: " + down);
        assertArrayEquals(new int[]{12, 47, 0}, down.get(0).end());
        var up = build(air, new int[]{12, 47, 0}).passages();
        assertEquals(1, up.size());
        assertArrayEquals(new int[]{0, 50, 0}, up.get(0).end(), "the same edge up (Baritone pillars)");
        var shallow = build(air, new int[]{0, 50, 0}, Set.of(), c -> false, 2, CAP).passages();
        assertArrayEquals(new int[]{5, 50, 0}, shallow.get(0).end(), "a drop of 3 with cave.passageDrop 2: not joined");
    }

    @Test
    void aWideRoom_fromItsCorner_isOnePassage() {
        Set<Long> air = new HashSet<>();
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) open(air, x, 40, z);
        var ps = build(air, new int[]{0, 40, 0}).passages();
        assertEquals(1, ps.size(), "a room is one passage, not 81 cells: " + ps);
        assertTrue(ps.get(0).width() > 1);
        assertArrayEquals(new int[]{8, 40, 8}, ps.get(0).end(), "its far corner");
    }

    @Test
    void theCap_leavesTheEndOpen_andOreFacesAreCounted() {
        Set<Long> air = new HashSet<>();
        for (int x = 0; x <= 100; x++) open(air, x, 40, 0);
        Set<Long> ore = Set.of(Sightings.cell(5, 42, 0), Sightings.cell(5, 40, 1), Sightings.cell(90, 40, 1));
        var f = build(air, new int[]{0, 40, 0}, ore, c -> Sightings.cellOf(c)[0] < 10, DROP, 50);
        assertTrue(f.capped());
        var p = f.passages().get(0);
        assertEquals(Passages.EndKind.CAPPED, p.endKind());
        assertTrue(p.open(), "the flood stopped before its end: not known, walked to see");
        assertEquals(2, p.oreFaces(), "the two faces on its walls; the one past the cap is not its");
        assertEquals((50 - 10) * 2, p.unseen(), "the cells past x 9, feet and head");
    }

    @Test
    void noWalkableCellAtTheFeet_isSaid() {
        var f = build(new HashSet<>(), new int[]{0, 40, 0});
        assertTrue(f.passages().isEmpty());
        assertTrue(f.why().startsWith("no walkable cave cell at the feet 0 40 0"), f.why());
    }

    @Test
    void thePriceIsSecondsPerExpectedOre_neverARank() {
        var near = passage(0, new int[]{5, 40, 0}, 5, 100);
        var far = passage(1, new int[]{50, 40, 0}, 50, 100);
        assertTrue(CaveWalk.passagePrice(near, 0.5, 0.01) < CaveWalk.passagePrice(far, 0.5, 0.01));
        assertEquals((5 + 1) * 0.5 / 1.0, CaveWalk.passagePrice(near, 0.5, 0.01), 1e-9);
        assertEquals(Double.POSITIVE_INFINITY, CaveWalk.passagePrice(passage(2, new int[]{1, 40, 0}, 1, 0), 0.5, 0.01), "nothing expected: not offered");
    }

    /** D15: the budget end is a source fact (the planner re-plans on the sources left), never a price fact the order stops on. */
    @Test
    void theBudgetEnd_isASourceFact_withTheCountsSaid() {
        var stop = (CaveWalk.Stop) CaveWalk.next(0, 3, 10.2, 10, null, List.of(passage(0, new int[]{5, 40, 0}, 5, 10), passage(1, new int[]{9, 40, 0}, 9, 0)),
                List.of(), new int[]{0, 40, 0}, 4.5);
        assertEquals(CaveWalk.End.MINUTES, stop.end());
        assertTrue(stop.why().contains("0 ore mined, 1 passage(s) left"), stop.why());
        var f = FactChange.caveMinutesFact(new int[]{1, 2, 3}, 0, 1, stop.why());
        assertFalse(f.price(), "not a price: a price fact on a failed step was 'kept the running pick' and the order stopped (runs JZ, JAE)");
        assertTrue(f.key().startsWith("source "), "the cave's source fact: out for this order, the rest priced again");
        assertTrue(f.text().startsWith("cave at 1 2 3: budget spent, 0 ore mined, 1 passages left"), f.text());
    }

    /** D15 (run JAD's flip loop): a sighting re-prices a caving step only when closer than its target by the margin. */
    @Test
    void aSighting_repricesTheCavingStep_onlyWhenCloserByTheMargin() {
        assertTrue(CaveWalk.seenRepricesCave(3, 10, 4));
        assertFalse(CaveWalk.seenRepricesCave(7, 10, 4), "the same ore, about as far: the step goes on");
        assertFalse(CaveWalk.seenRepricesCave(1, Double.NaN, 4), "no target (mining or picking): the step's record takes it");
    }
}
