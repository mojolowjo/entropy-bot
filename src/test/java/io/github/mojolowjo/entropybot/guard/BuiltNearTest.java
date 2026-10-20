package io.github.mojolowjo.entropybot.guard;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** AREAS review 1: an unlabelled cobble hut's wall counts as built outside every area when a built block stands near. */
class BuiltNearTest {
    static BuiltNear.Look world(Set<String> built) {
        return (x, y, z) -> built.contains(x + " " + y + " " + z) ? 1 : 0;
    }

    @Test
    void aNaturalBlockNextToABuiltOneIsBuiltOutsideTheAreas() {
        BuiltNear.Look w = world(Set.of("5 64 5"));            // a planks door at 5 64 5
        assertTrue(BuiltNear.counts(false, false, true, w, 4, 64, 5), "the cobble beside it");
        assertTrue(BuiltNear.counts(false, false, true, w, 5, 66, 5), "two above (the axis reach)");
        assertTrue(BuiltNear.counts(false, false, true, w, 4, 65, 4), "the corner");
        assertFalse(BuiltNear.counts(false, false, true, w, 5, 67, 5), "three away: no");
        assertFalse(BuiltNear.counts(false, false, true, w, 7, 66, 5), "a knight's move away: no");
    }

    @Test
    void insideTheAreasOresAndTheSelfRuleAreAsBefore() {
        BuiltNear.Look w = world(Set.of("5 64 5"));
        assertFalse(BuiltNear.counts(false, false, false, w, 4, 64, 5), "inside the owner's area: their cobble is theirs");
        assertFalse(BuiltNear.counts(false, true, true, w, 4, 64, 5), "an ore never counts");
        assertTrue(BuiltNear.counts(true, false, false, null, 4, 64, 5), "a built block is built anywhere");
        assertFalse(BuiltNear.counts(false, false, true, null, 4, 64, 5), "no world to look at: no");
        assertEquals(32, BuiltNear.OFFSETS.length);
    }
}
