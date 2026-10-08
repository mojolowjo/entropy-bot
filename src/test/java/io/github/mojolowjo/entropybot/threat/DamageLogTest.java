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

    @Test
    void observedDamageReplacesTheTableAfterThreeSamples() {
        MobDamage md = new MobDamage();
        FightOrFlee.Me me = new FightOrFlee.Me(20, 20, 0, 6, 0, -1, 6);
        List<FightOrFlee.Foe> one = List.of(new FightOrFlee.Foe("minecraft:zombie", 3, false));
        FightOrFlee.Result a = FightOrFlee.assess(me, one, md);
        assertTrue(a.why().contains("zombie assumed 3/hit"), a.why());
        md.add("minecraft:zombie", 7.5);
        md.add("zombie", 7.5);
        assertTrue(FightOrFlee.assess(me, one, md).why().contains("assumed"), "2 samples: still the table");
        md.add("zombie", 7.5);
        FightOrFlee.Result b = FightOrFlee.assess(me, one, md);
        assertTrue(b.why().contains("zombie observed 7.5/hit (n=3)"), b.why());
        assertTrue(b.loss() > a.loss(), "harder hits, bigger expected loss");
        md.add("zombie", 1.5);                     // a decaying average: 7.5 + 0.3 * (1.5 - 7.5) = 5.7
        assertEquals(5.7, md.observed("zombie"), 1e-9);
        MobDamage back = new MobDamage();
        back.load(md.toJson());
        assertArrayEquals(new double[]{5.7, 4}, back.get("zombie"), 1e-9);
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

    @Test
    void deadEndCorridorHoldsTheMouth() {
        ReachGrid g = corridor();
        DeadEnd.Check c = DeadEnd.check(g, 2, 1, 2, 10, 1, 2);       // bot deep in the corridor, the zombie in the room
        assertNotNull(c);
        assertTrue(c.deadEnd(), "cells " + c.cells());
        assertArrayEquals(new int[]{6, 1, 2}, c.mouth(), "the corridor's last one-wide cell toward the mob");
        FightOrFlee.Result home = new FightOrFlee.Result(FightOrFlee.Verdict.HOME, -1, 9, "retreat home: losing");
        FightOrFlee.Result held = FightOrFlee.holdIfDeadEnd(home, c);
        assertEquals(FightOrFlee.Verdict.HOLD, held.verdict());
        assertTrue(held.why().startsWith("hold the corridor mouth at 6 1 2: a dead end behind me"), held.why());
        FightOrFlee.Result fight = new FightOrFlee.Result(FightOrFlee.Verdict.FIGHT, 5, 3, "fight");
        assertSame(fight, FightOrFlee.holdIfDeadEnd(fight, c), "a fight stays a fight");
        assertSame(home, FightOrFlee.holdIfDeadEnd(home, null), "no grid: no rule");
    }

    @Test
    void openRoomIsNoDeadEnd() {
        ReachGrid g = corridor();
        DeadEnd.Check c = DeadEnd.check(g, 9, 1, 1, 12, 1, 4);       // both in the room: plenty behind the bot
        assertFalse(c.deadEnd(), "cells " + c.cells());
        assertNull(c.mouth());
    }
}
