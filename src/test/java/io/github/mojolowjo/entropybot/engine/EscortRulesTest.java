package io.github.mojolowjo.entropybot.engine;

import io.github.mojolowjo.entropybot.commands.OwnerFix;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EscortRulesTest {
    // the player at the origin, the bot 3 east
    static final double PX = 0, PY = 64, PZ = 0, BX = 3, BZ = 0;

    static EscortRules.Mob mob(int k, double x, double z, boolean aggressive, float yaw) {
        return new EscortRules.Mob(k, "zombie", x, 64, z, aggressive, yaw, false);
    }

    @Test
    void parsing() {
        assertEquals("status", EscortRules.parse("").word());
        assertEquals("status", EscortRules.parse("status").word());
        assertEquals("off", EscortRules.parse("off").word());
        EscortRules.Cmd me = EscortRules.parse("me");
        assertEquals("me", me.word());
        assertEquals(6, me.radius());
        assertEquals(10, EscortRules.parse("me 10").radius());
        EscortRules.Cmd pl = EscortRules.parse("Steve_2 8");
        assertEquals("player", pl.word());
        assertEquals("Steve_2", pl.name());
        assertEquals(8, pl.radius());
        assertEquals("error", EscortRules.parse("me 40").word());
        assertEquals("error", EscortRules.parse("me ten").word());
        assertEquals("error", EscortRules.parse("me 1").word());
        assertEquals("error", EscortRules.parse("a b c").word());
        assertEquals("error", EscortRules.parse("x!").word());
    }

    @Test
    void yawAndTargeting() {
        assertEquals(0, EscortRules.yawTo(0, 0, 0, 5), 1e-9);       // south
        assertEquals(90, EscortRules.yawTo(0, 0, -5, 0), 1e-9);     // west
        // a mob 5 north of the player looking south (at them): targets the player
        assertTrue(EscortRules.targetsPlayer(mob(1, 0, -5, true, 0), PX, PZ, BX, BZ));
        assertFalse(EscortRules.targetsPlayer(mob(1, 0, -5, false, 0), PX, PZ, BX, BZ), "not aggressive");
        assertFalse(EscortRules.targetsPlayer(mob(1, 0, -5, true, 180), PX, PZ, BX, BZ), "looking away");
        // looking at the bot rather than the player
        float atBot = (float) EscortRules.yawTo(3, -5, BX, BZ);
        assertFalse(EscortRules.targetsPlayer(mob(1, 3, -5, true, atBot), PX, PZ, BX, BZ));
    }

    @Test
    void targetingBeatsNearest() {
        List<EscortRules.Mob> mobs = List.of(
                mob(1, 2, 2, false, 0),                 // nearest, idle
                mob(2, 0, -5, true, 0));                // farther, targets the player
        assertEquals(1, EscortRules.pick(mobs, PX, PY, PZ, BX, BZ, 6));
        assertEquals(0, EscortRules.pick(List.of(mob(1, 4, 0, false, 0), mob(2, 2, 0, false, 0)).reversed(), PX, PY, PZ, BX, BZ, 6));
        assertEquals(-1, EscortRules.pick(List.of(mob(1, 8, 0, false, 0)), PX, PY, PZ, BX, BZ, 6), "beyond the radius");
        // a targeting skeleton counts up to twice the radius
        assertEquals(0, EscortRules.pick(List.of(mob(1, 0, -10, true, 0)), PX, PY, PZ, BX, BZ, 6));
        assertEquals(-1, EscortRules.pick(List.of(), PX, PY, PZ, BX, BZ, 6));
    }

    @Test
    void standoff() {
        assertNull(EscortRules.standoff(0, 0, 3, 0), "2-4 blocks: stay");
        assertNull(EscortRules.standoff(0, 0, 0, 2));
        double[] s = EscortRules.standoff(0, 0, 1, 0);          // in their face: back to 3 on the same side
        assertEquals(3, s[0], 1e-9);
        assertEquals(0, s[1], 1e-9);
        s = EscortRules.standoff(0, 0, 0, -10);                 // far: 3 off on the bot's side
        assertEquals(0, s[0], 1e-9);
        assertEquals(-3, s[1], 1e-9);
        assertNotNull(EscortRules.standoff(0, 0, 0, 0));       // on top of them: some side
    }

    @Test
    void interposeIsBetween() {
        double[] s = EscortRules.interpose(0, 0, 10, 0);
        assertEquals(5, s[0], 1e-9);
        s = EscortRules.interpose(0, 0, 4, 0);                  // 2 from both
        assertEquals(2, s[0], 1e-9);
        s = EscortRules.interpose(0, 0, 0, 2);                  // close: half way, never beyond the creeper
        assertTrue(s[1] > 0 && s[1] < 2);
        assertEquals(0, s[0], 1e-9);
    }

    @Test
    void feedRule() {
        assertEquals(4, EscortRules.feed(6, 20, 100_000, 0));
        assertEquals(0, EscortRules.feed(7, 20, 100_000, 0), "not hungry enough");
        assertEquals(0, EscortRules.feed(-1, 20, 100_000, 0), "unknown food (old companion)");
        assertEquals(0, EscortRules.feed(3, 8, 100_000, 0), "keeps its own 8");
        assertEquals(2, EscortRules.feed(3, 10, 100_000, 0));
        assertEquals(0, EscortRules.feed(3, 20, 100_000, 50_000), "cooldown");
    }

    @Test
    void dangerWarning() {
        // a creeper north of the player, 10 away and closer than before
        String w = EscortRules.warning("minecraft:creeper", 11, 10, 0, 0, 0, -10, false);
        assertNotNull(w);
        assertTrue(w.contains("creeper") && w.contains("north"), w);
        assertNull(EscortRules.warning("minecraft:creeper", 11, 10, 0, 0, 0, -10, true), "once per mob");
        assertNull(EscortRules.warning("minecraft:creeper", 9, 10, 0, 0, 0, -10, false), "moving away");
        assertNull(EscortRules.warning("minecraft:creeper", 14, 13, 0, 0, 0, -13, false), "too far");
        assertNull(EscortRules.warning("minecraft:zombie", 11, 10, 0, 0, 0, -10, false), "only creepers and skeletons");
        assertNotNull(EscortRules.warning("stray", Double.MAX_VALUE, 5, 0, 0, 5, 0, false));
        assertEquals("east", EscortRules.compass(0, 0, 5, 0));
        assertEquals("south-west", EscortRules.compass(0, 0, -5, 5));
        assertEquals("north", EscortRules.compass(0, 0, 0, -5));
    }

    @Test
    void ownerVitals() {
        OwnerFix.Fix f = OwnerFix.parse("{\"name\":\"mojolowjo\",\"x\":1,\"y\":64,\"z\":2,\"dim\":\"minecraft:overworld\",\"received\":1000,\"food\":5,\"health\":19.6}");
        assertArrayEquals(new int[]{5, 20}, OwnerFix.vitals(f, "MOJOLOWJO", 2000));
        assertNull(OwnerFix.vitals(f, "mojolowjo", 20_000), "stale");
        assertNull(OwnerFix.vitals(f, "steve", 2000));
        OwnerFix.Fix old = OwnerFix.parse("{\"name\":\"mojolowjo\",\"x\":1,\"y\":64,\"z\":2,\"dim\":\"minecraft:overworld\",\"received\":1000}");
        assertArrayEquals(new int[]{-1, -1}, OwnerFix.vitals(old, "mojolowjo", 2000), "no fields: unknown");
        OwnerFix.Fix bad = OwnerFix.parse("{\"name\":\"mojolowjo\",\"x\":1,\"y\":64,\"z\":2,\"dim\":\"minecraft:overworld\",\"received\":1000,\"food\":\"x\"}");
        assertEquals(-1, bad.food());
    }
}
