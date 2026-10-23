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
 * surface rules and the structure templates' palettes, read from the client's jars by WorldGenReader on singleplayer and
 * on a server alike since 0.39.z26 (2); the integrated server's registries are only a check). Ores ({@code c:ores}) and logs ({@code #logs}) are
 * never structure blocks.
 *
 * <p>Rule 15: before the client has a level with recipes (tags come in the configuration phase, before the recipes),
 * the table is absent and every block answers {@link Structure#NOT_LOADED} (never cached as "no"); it is built on the
 * client tick once per world and again when the recipe count or the world-gen set changes. Until the jar read is done a
 * crafted block answers NOT_LOADED (said once per world with the jar count); a read with no world-gen data is said once. The pathfinder thread reads
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
    /** The world-gen set the table was built with (null: not loaded yet). */
    private static volatile Set<String> builtGen;
    private static volatile boolean warnedGen, warnedNoData, checked;

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
        if (s == Structure.NOT_LOADED && builtGen == null && !warnedGen) {
            warnedGen = true;
            int j = WorldGenReader.jars;
            Quiet.line("structure facts: world-gen blocks not loaded yet (reading " + (j < 0 ? "the" : String.valueOf(j)) + " jars in the background) - crafted blocks are refused as not loaded yet until the read is done");
        }
        return s == null ? Structure.NOT_LOADED : s;
    }


    /** Client tick: drop the table without a level; (re)build it for a new level or a changed recipe set. Never throws. */
    public static void tick(Minecraft mc) {
        try {
            Level lv = mc == null ? null : mc.level;
            if (lv == null) {
                if (builtFor != null || table != null) { table = null; builtFor = null; builtRecipes = -1; builtGen = null; warnedNotLoaded = false; warnedNoData = false; }
                return;
            }
            int n = lv.getRecipeManager().getRecipes().size();
            Set<String> gen = WorldGenReader.blocks;
            if (lv == builtFor && n == builtRecipes && gen == builtGen) return;
            if (lv != builtFor) {
                table = null; warnedNotLoaded = false; warnedGen = false; checked = false; OwnPlaced.newWorld();
                var files = io.github.mojolowjo.entropybot.Core.INSTANCE.files();
                WorldGenReader.start(files == null ? null : files.root(), net.minecraft.SharedConstants.getCurrentVersion().getName());
                gen = null;                           // the new world's read: not loaded until it is done
            }
            builtFor = lv;
            builtRecipes = n;
            builtGen = gen;
            if (n == 0) return;                       // the recipes are not in yet: stays not loaded
            table = build(lv, gen);
            if (gen != null && !checked) { checked = true; registryCheck(gen); }
        } catch (RuntimeException e) {
            Quiet.warn("structure facts: the table could not be built, every break stays refused as not loaded yet", e);
        }
    }

    private static Map<Block, Structure> build(Level lv, Set<String> gen) {
        if (gen != null && WorldGenReader.noData && !warnedNoData) {
            warnedNoData = true;
            Quiet.line("structure facts: no world-gen data in " + WorldGenReader.jars + " jars: crafted blocks are refused outside areas until the override file ("
                    + WorldGenFiles.OVERRIDE_FILE + ") names them");
        }
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
            Structure v = WorldGenFiles.fact(t, oreOrLog, entity, station, craft, gen, BuiltInRegistries.BLOCK.getKey(b).toString());
            out.put(b, v);
            if (v == Structure.YES) {
                yes++;
                if (t) tagged++; else if (entity) be++; else if (station) st++; else cr++;
            }
        }
        LOG.info("[entropybot] structure facts: {} of {} blocks are structure blocks ({} by tag, {} block entity, {} station, {} crafted and not world-gen); world gen: {}",
                yes, out.size(), tagged, be, st, cr, gen == null ? "not loaded yet (crafted blocks answer not loaded)" : gen.size() + " blocks placed (from the jars)");
        return Collections.unmodifiableMap(out);
    }

    /** Singleplayer only, once per world: the integrated server's registries against the jars' set; a difference is said, never used. */
    private static void registryCheck(Set<String> jarSet) {
        try {
            Set<String> reg = registryBlocks();
            if (reg == null) return;                  // a server's client: no registries to check against
            java.util.TreeSet<String> onlyReg = new java.util.TreeSet<>(reg), onlyJar = new java.util.TreeSet<>(jarSet);
            onlyReg.removeAll(jarSet);
            onlyJar.removeAll(reg);
            if (onlyReg.isEmpty() && onlyJar.isEmpty()) LOG.info("[entropybot] structure facts: the server's world-gen registries agree with the jars ({} blocks)", reg.size());
            else LOG.info("[entropybot] structure facts: registries vs jars differ (the jars stand; the registries hold no structure templates): {} only in the registries (first: {}), {} only in the jars (first: {})",
                    onlyReg.size(), onlyReg.isEmpty() ? "-" : onlyReg.first(), onlyJar.size(), onlyJar.isEmpty() ? "-" : onlyJar.first());
        } catch (RuntimeException e) {
            Quiet.warn("structure facts: the registry check failed (the jars' set stands)", e);
        }
    }

    /** The block ids the integrated server's registries place (configured features, the noise settings' surface rules), or null without one. */
    private static Set<String> registryBlocks() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return null;
        var ra = server.registryAccess();
        var ops = RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, ra);
        Set<String> names = new HashSet<>();
        int failed = 0;
        for (ConfiguredFeature<?, ?> f : ra.registryOrThrow(Registries.CONFIGURED_FEATURE)) {
            var j = ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, f).result();
            if (j.isPresent()) WorldGenFiles.collect(j.get(), names); else failed++;
        }
        for (NoiseGeneratorSettings n : ra.registryOrThrow(Registries.NOISE_SETTINGS)) {
            var j = NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, n).result();
            if (j.isPresent()) WorldGenFiles.collect(j.get(), names); else failed++;
        }
        if (failed > 0) LOG.warn("[entropybot] structure facts: registry check: {} world-gen entries could not be encoded", failed);
        return names;
    }
}
