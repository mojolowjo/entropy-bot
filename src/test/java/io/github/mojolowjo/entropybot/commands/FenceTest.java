package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B7e: the sim's "guard" walking fence as pure tests: goto, come and follow against the areas, the follow watch, the
 * position watch's 1-block slack and T's 15 s grace for a dig.
 */
class FenceTest {
    static final String OW = "minecraft:overworld";
    static final String HINT = "area add <name> here <r>";
    /** The sim's area "zone": -40 125 to 12 180, any height. */
    static final JsonArray AREAS = JsonParser.parseString("[{\"name\":\"zone\",\"dim\":\"minecraft:overworld\",\"x1\":-40,\"z1\":125,\"x2\":12,\"z2\":180},"
            + "{\"name\":\"deep\",\"x1\":100,\"z1\":100,\"x2\":110,\"z2\":110,\"y1\":-60,\"y2\":20},"
            + "{\"name\":\"hell\",\"dim\":\"minecraft:the_nether\",\"x1\":0,\"z1\":0,\"x2\":50,\"z2\":50}]").getAsJsonArray();

    @Test
    void gotoOutsideTheAreasIsRefusedWithTheHint() {
        String why = FenceRules.goalAllowed("would refuse: outside every area", true);
        assertEquals("outside every area", why);
        assertEquals("error: outside every area - " + HINT, FenceRules.gotoRefusal(why), "the sim's goto refusal");
        assertNull(FenceRules.goalAllowed("ok", true), "goto inside works");
        assertNull(FenceRules.goalAllowed("would refuse (log mode): outside every area", true), "log mode only records");
        assertNull(FenceRules.goalAllowed("would refuse: outside every area", false), "the fence off: anywhere");
        assertEquals("guard error: boom", FenceRules.goalAllowed("error: boom", true));
    }

    @Test
    void aPortalIsRefusedInEveryMode() {
        String r = "would refuse: next to a nether_portal (I stay out of the Nether and the End)";
        String why = FenceRules.goalAllowed(r, false);
        assertEquals("next to a nether_portal (I stay out of the Nether and the End)", why, "the floor: fence or no fence");
        assertEquals(why, FenceRules.goalAllowed(r, true));
        assertEquals("error: " + why, FenceRules.gotoRefusal(why), "no area hint for a portal");
        assertEquals("you're " + why, FenceRules.comeRefusal(why, 1, 2));
    }

    @Test
    void whereAWalkEnds() {
        assertArrayEquals(new int[]{60, 53, 150}, FenceRules.goalSpot("goto 60 53 150", 70));
        assertArrayEquals(new int[]{-10, 70, 160}, FenceRules.goalSpot("goto -10 160", 70), "goto x z: at the bot's level");
        assertNull(FenceRules.goalSpot("follow player mojolowjo", 70), "a follow is checked by its watch");
        assertNull(FenceRules.goalSpot(null, 70));
    }

    @Test
    void comeAndFollowRefusals() {
        assertEquals("you're outside my areas (60 150) - area add <name> here 30", FenceRules.comeRefusal("outside every area", 60, 150));
        assertEquals("mojolowjo is outside my areas (60 150) - area add <name> here 30", FenceRules.followRefusal("mojolowjo", 60, 150));
        assertEquals("stopped: mojolowjo left my areas at 60 53 150", FenceRules.followLeft("mojolowjo", new int[]{60, 53, 150}));
    }

    @Test
    void howFarOutsideTheAreasTheBotStands() {
        assertEquals(0, FenceRules.areaGap(AREAS, -10, 53, 160, OW), "inside");
        assertEquals(0, FenceRules.areaGap(AREAS, 12, -50, 180, OW), "the edge is inside, at any height");
        assertEquals(1, FenceRules.areaGap(AREAS, 13, 53, 160, OW), "one step out");
        assertEquals(33, FenceRules.areaGap(AREAS, 100, 53, 100, OW), "the sim's teleport out: the nearest area counts (deep, 33 above it; zone is 88 away)");
        assertEquals(33, FenceRules.areaGap(AREAS, 105, 53, 105, OW), "above an area with a height: the height counts");
        assertEquals(0, FenceRules.areaGap(AREAS, 105, 0, 105, OW), "an area without a dim is the overworld");
        assertEquals(999, FenceRules.areaGap(AREAS, 105, 0, 105, "minecraft:the_end"), "no area in that dimension");
        assertEquals(0, FenceRules.areaGap(AREAS, 10, 64, 10, "minecraft:the_nether"));
        assertEquals(999, FenceRules.areaGap(null, 0, 0, 0, OW));
    }

    @Test
    void thePositionWatchHasOneBlockOfSlackAndAGraceForDigs() {
        assertTrue(FenceRules.watches(true, true, false));
        assertFalse(FenceRules.watches(true, true, true), "a reflex (a retreat) may cross the edge");
        assertFalse(FenceRules.watches(false, true, false), "the fence off");
        assertFalse(FenceRules.watches(true, false, false), "no job");
        // the 1-block slack (any area's edge)
        assertEquals(FenceGrace.Verdict.OK, FenceGrace.verdict(FenceRules.areaGap(AREAS, 13, 53, 160, OW), false, -1, 100));
        // a walk 2 out stops at once; a dig 2-4 out gets 15 s, then stops
        int gap = FenceRules.areaGap(AREAS, 14, 53, 160, OW);
        assertEquals(2, gap);
        assertEquals(FenceGrace.Verdict.STOP, FenceGrace.verdict(gap, false, -1, 100));
        assertEquals(FenceGrace.Verdict.GRACE, FenceGrace.verdict(gap, true, -1, 100));
        assertEquals(FenceGrace.Verdict.GRACE, FenceGrace.verdict(gap, true, 100, 100 + FenceGrace.GRACE_TICKS - 20));
        assertEquals(FenceGrace.Verdict.STOP, FenceGrace.verdict(gap, true, 100, 100 + FenceGrace.GRACE_TICKS));
        assertEquals(FenceGrace.Verdict.STOP, FenceGrace.verdict(FenceRules.areaGap(AREAS, 100, 53, 140, OW), true, -1, 100), "far out: at once");
        assertEquals("stopped: I am outside my areas at 100 53 100 - " + HINT, FenceGrace.stopMessage("100 53 100", false), "the sim's stop text");
    }
}
