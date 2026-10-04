package io.github.mojolowjo.entropybot.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** B7e (E1): the reflex whispers, each once per transition, with the bridge's texts. */
class ReflexNotesTest {

    @Test
    void aRetreatIsSaidOnceWhenItStarts() {
        ReflexNotes n = new ReflexNotes();
        assertTrue(n.step("fighting", "zombie", 12, null, false, 20).isEmpty());
        assertEquals(List.of("Low health (5/20), retreating from zombie"), n.step("retreating", "zombie", 5.4f, null, false, 20));
        assertTrue(n.step("retreating", "zombie", 4, null, false, 20).isEmpty(), "still retreating: once");
        assertTrue(n.step("none", null, 4, null, false, 20).isEmpty());
        assertEquals(List.of("Low health (3/20), retreating from a monster"), n.step("RETREATING", null, 3, null, false, 20), "again after it ended; no target");
    }

    @Test
    void aDeniedDimensionOncePerVisit() {
        ReflexNotes n = new ReflexNotes();
        assertEquals(List.of("I ended up in minecraft:the_nether - sending /home. Tell me if I should stay put instead."),
                n.step("none", null, 20, "minecraft:the_nether", false, 20));
        assertTrue(n.step("none", null, 20, "minecraft:the_nether", false, 20).isEmpty());
        assertEquals(1, n.step("none", null, 20, "minecraft:the_end", false, 20).size(), "another one: said");
        assertTrue(n.step("none", null, 20, null, false, 20).isEmpty(), "back home");
        assertEquals(1, n.step("none", null, 20, "minecraft:the_end", false, 20).size(), "a new visit: said again");
    }

    @Test
    void outOfFoodAtSixAndAgainOnlyAfterFoodCameBack() {
        ReflexNotes n = new ReflexNotes();
        assertTrue(n.step("none", null, 20, null, true, 7).isEmpty(), "food 7: not yet");
        assertEquals(List.of("I'm out of food (food 6/20) - I can keep working but will starve eventually."), n.step("none", null, 20, null, true, 6));
        assertTrue(n.step("none", null, 20, null, true, 3).isEmpty(), "once");
        assertTrue(n.step("none", null, 20, null, false, 3).isEmpty(), "food found: re-armed");
        assertEquals(1, n.step("none", null, 20, null, true, 5).size());
    }

    @Test
    void severalAtOnce() {
        ReflexNotes n = new ReflexNotes();
        List<String> say = n.step("retreating", "creeper", 4, "minecraft:the_nether", true, 2);
        assertEquals(3, say.size());
        assertTrue(say.get(0).startsWith("Low health"));
        assertTrue(say.get(1).startsWith("I ended up in"));
        assertTrue(say.get(2).startsWith("I'm out of food"));
    }
}
