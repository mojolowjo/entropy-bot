package io.github.mojolowjo.entropybot.threat;

import static org.junit.jupiter.api.Assertions.*;

import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 0.24.4: the damage log ring, the death list and its text; the measured fight model; the dead-end rule. */
class DamageLogTest {
    static final ZoneId UTC = ZoneId.of("UTC");

    static HitLog.Hit hit(long ms, String id, double amt, double hp) {
        return new HitLog.Hit(ms, "mob_attack", id, id == null ? null : "Zombie", amt, hp, 1, -50, 2, "mine strip");
    }

    @Test
    void ringKeepsTheLastInOrder() {
        HitLog l = new HitLog();
        for (int i = 0; i < HitLog.CAP + 5; i++) l.add(hit(i, "minecraft:zombie", 1, 19));
        List<HitLog.Hit> last = l.last(3);
        assertEquals(3, last.size());
        assertEquals(HitLog.CAP + 2, last.get(0).atMs());
        assertEquals(HitLog.CAP + 4, last.get(2).atMs());
        assertEquals(HitLog.CAP, l.last(10_000).size());
        l.add(hit(1, "zombie", 0, 20));            // no damage: not a hit
        assertEquals(HitLog.CAP, l.last(10_000).size());
    }

    @Test
    void deathFreezesTheLastTwentyAndDeathsText() {
        HitLog l = new HitLog();
        assertEquals("", l.deathText(UTC), "no death yet: nothing added");
        for (int i = 0; i < 25; i++) l.add(hit(1_000_000 + i * 1000L, "zombie", 3, 20 - i * 0.5));
        l.add(new HitLog.Hit(1_030_000, "fall", null, null, 4.5, 0, 1, -50, 2, null));
        List<HitLog.Hit> d = l.death(1_031_000);
        assertEquals(HitLog.DEATH_HITS, d.size());
        assertEquals("fall", d.get(d.size() - 1).source());
        String t = l.deathText(UTC);
        assertTrue(t.startsWith("\nlast death's hits: 20 hits, 61.5 damage:"), t);
        assertTrue(t.contains("-3 from zombie (mob_attack), health"), t);
        assertTrue(t.contains("-4.5 from fall, health 0 at 1 -50 2"), t);
        assertTrue(t.contains("job mine strip"), t);
        // hits older than 5 minutes are not this death's
        HitLog o = new HitLog();
        o.add(hit(0, "zombie", 2, 18));
        assertTrue(o.death(400_000).isEmpty());
        assertEquals("none recorded", HitLog.text(o.lastDeath(), UTC));
        // a second death lists only the hits after the first
        HitLog two = new HitLog();
        two.add(hit(1000, "zombie", 3, 0));
        two.death(1500);
        two.add(new HitLog.Hit(2000, "genericKill", null, null, 20, 0, 0, 0, 0, null));
        List<HitLog.Hit> second = two.death(2500);
        assertEquals(1, second.size());
        assertEquals("genericKill", second.get(0).source());
    }


    // a 1-wide dead-end corridor (x 1..6, z 2) off a room (x 7..12, z 0..4): floor y 0, air y 1..2
    static ReachGrid corridor() {
        String floor = "##############";
        String[] fl = {floor, floor, floor, floor, floor};
        String[] air = {
                "#######......#",
                "#######......#",
                "#............#",
                "#######......#",
                "#######......#"};
        String roof = "##############";
        return ReachGrid.parse(new String[][]{fl, air, air, {roof, roof, roof, roof, roof}});
    }


}
