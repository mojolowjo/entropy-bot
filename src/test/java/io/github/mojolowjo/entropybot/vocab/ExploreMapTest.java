package io.github.mojolowjo.entropybot.vocab;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 run EP (owner 2026-10-28, rule 17): a chunk is covered only when the bot walked within exploreRules.seen chunks
 * of it (explored.json); a loaded chunk, a painted terrain-map tile, is NOT covered. Was (0.23.1): the painted tile counted,
 * which at render distance 4 marked 64-80 m around the bot covered unwalked.
 */
class ExploreMapTest {

    @Test
    void onlyWalkedChunksAreCovered() {
        // the bot stands in chunk 16 20; the client has loaded (and the map painted) everything within 5 chunks: none of it counts
        Set<String> explored = new HashSet<>();
        ExploreWords.BiPred seen = (cx, cz) -> explored.contains(cx + " " + cz);
        int[] n = ExploreWords.next(16, 20, 16, 20, "north", seen, (x, z) -> false);
        assertNotNull(n);
        assertEquals(19, n[1], "the first chunk ahead: loaded land is not covered land");
        assertTrue(Math.abs(n[0] - 16) <= 1, "one of the three chunks of ring 1 ahead (a tie: scan order)");
        // the chunks the bot walked within exploreRules.seen of (explored.json) are covered: the walk goes past them
        for (int cz = 15; cz <= 20; cz++) for (int cx = 10; cx <= 22; cx++) explored.add(cx + " " + cz);   // wider than the 90 degree cone
        int[] n2 = ExploreWords.next(16, 20, 16, 20, "north", seen, (x, z) -> false);
        assertNotNull(n2);
        assertEquals(14, n2[1], "the first row north of the walked chunks");
        assertTrue(Math.abs(n2[0] - 16) <= 6, "inside the cone");
    }
}
