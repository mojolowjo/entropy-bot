package io.github.mojolowjo.entropybot.aa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND4A O: house, shelter, burrow priced; the night makes the long build lose. */
class BaseOptionsTest {
    @Test
    void byDayTheHouseAndTheShelterAreRowsAndTheBurrowIsDropped() {
        Options.Choice c = BaseOptions.choose(new BaseOptions.BaseFacts("home", new int[]{0, 64, 0}, 20, false, 0, false, 0), new Options.OptionMemory(), 0);
        assertEquals(2, c.ranked().size());
        assertEquals("goal shelter", c.best().command());
        assertTrue(c.why().contains("burrow: a night way only"), c.why());
    }

    @Test
    void atNightTheBurrowWinsAndTheOpenNightIsPriced() {
        Options.Choice day = BaseOptions.choose(new BaseOptions.BaseFacts("home", null, 0, false, 0, false, 0), new Options.OptionMemory(), 0);
        Options.Choice night = BaseOptions.choose(new BaseOptions.BaseFacts("home", null, 0, true, 1, false, 0), new Options.OptionMemory(), 0);
        assertEquals("burrow then wait 120", night.best().command());
        double houseDay = day.ranked().get(0).cost(), houseNight = night.ranked().stream().filter(o -> o.id().startsWith("house")).findFirst().orElseThrow().cost();
        assertTrue(houseNight > houseDay, "the open night costs");
        assertTrue(night.why().contains("open night"), night.why());
    }

    @Test
    void underGroundTheSurfaceWaysClimbFirst() {
        Options.Choice c = BaseOptions.choose(new BaseOptions.BaseFacts("home", null, 0, false, 0, true, 12), new Options.OptionMemory(), 0);
        assertEquals("climb then house home", c.best().command());
        assertTrue(c.why().contains("climb 12 up"), c.why());
    }

    @Test
    void noAreaNoBlocksByDayIsNoWay() {
        Options.Choice c = BaseOptions.choose(new BaseOptions.BaseFacts(null, null, 3, false, 0, false, 0), new Options.OptionMemory(), 0);
        assertNull(c.best());
        assertTrue(c.why().contains("house: no area marked") && c.why().contains("3 blocks in the bag, 12 wanted"), c.why());
    }
}
