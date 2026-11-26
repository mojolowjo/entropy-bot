package baritone.process;

import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.StationPlace;
import io.github.mojolowjo.entropybot.commands.Jobs;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Merge review 2026-11-25 send-back (findings 11/16): the station request from the bottom of the bot's own 1x1 shaft open
 * to the sky, run through the fork's own cell search (LegRules.stationCells, the jar in libs/) on the shaft. The request
 * is built as the live hand builds it: Jobs.feetSurface on the readings (no rock over the head, the ground round the
 * feet), then StationPlace.ask. Before the send-back a 2-3 deep shaft asked under ground, and the fork found 0 free cells
 * in the shaft's walls ("no station cell with room"); the rim within the place reach is asked now, as before the one reader.
 */
class StationShaftCellsTest {
    @AfterEach
    void reset() { Tuning.reset(); }

    static final int GROUND = 67;      // the first free cell over the ground round the shaft

    /** Solid below {@code GROUND} everywhere, but the shaft column 0 0 is dug down to {@code floor} (the feet stand there). */
    static LegRules.CellWorld shaft(int floor) {
        return new LegRules.CellWorld() {
            @Override public boolean empty(BlockPos p) { return p.getY() >= GROUND || (p.getX() == 0 && p.getZ() == 0 && p.getY() >= floor); }
            @Override public boolean solidTop(BlockPos p) { return !empty(p); }
            @Override public int surfaceY(int x, int z) { return x == 0 && z == 0 ? floor : GROUND; }
        };
    }

    static StationPlace.Ask ask(int floor) {
        int[] feet = {0, floor, 0};
        NeedChain.Surface s = Jobs.feetSurface(feet, 0, (x, z) -> x == 0 && z == 0 ? floor : GROUND);
        assertNotNull(s, "every column round the feet is known");
        assertTrue(s.under() && s.hole(), "the planner: under ground in a hole, " + s);
        return StationPlace.ask(feet, s, null);
    }

    static LegRules.NearCells cells(int floor, StationPlace.Ask a) {
        BlockPos feet = new BlockPos(0, floor, 0);
        return LegRules.stationCells(feet, new BlockPos(a.center()[0], a.center()[1], a.center()[2]), a.radius(), a.surface(), shaft(floor), p -> null);
    }

    @Test
    void aShallowOpenShaft_theForkFindsARimCell() {
        for (int floor : new int[]{GROUND - 2, GROUND - 3}) {
            StationPlace.Ask a = ask(floor);
            assertTrue(a.surface(), a.why());
            LegRules.NearCells c = cells(floor, a);
            assertFalse(c.cells().isEmpty(), "the fork finds a cell with room on the rim (floor " + floor + "): " + c.why());
            assertTrue(c.cells().stream().allMatch(p -> p.getY() == GROUND), c.cells().toString());
            // the request the send-back replaced: under ground round the feet, the shaft's walls only
            LegRules.NearCells old = LegRules.stationCells(new BlockPos(0, floor, 0), new BlockPos(0, floor, 0), a.radius(), false, shaft(floor), p -> null);
            assertTrue(old.cells().isEmpty() && old.why().contains("0 free cells"), "the replayed fault: " + old.why());
        }
    }

    @Test
    void aDeepShaft_staysThePlainUnderGroundRequest() {
        int floor = GROUND - 10;
        StationPlace.Ask a = ask(floor);
        assertFalse(a.surface(), "the rim 10 up is past the place reach: " + a.why());
        assertTrue(a.why().contains("open to the sky"), a.why());
        LegRules.NearCells c = cells(floor, a);
        assertTrue(c.cells().isEmpty() && c.why() != null, "the fork's named end, the re-plan walks to a cell with room: " + c.why());
    }

    @Test
    void theRingNotLoaded_isNeverTheSurface() {
        int[] feet = {0, 64, 0};
        assertNull(Jobs.feetSurface(feet, 0, (x, z) -> x == 0 && z == 0 ? 64 : io.github.mojolowjo.entropybot.world.SurfaceHeight.NOT_KNOWN),
                "no column round the feet known: not known (rule 15), never pitDepth's 0");
        assertFalse(StationPlace.ask(feet, null, null).surface());
        assertNull(Jobs.feetSurface(feet, -1, (x, z) -> 64), "the rock over the head not known");
        assertFalse(Jobs.feetSurface(feet, 0, (x, z) -> 64).under(), "flat open ground: the surface");
    }
}
