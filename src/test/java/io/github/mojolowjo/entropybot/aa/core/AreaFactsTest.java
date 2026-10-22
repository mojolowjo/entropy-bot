package io.github.mojolowjo.entropybot.aa.core;

import io.github.mojolowjo.entropybot.guard.AreaType;
import io.github.mojolowjo.entropybot.guard.Box;
import io.github.mojolowjo.entropybot.guard.Policy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AREAS step 3: the labelled places as facts (never permissions). */
class AreaFactsTest {
    static final String OW = "minecraft:overworld";
    static final Policy P = Policy.parse("{\"areas\":["
            + "{\"name\":\"camp\",\"type\":\"work\",\"note\":\"the first camp\",\"x1\":0,\"z1\":0,\"x2\":99,\"z2\":99},"
            + "{\"name\":\"home\",\"type\":\"base\",\"x1\":10,\"z1\":10,\"x2\":20,\"z2\":20},"
            + "{\"name\":\"far\",\"type\":\"base\",\"x1\":500,\"z1\":500,\"x2\":520,\"z2\":520},"
            + "{\"name\":\"swamp\",\"type\":\"danger\",\"x1\":50,\"z1\":50,\"x2\":60,\"z2\":60},"
            + "{\"name\":\"tom\",\"type\":\"no-break\",\"note\":\"tom's house\",\"x1\":80,\"z1\":80,\"x2\":90,\"z2\":90}]}");

    @Test
    void hereListsTheMeaningsHardestFirst() {
        assertEquals("base,work", AreaFacts.here(P, OW, 15, 64, 15));
        assertEquals("work,danger", AreaFacts.here(P, OW, 55, 64, 55));
        assertEquals("no-break,work", AreaFacts.here(P, OW, 85, 64, 85));
        assertEquals("", AreaFacts.here(P, OW, 300, 64, 300));
        assertEquals("", AreaFacts.here(null, OW, 15, 64, 15));
        assertTrue(AreaFacts.in("base,work", AreaType.BASE));
        assertFalse(AreaFacts.in("base,work", AreaType.DANGER));
        assertFalse(AreaFacts.in("", AreaType.WORK));
    }

    @Test
    void notesComeFromEveryBoxAtTheCell() {
        assertEquals("tom: tom's house; camp: the first camp", AreaFacts.note(P, OW, 85, 64, 85));
        assertNull(AreaFacts.note(P, OW, 300, 64, 300));
        assertEquals("camp: the first camp", AreaFacts.note(P, OW, 15, 64, 15), "home has no note, camp has");
    }

    @Test
    void theNearestBaseAndTheWayBackAreSoftCosts() {
        int[] base = AreaFacts.nearestBase(P, OW, 100, 100);
        assertArrayEquals(new int[]{15, 15}, base);
        assertArrayEquals(new int[]{510, 510}, AreaFacts.nearestBase(P, OW, 400, 400));
        assertNull(AreaFacts.nearestBase(P, "minecraft:the_nether", 0, 0));
        assertNull(AreaFacts.nearestBase(null, OW, 0, 0));
        assertEquals(-1, AreaFacts.baseDist(null, 0, 0));
        assertEquals(5, AreaFacts.baseDist(new int[]{3, 4}, 0, 0), 1e-9);
        // walking 43 blocks farther from the base costs 10 s x 0.5 = 5 s; nearer costs nothing; no base costs nothing
        int[] me = {15, 64, 15}, dest = {58, 64, 15};
        assertEquals(5, AreaFacts.awayS(me, dest, base), 1e-9);
        assertEquals(0, AreaFacts.awayS(dest, me, base), 1e-9);
        assertEquals(0, AreaFacts.awayS(me, dest, null), 1e-9);
        assertEquals(0, AreaFacts.awayS(null, dest, base), 1e-9);
    }

    @Test
    void candidatesAreNoBreakWhenNotPlainlyMine() {
        assertTrue(AreaFacts.autoNoBreak(true, 0), "a named mob: someone's");
        assertTrue(AreaFacts.autoNoBreak(false, 2), "two block entities");
        assertFalse(AreaFacts.autoNoBreak(false, 1), "one chest could be mine");
        assertFalse(AreaFacts.autoNoBreak(false, 0));
        assertTrue(AreaFacts.autoNoBreak(false, 0, 6, null), "review 1: a cluster of 6 built blocks");
        assertTrue(AreaFacts.autoNoBreak(false, 0, 0, "a village"), "review 1: a village (bells, beds, workstations, doors, paths)");
        assertFalse(AreaFacts.autoNoBreak(false, 1, 5, null));
        Box b = AreaFacts.candidateBox("found1", OW, 100, 64, -20, 16, 8, 16);
        assertEquals(AreaType.NO_BREAK, b.type);
        assertEquals(84, b.x1);
        assertEquals(80, b.y2);
        assertNotNull(b.note);
        assertTrue(new Policy(java.util.List.of(), java.util.List.of(b)).protectAt(OW, 100, 64, -20) != null);
    }

    @Test
    void statusLine() {
        assertEquals("in base,work; base 3 blocks; near the owner", AreaFacts.status("base,work", 3.2, true));
        assertEquals("no area here; no base area", AreaFacts.status("", -1, false));
    }
}
