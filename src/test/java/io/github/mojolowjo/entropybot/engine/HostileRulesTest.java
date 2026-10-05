package io.github.mojolowjo.entropybot.engine;

import io.github.mojolowjo.entropybot.engine.HostileRules.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HostileRulesTest {
    private static final Set<String> REGISTRY = Set.of("minecraft:zombie", "minecraft:wolf", "minecraft:goat",
            "arphex:spider_lunger", "arphex:spider_jump");

    // ---- the verdict ----

    /** A fake mob for the threat model: the booleans the game side reads. */
    record Mob(String id, boolean player, boolean tame, boolean owner, boolean enemy, boolean neutral, boolean peaceful) {
        static Mob of(String id) { return new Mob(id, false, false, false, false, false, false); }
        Mob asEnemy() { return new Mob(id, player, tame, owner, true, neutral, peaceful); }
        Mob asNeutral() { return new Mob(id, player, tame, owner, enemy, true, peaceful); }
        Mob asTame() { return new Mob(id, player, true, true, enemy, neutral, peaceful); }
        Mob asOwned() { return new Mob(id, player, false, true, enemy, neutral, peaceful); }
        Mob asPeaceful() { return new Mob(id, player, tame, owner, enemy, neutral, true); }
        Mob asPlayer() { return new Mob(id, true, tame, owner, enemy, neutral, peaceful); }
    }

    /** The fake threat model: a hostile list, who hit the bot when, and the clock. */
    static final class World {
        final Set<String> list;
        final Map<String, Long> hits = new HashMap<>();
        long now;
        boolean hurt;

        World(String... list) { this.list = Set.of(list); }

        void hit(Mob m) {
            if (HostileRules.recordAttacker(true, false, true, m.player())) hits.put(m.id(), now);
            hurt = true;
        }

        Kind kind(Mob m) {
            return HostileRules.classify(m.player(), m.tame(), m.owner(), list.contains(m.id()), m.enemy(), m.neutral(), hurt,
                    HostileRules.retaliating(hits.get(m.id()), now), m.peaceful());
        }
    }

    @Test
    void enemiesCountAsBefore() {
        World w = new World();
        assertEquals(Kind.ENEMY, w.kind(Mob.of("minecraft:zombie").asEnemy()));
        assertEquals(Kind.NEUTRAL, w.kind(Mob.of("minecraft:enderman").asEnemy().asNeutral()));
        w.hurt = true;
        assertEquals(Kind.ENEMY_NEUTRAL_HURT, w.kind(Mob.of("minecraft:enderman").asEnemy().asNeutral()));
        assertTrue(Kind.ENEMY_NEUTRAL_HURT.counts());
    }

    @Test
    void theSpiderLungerCountsOnlyThroughTheList() {
        Mob lunger = Mob.of("arphex:spider_lunger");        // a TamableAnimal, not an Enemy
        assertEquals(Kind.NOT_HOSTILE, new World().kind(lunger));
        assertFalse(new World().kind(lunger).counts());
        World w = new World(HostileRules.DEFAULTS.toArray(new String[0]));
        assertEquals(Kind.HOSTILE_LIST, w.kind(lunger));
        assertTrue(w.kind(lunger).counts());
        assertEquals(Kind.NOT_HOSTILE, w.kind(Mob.of("arphex:spider_jump")), "spider_jump is not seeded");
    }

    @Test
    void petsAndPlayersAreNeverCounted() {
        World w = new World("arphex:spider_lunger", "minecraft:wolf");
        assertEquals(Kind.PROTECTED, w.kind(Mob.of("arphex:spider_lunger").asTame()), "listed but tamed");
        assertEquals(Kind.PROTECTED, w.kind(Mob.of("minecraft:wolf").asTame()));
        assertEquals(Kind.PROTECTED, w.kind(Mob.of("minecraft:horse").asOwned()), "an owned horse");
        Mob pet = Mob.of("minecraft:wolf").asTame();
        w.hit(pet);
        assertEquals(Kind.PROTECTED, w.kind(pet), "a pet that hit the bot is still protected");
        assertEquals(Kind.PLAYER, w.kind(Mob.of("minecraft:player").asPlayer().asEnemy()));
        assertFalse(Kind.PROTECTED.counts());
        assertFalse(Kind.PLAYER.counts());
        assertTrue(HostileRules.protectedMob(true, false, false));
        assertTrue(HostileRules.protectedMob(false, true, false));
        assertTrue(HostileRules.protectedMob(false, false, true));
        assertFalse(HostileRules.protectedMob(false, false, false));
    }

    @Test
    void retaliationLastsTheHurtWindowThenStops() {
        World w = new World();
        Mob goat = Mob.of("minecraft:goat").asNeutral();
        assertEquals(Kind.NOT_HOSTILE, w.kind(goat));
        w.now = 1000;
        w.hit(goat);
        assertEquals(Kind.RETALIATION, w.kind(goat));
        w.now = 1000 + HostileRules.RETALIATE_TICKS - 1;
        assertEquals(Kind.RETALIATION, w.kind(goat));
        w.now = 1000 + HostileRules.RETALIATE_TICKS;
        w.hurt = false;
        assertEquals(Kind.NOT_HOSTILE, w.kind(goat), "dropped after the window");
        // a new hit starts it again
        w.hit(goat);
        assertEquals(Kind.RETALIATION, w.kind(goat));
        assertEquals(100, HostileRules.RETALIATE_TICKS);
    }

    @Test
    void neverRetaliatesAgainstVillagersGolemsOrPlayers() {
        World w = new World();
        Mob golem = Mob.of("minecraft:iron_golem").asPeaceful();
        w.hit(golem);
        assertEquals(Kind.PEACEFUL, w.kind(golem));
        assertFalse(w.kind(golem).counts());
        Mob player = Mob.of("minecraft:player").asPlayer();
        w.hit(player);
        assertFalse(w.hits.containsKey("minecraft:player"), "a player's hit is not even recorded");
        assertEquals(Kind.PLAYER, w.kind(player));
    }

    @Test
    void whichHitsAreRecorded() {
        assertFalse(HostileRules.recordAttacker(false, false, false, false), "fall, fire, lava: no entity");
        assertFalse(HostileRules.recordAttacker(true, true, true, true), "the bot itself");
        assertFalse(HostileRules.recordAttacker(true, false, false, false), "a stray arrow with no shooter, a falling block");
        assertFalse(HostileRules.recordAttacker(true, false, true, true), "a player");
        assertTrue(HostileRules.recordAttacker(true, false, true, false));
        assertFalse(HostileRules.retaliating(null, 5));
        assertFalse(HostileRules.retaliating(10L, 5), "a stamp from the future (a new world)");
    }

    @Test
    void strongStrangersAreFledFrom() {
        assertTrue(HostileRules.strong(150, 7), "the Spider Lunger's 150 hp against an iron sword");
        assertTrue(HostileRules.strong(100, 50));
        assertFalse(HostileRules.strong(40, 7), "spider_jump's 40 hp against an iron sword: fight");
        assertTrue(HostileRules.strong(40, 1), "40 hp bare-handed: retreat");
        assertTrue(HostileRules.strong(10, 1), "a goat bare-handed: 10 hp = 10 fists");
        assertFalse(HostileRules.strong(8, 1), "a wild wolf bare-handed: fight");
        assertFalse(HostileRules.strong(20, 5), "a zombie-sized stranger with a stone sword");
        assertTrue(HostileRules.strong(20, 0), "a weapon hit under 1 counts as 1");
    }

    // ---- ids and the file ----

    @Test
    void normalizesIds() {
        assertEquals("minecraft:zombie", HostileRules.normalize(" Zombie "));
        assertEquals("arphex:spider_lunger", HostileRules.normalize("ARPHEX:Spider_Lunger"));
        assertNull(HostileRules.normalize(""));
        assertNull(HostileRules.normalize(null));
        assertNull(HostileRules.normalize("a:b:c"));
        assertNull(HostileRules.normalize("bad id"));
        assertNull(HostileRules.normalize(":x"));
    }

    @Test
    void fileRoundTripAndBrokenFiles() {
        HostileRules.Parsed none = HostileRules.parse(null);
        assertEquals(HostileRules.DEFAULTS, none.ids());
        assertFalse(none.failed());
        assertEquals(List.of("arphex:spider_lunger"), HostileRules.DEFAULTS);
        List<String> ids = List.of("arphex:spider_lunger", "arphex:spider_jump");
        HostileRules.Parsed back = HostileRules.parse(HostileRules.toJson(ids));
        assertEquals(ids, back.ids());
        assertNull(back.note());
        HostileRules.Parsed empty = HostileRules.parse(HostileRules.toJson(List.of()));
        assertEquals(List.of(), empty.ids(), "an emptied list stays empty (not re-seeded)");
        HostileRules.Parsed broken = HostileRules.parse("{\"hostile\": [");
        assertTrue(broken.failed());
        assertEquals(HostileRules.DEFAULTS, broken.ids());
        assertTrue(broken.note().contains("not valid JSON"), broken.note());
        assertTrue(HostileRules.parse("[1,2]").failed());
        assertTrue(HostileRules.parse("{\"hostile\": 3}").failed());
        HostileRules.Parsed some = HostileRules.parse("{\"hostile\": [\"Zombie\", \"bad id\", 5, \"zombie\"]}");
        assertEquals(List.of("minecraft:zombie"), some.ids());
        assertFalse(some.failed());
        assertTrue(some.note().startsWith("ignored 2 bad entries"), some.note());
        assertEquals(HostileRules.DEFAULTS, HostileRules.parse("{}").ids(), "no key yet: the defaults");
    }

    @Test
    void fileCapsAtOneHundred() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 120; i++) many.add("mod:mob_" + i);
        HostileRules.Parsed p = HostileRules.parse(HostileRules.toJson(many));
        assertEquals(HostileRules.CAP, p.ids().size());
        assertTrue(p.note().contains("over 100"), p.note());
    }

    // ---- the verb ----

    @Test
    void listAddRemove() {
        List<String> cur = HostileRules.DEFAULTS;
        HostileRules.Result r = HostileRules.command("", cur, REGISTRY::contains);
        assertNull(r.changed());
        assertTrue(r.text().contains("arphex:spider_lunger"), r.text());
        assertEquals(r.text(), HostileRules.command(" list ", cur, REGISTRY::contains).text());

        r = HostileRules.command("add arphex:spider_jump", cur, REGISTRY::contains);
        assertEquals(List.of("arphex:spider_lunger", "arphex:spider_jump"), r.changed());
        assertTrue(r.text().startsWith("ok: added arphex:spider_jump"), r.text());
        cur = r.changed();

        r = HostileRules.command("add zombie, wolf  arphex:nope bad!id arphex:spider_jump", cur, REGISTRY::contains);
        assertEquals(List.of("arphex:spider_lunger", "arphex:spider_jump", "minecraft:zombie", "minecraft:wolf"), r.changed());
        assertTrue(r.text().contains("unknown entity id arphex:nope"), r.text());
        assertTrue(r.text().contains("\"bad!id\" is not an entity id"), r.text());
        assertTrue(r.text().contains("arphex:spider_jump is on the list already"), r.text());
        cur = r.changed();

        r = HostileRules.command("add arphex:nope", cur, REGISTRY::contains);
        assertNull(r.changed());
        assertTrue(r.text().startsWith("error: nothing added - unknown entity id arphex:nope"), r.text());

        r = HostileRules.command("remove zombie arphex:gone", cur, REGISTRY::contains);
        assertEquals(List.of("arphex:spider_lunger", "arphex:spider_jump", "minecraft:wolf"), r.changed());
        assertTrue(r.text().contains("arphex:gone is not on the list"), r.text());

        r = HostileRules.command("remove arphex:spider_lunger arphex:spider_jump minecraft:wolf", r.changed(), REGISTRY::contains);
        assertEquals(List.of(), r.changed());
        assertTrue(r.text().contains("hostile list: empty"), r.text());

        assertTrue(HostileRules.command("add", cur, REGISTRY::contains).text().startsWith("error: defend hostile add <entity id>"));
        assertTrue(HostileRules.command("frob x", cur, REGISTRY::contains).text().startsWith("error: defend hostile [list]"));
    }

    @Test
    void addStopsAtTheCap() {
        List<String> full = new ArrayList<>();
        for (int i = 0; i < HostileRules.CAP; i++) full.add("mod:mob_" + i);
        HostileRules.Result r = HostileRules.command("add zombie", full, REGISTRY::contains);
        assertNull(r.changed());
        assertTrue(r.text().contains("I keep at most 100"), r.text());
    }
}
