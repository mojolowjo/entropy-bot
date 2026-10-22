package io.github.mojolowjo.entropybot.sense;

import java.util.HashMap;
import java.util.Map;

/** A fake chunk for the sense tests: block names by position (default "stone" below 64, "air" above), sections -4..19. */
final class FakeChunk implements SummaryBuilder.ChunkSource<String> {
    final int cx, cz;
    final Map<String, String> blocks = new HashMap<>();
    int surface = 63;

    FakeChunk(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
    }

    String at(int x, int y, int z) {
        String b = blocks.get(x + " " + y + " " + z);
        if (b != null) return b;
        return y <= surface ? "stone" : "air";
    }

    void set(int x, int y, int z, String b) { blocks.put(x + " " + y + " " + z, b); }

    @Override public int sections() { return 24; }

    @Override public int minSection() { return -4; }

    @Override public boolean skip(int i) {
        int y0 = (minSection() + i) << 4;
        if (y0 > surface) {
            for (String k : blocks.keySet()) {
                int y = Integer.parseInt(k.split(" ")[1]);
                if (y >= y0 && y < y0 + 16) return false;
            }
            return true;        // only air
        }
        return false;
    }

    @Override public String state(int i, int lx, int ly, int lz) {
        return at((cx << 4) + lx, ((minSection() + i) << 4) + ly, (cz << 4) + lz);
    }

    @Override public int top(int lx, int lz) {
        int x = (cx << 4) + lx, z = (cz << 4) + lz;
        for (int y = 319; y >= -64; y--) if (!at(x, y, z).equals("air")) return y;
        return ChunkSummary.NO_TOP;
    }

    static final SummaryBuilder.Classifier<String> CLS = FakeChunk::cls;

    static SummaryBuilder.Cls cls(String b) {
        return switch (b) {
            case "iron_ore" -> new SummaryBuilder.Cls("iron", "minecraft:iron_ore", null, false);
            case "coal_ore" -> new SummaryBuilder.Cls("coal", "minecraft:coal_ore", null, false);
            case "deepslate_iron_ore" -> new SummaryBuilder.Cls("iron", "minecraft:deepslate_iron_ore", null, false);
            case "diamond_ore" -> new SummaryBuilder.Cls("diamond", "minecraft:diamond_ore", "diamonds", false);
            case "nickel_ore" -> new SummaryBuilder.Cls("oritech:nickel", "oritech:nickel_ore", null, false);
            case "spawner" -> new SummaryBuilder.Cls(null, "minecraft:spawner", "spawner", false);
            case "rail" -> new SummaryBuilder.Cls(null, "minecraft:rail", "mineshaft", false);
            case "lava" -> new SummaryBuilder.Cls(null, "minecraft:lava", null, true);
            default -> SummaryBuilder.NONE;
        };
    }

    ChunkSummary read() { return SummaryBuilder.build("minecraft:overworld", cx, cz, this, CLS, 1000); }
}
