package io.github.mojolowjo.entropybot.clear;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 0.25.1 path.oreDetour: the ore on the way home (in sight, minable, safe, inside the areas, one vein of 8 at most). */
class OreDetourTest {
    static final Veins.Area ALL = (x, z) -> true;

    @Test
    void picksTheNearestExposedVeinAndCapsIt() {
        FakeWorld w = new FakeWorld();           // stone up to y 52, the bot stands at y 53
        w.set(3, 52, 0, "iron_ore");
        w.set(3, 51, 0, "iron_ore");
        w.set(5, 40, 0, "diamond_ore");          // buried: not in sight
        List<Pos> v = OreDetour.pick(w, 0, 53, 0, 8, id -> true, ALL);
        assertEquals(List.of(new Pos(3, 52, 0), new Pos(3, 51, 0)), v);
        assertTrue(OreDetour.line(w, v, 0, 53, 0).startsWith("ore on the way home: 2 iron_ore at 3 52 0 ("), OreDetour.line(w, v, 0, 53, 0));
        w.fill(-6, -2, 44, 52, -2, 2, "coal_ore");
        List<Pos> big = OreDetour.pick(w, -4, 53, 0, 8, id -> true, ALL);
        assertEquals(OreDetour.CAP, big.size(), "one vein, 8 blocks at most");
    }

    @Test
    void leavesWhatItMayNotOrCannotMine() {
        FakeWorld w = new FakeWorld();
        w.set(3, 52, 0, "iron_ore");
        assertTrue(OreDetour.pick(w, 0, 53, 0, 0, id -> true, ALL).isEmpty(), "path.oreDetour 0 = off");
        assertTrue(OreDetour.pick(w, 0, 53, 0, 2, id -> true, ALL).isEmpty(), "out of the radius");
        assertTrue(OreDetour.pick(w, 0, 53, 0, 8, id -> !id.contains("iron"), ALL).isEmpty(), "the pickaxe can't mine it");
        assertTrue(OreDetour.pick(w, 0, 53, 0, 8, id -> true, (x, z) -> x < 3).isEmpty(), "outside the areas");
        w.set(4, 52, 0, "water");
        assertTrue(OreDetour.pick(w, 0, 53, 0, 8, id -> true, ALL).isEmpty(), "water beside it");
        w.set(4, 52, 0, "stone");
        w.set(3, 53, 0, "gravel");
        w.set(3, 52, 1, "air");                  // still in sight from the side
        assertTrue(OreDetour.pick(w, 0, 53, 0, 8, id -> true, ALL).isEmpty(), "gravel above it");
    }
}
