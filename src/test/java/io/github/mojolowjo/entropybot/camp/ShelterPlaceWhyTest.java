package io.github.mojolowjo.entropybot.camp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 0.27.4: a refused shelter placing names its cause (no face to place against, the guard or no room). */
class ShelterPlaceWhyTest {
    @Test void noFaceIsNamed() {
        String w = ShelterPlan.placeWhy("error: nothing in reach to place oak_planks against at 1 2 3");
        assertTrue(w.startsWith("no face to place against"), w);
    }

    @Test void guardOrRoomIsNamed() {
        String w = ShelterPlan.placeWhy("error: the game refused placing oak_planks at 1 2 3 (no room for it, or the guard said no)");
        assertTrue(w.startsWith("the guard refused, or no room for it"), w);
    }

    @Test void otherErrorsPassThroughWithoutThePrefix() {
        assertEquals("something is in the way at 1 2 3", ShelterPlan.placeWhy("error: something is in the way at 1 2 3"));
    }
}
