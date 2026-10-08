package io.github.mojolowjo.entropycompanion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 0.3.1 (assist): the action guess and the report's new fields. */
class OwnerActionsTest {
    static final long NOW = 1_000_000;
    static final long NEVER = Long.MIN_VALUE / 2;

    static OwnerActions.Ev ev(String id, long ago) { return new OwnerActions.Ev(id, 1, 64, 2, NOW - ago); }

    static String g(String held, OwnerActions.Ev b, OwnerActions.Ev p) { return OwnerActions.guess(held, b, p, NEVER, NOW); }

    @Test
    void guesses() {
        assertEquals("chopping", g("minecraft:iron_axe", ev("minecraft:oak_log", 2000), null));
        assertEquals("chopping", g("", ev("minecraft:birch_leaves", 2000), null));
        assertEquals("mining", g("minecraft:stone_pickaxe", ev("minecraft:stone", 1000), null));
        assertEquals("mining", g("", ev("minecraft:deepslate_iron_ore", 1000), null));
        assertEquals("mining", g("minecraft:iron_shovel", ev("minecraft:dirt", 1000), null), "dirt with a shovel");
        assertEquals("building", g("", ev("minecraft:oak_planks", 1000), null), "a built block broken by hand");
        assertEquals("farming", g("minecraft:iron_hoe", ev("minecraft:wheat", 1000), null));
        assertEquals("farming", g("", null, ev("minecraft:wheat_seeds", 1000)));
        assertEquals("building", g("minecraft:cobblestone", null, ev("minecraft:cobblestone", 1000)));
        assertEquals("building", g("", ev("minecraft:stone", 3000), ev("minecraft:cobblestone", 1000)), "the newer event wins");
        assertEquals("mining", g("", ev("minecraft:stone", 1000), ev("minecraft:cobblestone", 3000)));
        assertEquals("idle", g("minecraft:iron_pickaxe", ev("minecraft:stone", 11_000), null), "older than 10 s");
        assertEquals("fighting", g("minecraft:iron_sword", null, null), "a weapon held");
        assertEquals("idle", g("", null, null));
        assertEquals("fighting", OwnerActions.guess("", ev("minecraft:oak_log", 500), null, NOW - 3000, NOW), "a hit within 10 s wins");
    }

    @Test
    void badIdsAreNotRecorded() {
        OwnerActions a = new OwnerActions();
        a.broke("Not An Id", 0, 0, 0, NOW);
        assertNull(a.broke());
        a.broke("minecraft:oak_log", 3, 70, -4, NOW);
        assertEquals("minecraft:oak_log", a.broke().id());
    }

    @Test
    void reportCarriesTheNewFields() {
        OwnerState os = new OwnerState();
        OwnerState.State st = new OwnerState.State("mojolowjo", 1, 64, 2, "minecraft:overworld", 20, 20, 20, 5, 0, 0, 0,
                "minecraft:cobblestone", List.of(new OwnerState.Stack("minecraft:cobblestone", 7, false, true)));
        OwnerState.Extra x = new OwnerState.Extra(ev("minecraft:stone", 1500), ev("minecraft:cobblestone", 500),
                new OwnerActions.Target("minecraft:zombie", 5, 64, 6, true), "building");
        JsonObject o = JsonParser.parseString(os.json(st, x, NOW)).getAsJsonObject();
        assertEquals("minecraft:stone", o.getAsJsonObject("broke").get("id").getAsString());
        assertEquals(1500, o.getAsJsonObject("broke").get("age").getAsLong());
        assertEquals(64, o.getAsJsonObject("broke").get("y").getAsInt());
        assertEquals(7, o.getAsJsonObject("placed").get("left").getAsInt(), "how many the owner has left");
        assertEquals("entity", o.getAsJsonObject("target").get("kind").getAsString());
        assertEquals("building", o.get("action").getAsString());
        assertFalse(JsonParser.parseString(new OwnerState().json(st, NOW)).getAsJsonObject().has("action"), "the old form has none");
    }
}
