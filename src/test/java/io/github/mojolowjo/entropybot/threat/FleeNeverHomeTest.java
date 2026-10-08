package io.github.mojolowjo.entropybot.threat;

import io.github.mojolowjo.entropybot.move.ServerCmds;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.24.1: the live bug (phantoms at full health sent the bot /home to an old home 10M blocks off). */
class FleeNeverHomeTest {
    static FightOrFlee.Me me(double hp, int armor, double weapon, int light, int lit) {
        return new FightOrFlee.Me(hp, 20, armor, weapon, light, lit, 6);
    }

    static List<FightOrFlee.Foe> many(String kind, int n) {
        return IntStream.range(0, n).mapToObj(i -> new FightOrFlee.Foe(kind, 6, false)).toList();
    }

    @Test
    void fliersAtFullHealthNeverRetreatHome() {
        for (double hp : new double[]{20, 30, 12}) {
            for (int weapon : new int[]{1, 7}) {
                for (int lit : new int[]{-1, 10}) {
                    for (int light : new int[]{0, 15}) {
                        FightOrFlee.Result r = FightOrFlee.assess(me(hp, 0, weapon, light, lit), many("minecraft:phantom", 3));
                        assertNotEquals(FightOrFlee.Verdict.HOME, r.verdict(), r.why());
                        assertTrue(r.why().startsWith("fight") || r.why().startsWith("shelter"), r.why());
                    }
                }
            }
        }
    }

    @Test
    void fliersLosingShelterWhenALitSpotIsKnownElseFight() {
        FightOrFlee.Result s = FightOrFlee.assess(me(20, 0, 1, 0, 10), many("phantom", 3));
        assertEquals(FightOrFlee.Verdict.SHELTER, s.verdict(), s.why());
        assertTrue(s.why().contains("health 20") && s.why().contains("margin"), s.why());
        FightOrFlee.Result f = FightOrFlee.assess(me(20, 0, 1, 0, -1), many("phantom", 3));
        assertEquals(FightOrFlee.Verdict.FIGHT, f.verdict(), f.why());
        assertEquals(FightOrFlee.Verdict.SHELTER, FightOrFlee.assess(me(20, 0, 1, 0, 8), many("skeleton", 4)).verdict(), "ranged too");
        assertEquals(FightOrFlee.Verdict.FIGHT, FightOrFlee.assess(me(20, 0, 1, 0, 0), many("phantom", 3)).verdict(), "at the lit spot already: no 0-block shelter loop");
        assertEquals(FightOrFlee.Verdict.FIGHT, FightOrFlee.assess(me(20, 0, 1, 15, 10), many("phantom", 3)).verdict(), "lit here: stay");
    }

    @Test
    void lowHealthWithAZombieClosingRetreatsHome() {
        FightOrFlee.Result r = FightOrFlee.assess(me(5, 0, 7, 15, 10), many("zombie", 1));
        assertEquals(FightOrFlee.Verdict.HOME, r.verdict());
        assertTrue(r.why().startsWith("retreat home"), r.why());
        assertEquals(FightOrFlee.Verdict.HOME, FightOrFlee.assess(me(5, 0, 7, 15, 10), many("phantom", 1)).verdict(), "under 6: home even from fliers");
    }

    @Test
    void homeTeleportOnlyUnderSixAndWithServerCommandsOn() {
        assertFalse(ServerCmds.homeTp(20, true), "full health: never /home");
        assertFalse(ServerCmds.homeTp(6, true));
        assertTrue(ServerCmds.homeTp(5, true));
        assertFalse(ServerCmds.homeTp(5, false), "server commands off: walk");
    }

    @Test
    void absurdWalksAreRefused() {
        assertNull(ServerCmds.tooFar(1999, 2000));
        assertNull(ServerCmds.tooFar(2000, 2000));
        assertEquals("error: that is 5000 blocks away - too far to walk (path maxWalk 2000)", ServerCmds.tooFar(5000, 2000));
        String live = ServerCmds.tooFar(new int[]{-20, 54, 183}, new int[]{10_000_000, 70, 183}, 2000);
        assertNotNull(live);
        assertTrue(live.contains("too far to walk"), live);
        assertNull(ServerCmds.tooFar(new int[]{0, 64, 0}, new int[]{300, 64, 400}, 2000));
    }

    @Test
    void serverCommandsDefaultOffAndNoted() {
        boolean was = ServerCmds.on();
        try {
            ServerCmds.setOn(false);
            assertFalse(ServerCmds.allowed("home"));
            assertTrue(ServerCmds.checkLines().stream().anyMatch(l -> l.contains("home walked instead")));
            ServerCmds.setOn(true);
            assertTrue(ServerCmds.allowed("home"));
        } finally {
            ServerCmds.setOn(was);
        }
    }
}
