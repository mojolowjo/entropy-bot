package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B7e: the sim's "guard" walking fence as pure tests: goto, come and follow against the guard's answer. AREAS (0.39.0):
 * the world is open; only a keep-out area and the floor (a portal) refuse a walk, and the position watch is gone.
 */
class FenceTest {
    static final String OW = "minecraft:overworld";
    /** The sim's area "zone": -40 125 to 12 180, any height. */
    static final JsonArray AREAS = JsonParser.parseString("[{\"name\":\"zone\",\"dim\":\"minecraft:overworld\",\"x1\":-40,\"z1\":125,\"x2\":12,\"z2\":180},"
            + "{\"name\":\"deep\",\"x1\":100,\"z1\":100,\"x2\":110,\"z2\":110,\"y1\":-60,\"y2\":20},"
            + "{\"name\":\"hell\",\"dim\":\"minecraft:the_nether\",\"x1\":0,\"z1\":0,\"x2\":50,\"z2\":50}]").getAsJsonArray();

    @Test
    void outsideEveryAreaIsNoRefusalAnyMore() {
        assertNull(FenceRules.goalAllowed("would refuse: outside every area", true), "the world is open");
        assertNull(FenceRules.goalAllowed("would refuse: no areas set", true));
        assertNull(FenceRules.goalAllowed("ok", true), "goto inside works");
        assertNull(FenceRules.goalAllowed("would refuse (log mode): in pit (no-break)", true), "log mode only records");
        assertNull(FenceRules.goalAllowed("would refuse: outside every area", false), "the fence off: anywhere");
        assertEquals("guard error: boom", FenceRules.goalAllowed("error: boom", true));
    }

    @Test
    void aKeepOutAreaIsRefusedInEveryModeByItsName() {
        String r = "would refuse: in pit (keep-out)";
        assertEquals("in pit (keep-out)", FenceRules.goalAllowed(r, false), "the floor: fence or no fence");
        assertEquals("in pit (keep-out)", FenceRules.goalAllowed(r, true));
        assertEquals("error: in pit (keep-out)", FenceRules.gotoRefusal("in pit (keep-out)"), "no area hint: the box names itself");
        assertEquals("you're in pit (keep-out) (60 150) - I don't go in there", FenceRules.comeRefusal("in pit (keep-out)", 60, 150));
        assertEquals("mojolowjo is in pit (keep-out) (60 150) - I don't go in there", FenceRules.followRefusal("mojolowjo", "in pit (keep-out)", 60, 150));
        assertEquals("stopped: mojolowjo went where I don't go, at 60 53 150", FenceRules.followLeft("mojolowjo", new int[]{60, 53, 150}));
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
    void howFarFromTheAreasTheBotStands() {
        assertEquals(0, FenceRules.areaGap(AREAS, -10, 53, 160, OW), "inside");
        assertEquals(0, FenceRules.areaGap(AREAS, 12, -50, 180, OW), "the edge is inside, at any height");
        assertEquals(1, FenceRules.areaGap(AREAS, 13, 53, 160, OW), "one step out");
        assertEquals(33, FenceRules.areaGap(AREAS, 100, 53, 100, OW), "the nearest area counts (deep, 33 above it; zone is 88 away)");
        assertEquals(33, FenceRules.areaGap(AREAS, 105, 53, 105, OW), "above an area with a height: the height counts");
        assertEquals(0, FenceRules.areaGap(AREAS, 105, 0, 105, OW), "an area without a dim is the overworld");
        assertEquals(999, FenceRules.areaGap(AREAS, 105, 0, 105, "minecraft:the_end"), "no area in that dimension");
        assertEquals(0, FenceRules.areaGap(AREAS, 10, 64, 10, "minecraft:the_nether"));
        assertEquals(999, FenceRules.areaGap(null, 0, 0, 0, OW));
    }
}
