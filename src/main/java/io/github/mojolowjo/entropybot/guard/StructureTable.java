package io.github.mojolowjo.entropybot.guard;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Quiet;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Structure;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.neoforged.neoforge.common.Tags;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * STRUCTURE_BLOCKS.md (owner 2026-10-23, RULES 16): the structure fact of every block, from the game's data only (no
 * name of ours): the block tags the ruling names, a block entity, a recipe type's station (the recipes' toast symbol),
 * and "a crafting recipe makes it and world gen never places it" (the configured features and the noise settings'
 * surface rules, read from the singleplayer server's registries). Ores ({@code c:ores}) and logs ({@code #logs}) are
 * never structure blocks.
 *
 * <p>Rule 15: before the client has a level with recipes (tags come in the configuration phase, before the recipes),
 * the table is absent and every block answers {@link Structure#NOT_LOADED} (never cached as "no"); it is built on the
 * client tick once per world and again when the recipe count changes. On a server's client the world-gen data is not
 * sent: crafted blocks then count as structure blocks (fail closed), said once per world. The pathfinder thread reads
 * the immutable map only. Loader notes: vanilla registries, tags and codecs; NeoForge only for {@code Tags.Blocks.ORES}.
 */
public final class StructureTable {
    private static final Logger LOG = LogUtils.getLogger();

    private StructureTable() {}

    /** The ruling's block tags (#minecraft:...), the game's own tag keys. */
    private static final List<TagKey<Block>> STRUCTURE_TAGS = List.of(BlockTags.STAIRS, BlockTags.SLABS, BlockTags.DOORS,
            BlockTags.TRAPDOORS, BlockTags.FENCES, BlockTags.FENCE_GATES, BlockTags.WALLS, BlockTags.BEDS, BlockTags.SIGNS,
            BlockTags.WALL_SIGNS, BlockTags.ALL_HANGING_SIGNS, BlockTags.BANNERS, BlockTags.CANDLES, BlockTags.IMPERMEABLE,
            BlockTags.WOOL, BlockTags.RAILS, BlockTags.BUTTONS, BlockTags.PRESSURE_PLATES, BlockTags.FLOWER_POTS);

    private static volatile Map<Block, Structure> table;
    private static volatile Level builtFor;
    private static volatile int builtRecipes = -1;
    private static volatile boolean warnedNotLoaded;

    /** The block's structure fact; NOT_LOADED while the table is not built (said once per world). Any thread. */
    public static Structure of(Block b) {
        Map<Block, Structure> t = table;
        if (t == null) {
            if (!warnedNotLoaded) {
                warnedNotLoaded = true;
                Quiet.line("structure facts: asked before the game's tags and recipes are in - every break is refused as not loaded yet until they are");
            }
            return Structure.NOT_LOADED;
        }
        Structure s = t.get(b);
        return s == null ? Structure.NOT_LOADED : s;
    }

    /** Client tick: drop the table without a level; (re)build it for a new level or a changed recipe set. Never throws. */
    public static void tick(Minecraft mc) {
        try {
            Level lv = mc == null ? null : mc.level;
            if (lv == null) {
                if (builtFor != null || table != null) { table = null; builtFor = null; builtRecipes = -1; warnedNotLoaded = false; }
                return;
            }
            int n = lv.getRecipeManager().getRecipes().size();
            if (lv == builtFor && n == builtRecipes) return;
            if (lv != builtFor) { table = null; warnedNotLoaded = false; OwnPlaced.newWorld(); }
            builtFor = lv;
            builtRecipes = n;
            if (n == 0) return;                       // the recipes are not in yet: stays not loaded
            table = build(lv);
        } catch (RuntimeException e) {
            Quiet.warn("structure facts: the table could not be built, every break stays refused as not loaded yet", e);
        }
    }

    private static Map<Block, Structure> build(Level lv) {
        Set<Item> stations = new HashSet<>(), crafted = new HashSet<>();
        for (var h : lv.getRecipeManager().getRecipes()) {
            var r = h.value();
            try {
                ItemStack sym = r.getToastSymbol();
                if (sym != null && !sym.isEmpty()) stations.add(sym.getItem());
                if (r.getType() == RecipeType.CRAFTING) {
                    ItemStack out = r.getResultItem(lv.registryAccess());
                    if (out != null && !out.isEmpty()) crafted.add(out.getItem());
                }
            } catch (RuntimeException e) {
                Quiet.warn("structure facts: a recipe could not be read, its result counts as not crafted", e);
            }
        }
        Set<String> gen = worldGenBlocks();
        Map<Block, Structure> out = new IdentityHashMap<>();
        int yes = 0, tagged = 0, be = 0, st = 0, cr = 0;
        for (Block b : BuiltInRegistries.BLOCK) {
            BlockState s = b.defaultBlockState();
            boolean t = false;
            for (TagKey<Block> k : STRUCTURE_TAGS) if (s.is(k)) { t = true; break; }
            boolean oreOrLog = s.is(Tags.Blocks.ORES) || s.is(BlockTags.LOGS);
            boolean entity = b instanceof EntityBlock;
            Item it = b.asItem();
            boolean station = it != Items.AIR && stations.contains(it);
            boolean craft = it != Items.AIR && crafted.contains(it);
            Boolean placed = gen == null ? null : gen.contains(BuiltInRegistries.BLOCK.getKey(b).toString());
            Structure v = BreakPolicy.structureOf(true, t, oreOrLog, entity, station, craft, placed);
            out.put(b, v);
            if (v == Structure.YES) {
                yes++;
                if (t) tagged++; else if (entity) be++; else if (station) st++; else cr++;
            }
        }
        LOG.info("[entropybot] structure facts: {} of {} blocks are structure blocks ({} by tag, {} block entity, {} station, {} crafted and not world-gen); world gen: {}",
                yes, out.size(), tagged, be, st, cr, gen == null ? "not available on a server's client (crafted blocks count as structure blocks)" : gen.size() + " blocks placed");
        return Collections.unmodifiableMap(out);
    }

    /** The block ids world gen places (configured features, the noise settings' surface rules), or null without a singleplayer server. */
    private static Set<String> worldGenBlocks() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return null;
        var ra = server.registryAccess();
        var ops = RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, ra);
        Set<String> names = new HashSet<>();
        int failed = 0;
        for (ConfiguredFeature<?, ?> f : ra.registryOrThrow(Registries.CONFIGURED_FEATURE)) {
            var j = ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, f).result();
            if (j.isPresent()) collect(j.get(), names); else failed++;
        }
        for (NoiseGeneratorSettings n : ra.registryOrThrow(Registries.NOISE_SETTINGS)) {
            var j = NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, n).result();
            if (j.isPresent()) collect(j.get(), names); else failed++;
        }
        if (failed > 0) LOG.warn("[entropybot] structure facts: {} world-gen entries could not be read; their blocks count as never world-gen", failed);
        return names;
    }

    /** Every block state's "Name" in the encoded data. */
    private static void collect(JsonElement e, Set<String> names) {
        if (e == null) return;
        if (e.isJsonArray()) { for (JsonElement c : e.getAsJsonArray()) collect(c, names); return; }
        if (!e.isJsonObject()) return;
        var o = e.getAsJsonObject();
        for (var en : o.entrySet()) {
            if (en.getKey().equals("Name") && en.getValue().isJsonPrimitive()) names.add(en.getValue().getAsString());
            else collect(en.getValue(), names);
        }
    }
}
