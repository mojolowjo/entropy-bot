package io.github.mojolowjo.entropybot.poi;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.events.EventRing;
import io.github.mojolowjo.entropybot.guard.Guard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records points of interest from the loaded chunks around the bot while it works (docs/BOT_PLAN.md 5.6,
 * "poi mode scan"; the owner is fine with it reading chunk data). One chunk per tick, spiralling out to
 * {@link #RADIUS} chunks; a chunk is looked at again after {@link #RESCAN} ticks. A section is only read
 * block by block when its palette says it may hold something interesting, so a sweep costs next to nothing.
 */
public final class PoiScanner {
    private static final Logger LOG = LogUtils.getLogger();
    static final int RADIUS = 4;
    static final long RESCAN = 6000;
    static final int LAVA_LAKE = 12;        // lava sources in one section for a "lava lake"
    static final int MINESHAFT_BELOW = 45;  // rails under this height are a mineshaft's

    private final Pois pois;
    private final EventRing events;
    private Map<Block, String> kinds;
    private final Map<Long, Long> scanned = new HashMap<>();
    private int[][] spiral;
    private int cursor;
    private String dim;
    private int errors;

    public PoiScanner(Pois pois, EventRing events) {
        this.pois = pois;
        this.events = events;
    }

    private void build() {
        Map<Block, String> k = new HashMap<>();
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
        // the spiral of chunk offsets, nearest first
        java.util.List<int[]> s = new java.util.ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dz = -RADIUS; dz <= RADIUS; dz++) s.add(new int[] { dx, dz });
        s.sort(java.util.Comparator.comparingInt(a -> a[0] * a[0] + a[1] * a[1]));
        spiral = s.toArray(new int[0][]);
    }

    /** Once per client tick. Never throws. */
    public void tick(long tick) {
        try {
            step(tick);
        } catch (Throwable t) {
            errors++;
            if (errors <= 5 || errors % 1200 == 0) LOG.error("[entropybot] poi scan error #{}: {}", errors, t.toString());
        }
    }

    private void step(long tick) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        if (kinds == null) build();
        String d = Guard.dimOf(mc.level);
        if (!d.equals(dim)) {
            dim = d;
            scanned.clear();
        }
        int pcx = p.getBlockX() >> 4, pcz = p.getBlockZ() >> 4;
        // package H: the scanned-chunk notes grew with every chunk of a session; drop the stale ones (as TerrainMap does)
        if (scanned.size() > 8192) scanned.values().removeIf(at -> tick - at >= RESCAN);
        // the next chunk in the spiral that is loaded and not looked at lately
        for (int tries = 0; tries < spiral.length; tries++) {
            int[] o = spiral[cursor++ % spiral.length];
            int cx = pcx + o[0], cz = pcz + o[1];
            long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
            Long at = scanned.get(key);
            if (at != null && tick - at < RESCAN) continue;
            LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, false);
            if (chunk == null) continue;
            scanned.put(key, tick);
            scanChunk(chunk, cx, cz, tick);
            return;
        }
    }

    private void scanChunk(LevelChunk chunk, int cx, int cz, long tick) {
        LevelChunkSection[] sections = chunk.getSections();
        int minSection = chunk.getMinSection();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s == null || s.hasOnlyAir() || !s.maybeHas(st -> kinds.containsKey(st.getBlock()))) continue;
            int baseY = (minSection + i) << 4;
            Map<String, int[]> found = new LinkedHashMap<>();
            int lava = 0;
            int[] lavaAt = null;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState st = s.getBlockState(x, y, z);
                String kind = kinds.get(st.getBlock());
                if (kind == null) continue;
                int wx = (cx << 4) + x, wy = baseY + y, wz = (cz << 4) + z;
                if (kind.equals("lava lake")) {
                    if (st.getFluidState().isSource()) {
                        lava++;
                        if (lavaAt == null) lavaAt = new int[] { wx, wy, wz };
                    }
                    continue;
                }
                if (kind.equals("mineshaft") && wy >= MINESHAFT_BELOW) continue;
                found.putIfAbsent(kind, new int[] { wx, wy, wz });
            }
            if (lava >= LAVA_LAKE && lavaAt != null) found.put("lava lake", lavaAt);
            for (Map.Entry<String, int[]> e : found.entrySet()) {
                int[] at = e.getValue();
                Pois.Poi n = pois.saw(e.getKey(), at[0], at[1], at[2], dim, System.currentTimeMillis(), tick, null);
                if (n != null) {
                    String line = "found " + article(n.kind()) + " at " + at[0] + " " + at[1] + " " + at[2] + " (poi " + n.id() + ")";
                    events.push("poi", line, null);
                    LOG.info("[entropybot] {}", line);
                }
            }
        }
    }

    static String article(String kind) {
        if (kind.equals("diamonds") || kind.equals("emeralds") || kind.equals("ancient debris") || kind.equals("archaeology")) return kind;
        return ("aeiou".indexOf(kind.charAt(0)) >= 0 ? "an " : "a ") + kind;
    }
}
