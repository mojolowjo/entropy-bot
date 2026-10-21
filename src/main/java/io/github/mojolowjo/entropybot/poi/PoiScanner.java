package io.github.mojolowjo.entropybot.poi;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.Map;

/**
 * The points-of-interest kinds (docs/BOT_PLAN.md 5.6): which block is which kind (Lootr's per-player containers are "loot
 * chest"), read by the chunk pass into each chunk's summary. rm-mining: the per-chunk "found a ..." writes and lines are
 * gone; pois.json is refreshed on demand when a reader asks ({@code ChunkPass.refreshPois}).
 * Rules kept in the pass: rails are a mineshaft only below y 45; 12 lava sources in one section are a lava lake.
 */
public final class PoiScanner {
    private static volatile Map<Block, String> kinds;

    private PoiScanner() {}

    /** Block -> POI kind (built once from the registry; client thread). */
    public static Map<Block, String> kinds() {
        Map<Block, String> k = kinds;
        if (k != null) return k;
        k = new HashMap<>();
        k.put(Blocks.SPAWNER, "spawner");
        k.put(Blocks.TRIAL_SPAWNER, "trial chamber");
        k.put(Blocks.VAULT, "trial chamber");
        k.put(Blocks.NETHER_PORTAL, "nether portal");
        k.put(Blocks.END_PORTAL_FRAME, "stronghold");
        k.put(Blocks.BELL, "village");
        k.put(Blocks.BUDDING_AMETHYST, "geode");
        k.put(Blocks.DIAMOND_ORE, "diamonds");
        k.put(Blocks.DEEPSLATE_DIAMOND_ORE, "diamonds");
        k.put(Blocks.EMERALD_ORE, "emeralds");
        k.put(Blocks.DEEPSLATE_EMERALD_ORE, "emeralds");
        k.put(Blocks.ANCIENT_DEBRIS, "ancient debris");
        k.put(Blocks.SUSPICIOUS_SAND, "archaeology");
        k.put(Blocks.SUSPICIOUS_GRAVEL, "archaeology");
        k.put(Blocks.RAIL, "mineshaft");
        k.put(Blocks.LAVA, "lava lake");
        for (Block b : BuiltInRegistries.BLOCK) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
            // Lootr's per-player containers (chests, barrels, shulkers, carts): a structure's loot
            if (id.getNamespace().equals("lootr") && !id.getPath().contains("trophy")) k.put(b, "loot chest");
        }
        kinds = k;
        return k;
    }
}
