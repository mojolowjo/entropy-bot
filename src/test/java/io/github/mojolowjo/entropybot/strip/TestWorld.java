package io.github.mojolowjo.entropybot.strip;

import io.github.mojolowjo.entropybot.clear.ClearBox;
import io.github.mojolowjo.entropybot.clear.ClearRules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The offline simulator's world for the strip mine's rules (test/sim.js: stone up to y 52, air above), block by
 * block: names as the sim's ("stone", "iron_ore"). The same rules as the clear engine's FakeWorld, plus the tool
 * tiers (deepslate and diamond ores need iron, obsidian diamond) and what a placement may replace.
 */
final class TestWorld implements StripWorld {
    static final Set<String> NONSOLID = Set.of("air", "water", "lava", "short_grass", "torch");
    static final Set<String> CONTAINERS = Set.of("chest", "barrel", "furnace", "crafting_table");

    final Map<String, String> blocks = new HashMap<>();
    final Set<String> unloaded = new HashSet<>();

    String get(int x, int y, int z) {
        String n = blocks.get(x + " " + y + " " + z);
        return n != null ? n : y <= 52 ? "stone" : "air";
    }

    TestWorld set(int x, int y, int z, String name) {
        blocks.put(x + " " + y + " " + z, name);
        return this;
    }

    /** Fills a box (a dug tunnel: "air"). */
    TestWorld fill(ClearBox b, String name) {
        for (int x = b.x1(); x <= b.x2(); x++) for (int y = b.y1(); y <= b.y2(); y++) for (int z = b.z1(); z <= b.z2(); z++) set(x, y, z, name);
        return this;
    }

    /** The sim's strip world: the mine starts at ox 40 0 going north (strip/strip2/stripout/pit, SimWorlds.strip). */
    static TestWorld strip(int ox, boolean bedrock) {
        TestWorld w = new TestWorld();
        if (bedrock) w.set(0, 40, -5, "bedrock");
        w.set(ox, 40, 0, "air").set(ox, 41, 0, "air");
        w.set(ox - 7, 40, -3, "iron_ore");
        w.set(ox + 5, 40, -4, "coal_ore");
        w.set(ox - 2, 41, -8, "diamond_ore");
        w.set(ox + 8, 42, -6, "lava");
        w.set(ox, 41, -5, "iron_ore");
        return w;
    }

    @Override public String id(int x, int y, int z) { return ClearRules.fullId(get(x, y, z)); }

    @Override public String name(int x, int y, int z) { return get(x, y, z); }

    @Override public boolean air(int x, int y, int z) { return get(x, y, z).equals("air"); }

    @Override public boolean fluid(int x, int y, int z) {
        String n = get(x, y, z);
        return n.equals("water") || n.equals("lava");
    }

    @Override public boolean blockEntity(int x, int y, int z) { return CONTAINERS.contains(get(x, y, z)); }

    @Override public boolean unbreakable(int x, int y, int z) { return get(x, y, z).equals("bedrock"); }

    @Override public boolean builtBlock(int x, int y, int z) {
        if (ore(x, y, z)) return false;
        return blockEntity(x, y, z) || ClearRules.builtId(id(x, y, z));
    }

    @Override public boolean avoided(int x, int y, int z) { return false; }

    @Override public boolean ore(int x, int y, int z) { return get(x, y, z).endsWith("_ore"); }

    @Override public boolean noCollision(int x, int y, int z) { return NONSOLID.contains(get(x, y, z)); }

    @Override public boolean fullBlock(int x, int y, int z) { return !NONSOLID.contains(get(x, y, z)); }

    @Override public double collisionHeight(int x, int y, int z) {
        String n = get(x, y, z);
        return NONSOLID.contains(n) ? 0 : n.equals("moss_carpet") ? 0.0625 : 1;
    }

    @Override public int blockLight(int x, int y, int z) { return 0; }

    @Override public Hit clip(double fx, double fy, double fz, double tx, double ty, double tz) { return null; }

    @Override public String toolNeed(int x, int y, int z) {
        String n = get(x, y, z);
        if (n.equals("obsidian")) return "diamond";
        if (n.startsWith("deepslate_") && n.endsWith("_ore") || n.equals("diamond_ore") || n.equals("gold_ore") || n.equals("redstone_ore")) return "iron";
        return "stone";
    }

    @Override public boolean needsCorrectTool(int x, int y, int z) {
        String n = get(x, y, z);
        return n.equals("stone") || n.endsWith("_ore") || n.equals("obsidian") || n.equals("deepslate");
    }

    @Override public boolean replaceable(int x, int y, int z) {
        String n = get(x, y, z);
        return n.equals("air") || n.equals("water") || n.equals("lava") || n.equals("short_grass");
    }

    @Override public boolean loaded(int x, int y, int z) { return !unloaded.contains(x + " " + z); }
}
