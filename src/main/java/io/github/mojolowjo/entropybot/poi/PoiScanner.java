package io.github.mojolowjo.entropybot.poi;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.events.EventRing;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * The points-of-interest reader (docs/BOT_PLAN.md 5.6). Since 0.28.0 it no longer walks a spiral of chunks itself: the
 * one chunk pass ({@code sense/ChunkPass}) reads every chunk once when it arrives and patches it per block, and hands the
 * POI blocks it finds here ({@link #found}) and the ones that went ({@link Pois#gone}). This class keeps the kinds table
 * (which block is which kind; Lootr's per-player containers are "loot chest") and the "found a ..." line.
 * Rules kept in the pass: rails are a mineshaft only below y 45; 12 lava sources in one section are a lava lake.
 */
public final class PoiScanner {
    private static final Logger LOG = LogUtils.getLogger();

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

    /** A POI block the pass saw: a new point (event + log line) or a refresh of a known one within the merge distance. */
    public static Pois.Poi found(Pois pois, EventRing events, String kind, int x, int y, int z, String dim, long tick) {
        Pois.Poi n = pois.saw(kind, x, y, z, dim, System.currentTimeMillis(), tick, null);
        if (n != null) {
            String line = "found " + article(n.kind()) + " at " + x + " " + y + " " + z + " (poi " + n.id() + ")";
            if (events != null) events.push("poi", line, null);
            LOG.info("[entropybot] {}", line);
        }
        return n;
    }

    static String article(String kind) {
        if (kind.equals("diamonds") || kind.equals("emeralds") || kind.equals("ancient debris") || kind.equals("archaeology")) return kind;
        return ("aeiou".indexOf(kind.charAt(0)) >= 0 ? "an " : "a ") + kind;
    }
}
