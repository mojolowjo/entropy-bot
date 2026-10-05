package io.github.mojolowjo.entropybot.restore;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P1: the escape dig-out over a pure grid world (stone everywhere unless set). */
class EscapePlanTest {
    /** A grid: every cell BREAKABLE stone unless set. */
    static final class World implements EscapePlan.Grid {
        final Map<String, EscapePlan.Kind> cells = new HashMap<>();
        EscapePlan.Kind fill = EscapePlan.Kind.BREAKABLE;

        World set(int x, int y, int z, EscapePlan.Kind k) {
            cells.put(x + " " + y + " " + z, k);
            return this;
        }

        @Override
        public EscapePlan.Kind at(int x, int y, int z) {
            return cells.getOrDefault(x + " " + y + " " + z, fill);
        }
    }

    static String cells(List<int[]> l) {
        StringBuilder sb = new StringBuilder();
        for (int[] c : l) sb.append(c[0]).append(' ').append(c[1]).append(' ').append(c[2]).append(';');
        return sb.toString();
    }

    @Test
    void aBotSealedInStoneBreaksTwoBlocksForALevelStep() {
        World w = new World().set(0, 10, 0, EscapePlan.Kind.OPEN).set(0, 11, 0, EscapePlan.Kind.OPEN);
        EscapePlan.Escape e = EscapePlan.plan(w, new int[]{0, 10, 0}, new int[]{20, 10, 0});
        assertNotNull(e);
        assertArrayEquals(new int[]{1, 10, 0}, e.feet(), "toward the destination");
        assertEquals("1 11 0;1 10 0;", cells(e.breaks()), "head first, then feet");
    }

    @Test
    void aTwoDeepPitNeedsOneBlockToClimbOut() {
        // the bot at y 10 in a 1x1 pit, open above up to y 12+; the rim at y 12 is open air above the walls
        World w = new World();
        for (int y = 10; y <= 14; y++) w.set(0, y, 0, EscapePlan.Kind.OPEN);
        for (int[] s : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) for (int y = 12; y <= 14; y++) w.set(s[0], y, s[1], EscapePlan.Kind.OPEN);
        EscapePlan.Escape e = EscapePlan.plan(w, new int[]{0, 10, 0}, new int[]{0, 10, 30});
        assertNotNull(e);
        assertArrayEquals(new int[]{0, 11, 1}, e.feet());
        assertEquals("0 11 1;", cells(e.breaks()), "one block, standing on the wall below it");
    }

    @Test
    void protectedFluidOrBuiltCellsAreNeverBroken() {
        World w = new World().set(0, 10, 0, EscapePlan.Kind.OPEN).set(0, 11, 0, EscapePlan.Kind.OPEN);
        w.fill = EscapePlan.Kind.FLOOR_ONLY;                       // everything around is built / by water / in a protect box
        assertNull(EscapePlan.plan(w, new int[]{0, 10, 0}, null));
        w.fill = EscapePlan.Kind.BLOCKED;
        assertNull(EscapePlan.plan(w, new int[]{0, 10, 0}, null));
    }

    @Test
    void theFloorIsNeverBrokenAndFewestBreaksWin() {
        World w = new World().set(0, 10, 0, EscapePlan.Kind.OPEN).set(0, 11, 0, EscapePlan.Kind.OPEN);
        // east: the head cell is already open, only the feet cell to break
        w.set(1, 11, 0, EscapePlan.Kind.OPEN);
        // west is toward the destination but needs two
        EscapePlan.Escape e = EscapePlan.plan(w, new int[]{0, 10, 0}, new int[]{-50, 10, 0});
        assertArrayEquals(new int[]{1, 10, 0}, e.feet());
        assertEquals(1, e.breaks().size());
        // no floor under the east step (a drop): the next cheapest
        w.set(1, 9, 0, EscapePlan.Kind.OPEN);
        e = EscapePlan.plan(w, new int[]{0, 10, 0}, new int[]{-50, 10, 0});
        assertArrayEquals(new int[]{1, 9, 0}, e.feet());
        assertEquals("1 10 0;", cells(e.breaks()));
    }

    @Test
    void anOpenStepNeedsNothing() {
        World w = new World().set(0, 10, 0, EscapePlan.Kind.OPEN).set(0, 11, 0, EscapePlan.Kind.OPEN)
                .set(0, 10, 1, EscapePlan.Kind.OPEN).set(0, 11, 1, EscapePlan.Kind.OPEN);
        EscapePlan.Escape e = EscapePlan.plan(w, new int[]{0, 10, 0}, null);
        assertTrue(e.breaks().isEmpty());
        assertArrayEquals(new int[]{0, 10, 1}, e.feet());
    }

    @Test
    void aStepDownOverAnOpenHole() {
        World w = new World().set(0, 10, 0, EscapePlan.Kind.OPEN).set(0, 11, 0, EscapePlan.Kind.OPEN);
        w.fill = EscapePlan.Kind.FLOOR_ONLY;
        // south: a hole below the next column, so no level step there; one down onto the floor at y 8
        w.set(0, 11, 1, EscapePlan.Kind.BREAKABLE).set(0, 10, 1, EscapePlan.Kind.BREAKABLE).set(0, 9, 1, EscapePlan.Kind.OPEN);
        EscapePlan.Escape e = EscapePlan.plan(w, new int[]{0, 10, 0}, null);
        assertNotNull(e);
        assertArrayEquals(new int[]{0, 9, 1}, e.feet());
        assertEquals("0 11 1;0 10 1;", cells(e.breaks()), "the cell at head height beside, then the new head cell");
    }

    @Test
    void climbingUpBreaksTheCellOverTheHeadFirst() {
        World w = new World().set(0, 10, 0, EscapePlan.Kind.OPEN).set(0, 11, 0, EscapePlan.Kind.OPEN);
        w.fill = EscapePlan.Kind.FLOOR_ONLY;
        w.set(0, 12, 0, EscapePlan.Kind.BREAKABLE).set(1, 12, 0, EscapePlan.Kind.BREAKABLE).set(1, 11, 0, EscapePlan.Kind.BREAKABLE);
        EscapePlan.Escape e = EscapePlan.plan(w, new int[]{0, 10, 0}, null);
        assertNotNull(e);
        assertArrayEquals(new int[]{1, 11, 0}, e.feet());
        assertEquals("0 12 0;1 12 0;1 11 0;", cells(e.breaks()));
        assertTrue(e.breaks().size() <= EscapePlan.MAX_BREAKS);
    }
}