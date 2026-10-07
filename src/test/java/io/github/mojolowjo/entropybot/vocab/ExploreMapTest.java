package io.github.mojolowjo.entropybot.vocab;

import io.github.mojolowjo.entropybot.map.MapMath;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 0.23.1: explore treats chunks with a painted map tile as seen and goes for the nearest unpainted one that way. */
class ExploreMapTest {

    /** A fake region 0 0 with chunks painted: every chunk with cz >= paintedFromZ (south of the line). */
    static int[] region(int paintedFromZ) {
        int[] px = new int[MapMath.REGION * MapMath.REGION];
        for (int cx = 0; cx < 32; cx++) for (int cz = paintedFromZ; cz < 32; cz++) px[MapMath.index((cx << 4) + 7, (cz << 4) + 7)] = 0xff336633;
        return px;
    }

    @Test
    void aPaintedChunkIsSeen() {
        int[] px = region(10);
        assertTrue(MapMath.chunkPainted(px, 3, 12));
        assertFalse(MapMath.chunkPainted(px, 3, 9));
        assertFalse(MapMath.chunkPainted(null, 0, 0));
        assertTrue(ExploreWords.seen(true, false), "explored.json");
        assertTrue(ExploreWords.seen(false, true), "painted");
        assertTrue(ExploreWords.seen(false, null), "tile still loading");
        assertFalse(ExploreWords.seen(false, false));
    }

    @Test
    void northGoesToTheNearestUnpaintedChunkThatWay() {
        int[] px = region(10);         // chunks z 10..31 painted; the bot stands in chunk 16 20
        Set<String> explored = new HashSet<>();
        ExploreWords.BiPred seen = (cx, cz) -> ExploreWords.seen(explored.contains(cx + " " + cz),
                cx < 0 || cz < 0 || cx > 31 || cz > 31 ? Boolean.FALSE : MapMath.chunkPainted(px, cx, cz));
        int[] n = ExploreWords.next(16, 20, 16, 20, "north", seen, (x, z) -> false);
        assertNotNull(n);
        assertEquals(9, n[1], "the first unpainted row north of the painted land");
        assertTrue(Math.abs(n[0] - 16) <= 11 - 0, "inside the cone");
        // without the tiles it would have stopped one chunk ahead
        int[] blind = ExploreWords.next(16, 20, 16, 20, "north", (x, z) -> false, (x, z) -> false);
        assertEquals(19, blind[1]);
        // explored.json still counts: that chunk is skipped
        explored.add(n[0] + " " + n[1]);
        int[] n2 = ExploreWords.next(16, 20, 16, 20, "north", seen, (x, z) -> false);
        assertFalse(n2[0] == n[0] && n2[1] == n[1]);
    }
}
