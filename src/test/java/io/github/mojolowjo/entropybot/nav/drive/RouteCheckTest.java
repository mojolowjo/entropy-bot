package io.github.mojolowjo.entropybot.nav.drive;

import io.github.mojolowjo.entropybot.nav.moves.MoveCatalog;
import io.github.mojolowjo.entropybot.nav.search.Path;
import io.github.mojolowjo.entropybot.nav.search.Step;
import io.github.mojolowjo.entropybot.nav.search.goals.GoalBlock;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.mojolowjo.entropybot.nav.drive.MoveDriverTest.c;
import static org.junit.jupiter.api.Assertions.*;

/** Nav step 9c: the ghost flies a part only up to its first move that needs the hands. */
class RouteCheckTest {
    static Path route(Step... s) {
        List<BetterBlockPos> cells = new java.util.ArrayList<>();
        cells.add(s[0].src());
        for (Step x : s) cells.add(x.dest());
        BetterBlockPos end = s[s.length - 1].dest();
        return Path.of(cells, List.of(s), new GoalBlock(end.x, end.y, end.z));
    }

    static Step t(int z) { return new Step(c(0, 64, z), c(0, 64, z + 1), MoveCatalog.TRAVERSE_SOUTH, 4.6); }

    @Test
    void theFirstMoveNeedingTheHands() {
        assertEquals(-1, RouteCheck.firstHands(route(t(0), t(1), t(2)), new FakeMoveWorld()), "a plain walk");
        assertEquals(1, RouteCheck.firstHands(route(t(0), t(1), t(2)), new FakeMoveWorld().solid(0, 65, 2)), "a block in the way at move 2");
        assertEquals(2, RouteCheck.firstHands(route(t(0), t(1), t(2)), new FakeMoveWorld().hole(0, 3)), "no floor under move 3's cell");
        assertEquals(0, RouteCheck.firstHands(route(new Step(c(0, 64, 0), c(0, 65, 0), MoveCatalog.PILLAR, 20)), new FakeMoveWorld()), "a pillar places");
        assertEquals(-1, RouteCheck.firstHands(route(new Step(c(0, 64, 0), c(0, 64, 3), MoveCatalog.PARKOUR_SOUTH, 20)),
                new FakeMoveWorld().hole(0, 1).hole(0, 2)), "a parkour jump needs no hands");
    }
}
