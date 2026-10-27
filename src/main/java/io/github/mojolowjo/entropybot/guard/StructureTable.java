package io.github.mojolowjo.entropybot.guard;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.JarPacks;
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
 * and "a crafting recipe makes it and world gen never places it" (the configured features - their block states and, since
 * 0.39.z77, the plain block ids they name, so dripstone_block is terrain - and the noise settings' surface rules; since
 * 0.39.z77 natural 2 also a tag the jars' carvers name (raw_iron_block / raw_copper_block: the ore veins) and a loot table
 * that drops an ore family's item, the loot book from {@link #loot}; no override file), read
 * from the client's jars by WorldGenReader on singleplayer and
 * on a server alike since 0.39.z26 (2); the integrated server's registries are only a check). Ores ({@code c:ores}), logs ({@code #logs}) and planks ({@code #planks}, owner 2026-10-23) are
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

    /** Never structure blocks (owner 2026-10-23): ores, logs and, for now, planks; the game's own tag keys (StructureBlocksTest checks this line). */
    private static final List<TagKey<Block>> NEVER_TAGS = List.of(Tags.Blocks.ORES, BlockTags.LOGS, BlockTags.PLANKS);

    private static volatile Map<Block, Structure> table;
    private static volatile Level builtFor;
    private static final TableRetry<Map<Block, Structure>> retry = new TableRetry<>();
    private static volatile boolean warnedNotLoaded;
    /** The world-gen set the table was built with (null: not loaded yet). */

    private static volatile boolean warnedGen, warnedNoData, checked, warnedRecipes, warnedLoot, warnedTag;
    /** 0.39.z77 natural 2: the loot book the third leg reads (AaRuntime publishes it when it lands; from the jars, one per session's packs). */
    private static volatile io.github.mojolowjo.entropybot.aa.brain.LootBook loot;
    /** 0.39.z77 natural 2: why each structure block is one (the rule line and the dropped list); empty while not built. */
    private static volatile Map<Block, String> whys = Map.of();

    /** 0.39.z77 natural 2: the landed loot book (game thread); the table is built again with it on the next tick. */
    public static void loot(io.github.mojolowjo.entropybot.aa.brain.LootBook book) { loot = book; }

    /** Why the block is a structure block ({@link WorldGenFiles#structureWhy}); null when it is not one or the table is not built. Any thread. */
    public static String why(Block b) { return whys.get(b); }

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
        if (s == Structure.NOT_LOADED && retry.builtGen == null && !warnedGen) {
            warnedGen = true;
            int j = WorldGenReader.jars;
            String failed = WorldGenReader.failed;
            if (failed != null) Quiet.line("structure facts: the world-gen read failed (" + failed + ") - crafted blocks answer not loaded and are refused; it is read again on the next join");
            else Quiet.line("structure facts: world-gen blocks not loaded yet (reading " + (j < 0 ? "the" : String.valueOf(j)) + " jars in the background) - crafted blocks are refused as not loaded yet until the read is done");
        }
        if (s == Structure.NOT_LOADED && retry.builtGen != null && usable(loot) == null && !warnedLoot) {
            warnedLoot = true;
            Quiet.line("structure facts: the loot book is not in yet (read when AA runs) - a crafted block no world-gen feature or carver tag names is refused as not loaded until its ore-loot check can run");
        }
        return s == null ? Structure.NOT_LOADED : s;
    }


    /** Client tick: drop the table without a level; (re)build it for a new level or a changed recipe set. Never throws. */
    public static void tick(Minecraft mc) {
        try {
            Level lv = mc == null ? null : mc.level;
            if (lv == null) {
                if (builtFor != null || table != null) { table = null; whys = Map.of(); builtFor = null; retry.reset(); warnedNotLoaded = false; warnedNoData = false; }
                return;
            }
            int n = lv.getRecipeManager().getRecipes().size();
            Set<String> gen = WorldGenReader.blocks;
            var book = usable(loot);
            if (lv == builtFor && retry.current(n, gen, book)) return;
            if (lv != builtFor) {
                table = null; whys = Map.of(); warnedNotLoaded = false; warnedGen = false; checked = false; retry.reset(); warnedRecipes = false; warnedLoot = false; warnedTag = false; OwnPlaced.newWorld();
                var files = io.github.mojolowjo.entropybot.Core.INSTANCE.files();
                WorldGenReader.start(files == null ? null : files.root(), net.minecraft.SharedConstants.getCurrentVersion().getName());
                gen = null;                           // the new world's read: not loaded until it is done
            }
            builtFor = lv;
            if (n == 0) return;                       // the recipes are not in yet: stays not loaded, asked again next tick
            Set<String> g = gen, ct = gen == null ? null : WorldGenReader.carverTags;   // set before blocks by the reader
            boolean ok = retry.attempt(n, gen, book, () -> build(lv, g, ct, book));
            table = retry.value;
            if (!ok) return;
            if (gen != null && !checked) {            // 0.39.z26 fix (9): off the game tick, on the jars thread (it only logs)
                checked = true;
                var server = mc.getSingleplayerServer();
                Set<String> jarSet = gen;
                if (server != null) JarPacks.POOL.execute(() -> registryCheck(server, jarSet));
            }
        } catch (RuntimeException e) {
            table = null;
            whys = Map.of();
            if (retry.firstWarn()) {
                Quiet.warn("structure facts: the table check failed (" + e + ") - every block answers not loaded; tried again next tick", e);
            }
        }
    }

    /** The loot book the third leg may read: landed, ready and not a failed read; null = not loaded (rule 15). */
    private static io.github.mojolowjo.entropybot.aa.brain.LootBook usable(io.github.mojolowjo.entropybot.aa.brain.LootBook b) {
        return b == null || !b.ready() || b.origin.startsWith("read failed") ? null : b;
    }

    private static Map<Block, Structure> build(Level lv, Set<String> gen, Set<String> carverTagIds, io.github.mojolowjo.entropybot.aa.brain.LootBook book) {
        if (gen != null && WorldGenReader.noData && !warnedNoData) {
            warnedNoData = true;
            Quiet.line("structure facts: no world-gen data in " + WorldGenReader.jars + " jars: crafted blocks no carver tag or ore loot clears are refused outside areas");
        }
        // 0.39.z77 natural 2, leg (2): the carvers' tags, the game's synced block tags
        List<TagKey<Block>> carver = null;
        if (carverTagIds != null) {
            carver = new java.util.ArrayList<>();
            for (String t : carverTagIds) {
                var rl = net.minecraft.resources.ResourceLocation.tryParse(t);
                if (rl != null) carver.add(TagKey.create(Registries.BLOCK, rl));
                else if (!warnedTag) { warnedTag = true; Quiet.line("structure facts: a carver names the tag #" + t + ", not a valid id - left out"); }
            }
        }
        // leg (3): an ore family's items (every drop of a c:ores block, the loot book from the jars); null: not loaded
        Map<String, Set<String>> drops = null;
        Set<String> oreItems = null;
        if (book != null) {
            drops = new java.util.HashMap<>();
            for (var s : book.all())
                if (s.kind() == io.github.mojolowjo.entropybot.aa.brain.LootBook.Kind.BLOCK && s.chance() > 0) drops.computeIfAbsent(s.from(), k -> new HashSet<>()).add(s.item());
            List<String> ores = new java.util.ArrayList<>();
            for (Block b : BuiltInRegistries.BLOCK) if (b.defaultBlockState().is(Tags.Blocks.ORES)) ores.add(BuiltInRegistries.BLOCK.getKey(b).toString());
            Map<String, Set<String>> d = drops;
            oreItems = WorldGenFiles.oreItems(ores, o -> d.getOrDefault(o, Set.of()));
        }
        Set<Item> stations = new HashSet<>(), crafted = new HashSet<>(), unread = new HashSet<>();
        int failed = 0;
        String firstFail = null;
        for (var h : lv.getRecipeManager().getRecipes()) {
            var r = h.value();
            ItemStack out;
            try {
                out = r.getResultItem(lv.registryAccess());
            } catch (RuntimeException e) {
                // the result itself is unknown, so no block can be marked: the whole table stays not loaded (tick says it)
                throw new IllegalStateException("recipe " + h.id() + ": its result could not be read (" + e + ")", e);
            }
            try {
                ItemStack sym = r.getToastSymbol();
                if (sym != null && !sym.isEmpty()) stations.add(sym.getItem());
                if (r.getType() == RecipeType.CRAFTING && out != null && !out.isEmpty()) crafted.add(out.getItem());
            } catch (RuntimeException e) {
                failed++;                             // its result block answers not loaded, never "not crafted"
                if (out != null && !out.isEmpty()) unread.add(out.getItem());
                if (firstFail == null) firstFail = h.id() + ": " + e;
            }
        }
        if (failed > 0 && !warnedRecipes) {
            warnedRecipes = true;
            Quiet.line("structure facts: " + failed + " recipes could not be read (first: " + firstFail + ") - their result blocks answer not loaded, breaks of them are refused");
        }
        Map<Block, Structure> out = new IdentityHashMap<>();
        Map<Block, String> why = new IdentityHashMap<>();
        int yes = 0, tagged = 0, be = 0, st = 0, cr = 0, byGen = 0, byCarver = 0, byLoot = 0, waiting = 0;
        for (Block b : BuiltInRegistries.BLOCK) {
            BlockState s = b.defaultBlockState();
            TagKey<Block> tag = null;
            for (TagKey<Block> k : STRUCTURE_TAGS) if (s.is(k)) { tag = k; break; }
            boolean entity = b instanceof EntityBlock;
            Item it = b.asItem();
            boolean station = it != Items.AIR && stations.contains(it);
            boolean craft = it != Items.AIR && crafted.contains(it);
            String id = BuiltInRegistries.BLOCK.getKey(b).toString();
            Boolean ore = oreItems == null ? null : !Collections.disjoint(drops.getOrDefault(id, Set.of()), oreItems);
            Structure v = WorldGenFiles.classify(STRUCTURE_TAGS, NEVER_TAGS, s::is, carver, entity, station, craft, it != Items.AIR && unread.contains(it), gen, ore, id);
            out.put(b, v);
            if (v == Structure.YES) {
                yes++;
                if (tag != null) tagged++; else if (entity) be++; else if (station) st++; else cr++;
                why.put(b, WorldGenFiles.structureWhy(tag == null ? null : tag.location().toString(), entity, station));
            } else if (v == Structure.NO && craft && tag == null && !entity && !station) {
                if (gen != null && gen.contains(id)) byGen++;
                else if (Boolean.TRUE.equals(ore)) byLoot++;
                else if (carver != null && carver.stream().anyMatch(s::is)) byCarver++;
            } else if (v == Structure.NOT_LOADED) waiting++;
        }
        whys = Collections.unmodifiableMap(why);
        LOG.info("[entropybot] structure facts: {} of {} blocks are structure blocks ({} by tag, {} block entity, {} station, {} crafted and {}; never: c:ores, #logs, #planks); "
                        + "crafted blocks kept as terrain: {} named by the jars' world gen, {} in a carver tag {}, {} dropping an ore family's item; {} not loaded; world gen: {}; loot book: {}",
                yes, out.size(), tagged, be, st, cr, WorldGenFiles.NOT_PLACED, byGen, byCarver, carverTagIds == null ? "(not loaded)" : carverTagIds, byLoot, waiting,
                gen == null ? "not loaded yet (crafted blocks answer not loaded)" : gen.size() + " blocks placed (from the jars)",
                book == null ? "not in yet (crafted blocks no other source clears answer not loaded)" : oreItems.size() + " ore family items");
        return Collections.unmodifiableMap(out);
    }

    /**
     * Singleplayer only, once per world, on {@link JarPacks#POOL} (never the game tick): the integrated server's registries
     * against the jars' set; a difference is said, never used.
     */
    private static void registryCheck(net.minecraft.server.MinecraftServer server, Set<String> jarSet) {
        try {
            Set<String> reg = registryBlocks(server);
            java.util.TreeSet<String> onlyReg = new java.util.TreeSet<>(reg), onlyJar = new java.util.TreeSet<>(jarSet);
            onlyReg.removeAll(jarSet);
            onlyJar.removeAll(reg);
            if (onlyReg.isEmpty() && onlyJar.isEmpty()) LOG.info("[entropybot] structure facts: the server's world-gen registries agree with the jars ({} blocks)", reg.size());
            else LOG.info("[entropybot] structure facts: registries vs jars differ (the jars stand): {} only in the registries (first: {}), {} only in the jars (first: {})",
                    onlyReg.size(), onlyReg.isEmpty() ? "-" : onlyReg.first(), onlyJar.size(), onlyJar.isEmpty() ? "-" : onlyJar.first());
        } catch (RuntimeException e) {
            Quiet.warn("structure facts: the registry check failed (the jars' set stands)", e);
        }
    }

    /** The block ids the integrated server's registries place (configured features, the noise settings' surface rules), or null without one. */
    private static Set<String> registryBlocks(net.minecraft.server.MinecraftServer server) {
        var ra = server.registryAccess();
        var ops = RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, ra);
        Set<String> names = new HashSet<>();
        int failed = 0;
        for (ConfiguredFeature<?, ?> f : ra.registryOrThrow(Registries.CONFIGURED_FEATURE)) {
            var j = ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, f).result();
            if (j.isPresent()) { WorldGenFiles.collect(j.get(), names); WorldGenFiles.blockIds(j.get(), WorldGenReader::isBlock, names, false); } else failed++;
        }
        for (NoiseGeneratorSettings n : ra.registryOrThrow(Registries.NOISE_SETTINGS)) {
            var j = NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, n).result();
            if (j.isPresent()) WorldGenFiles.collect(j.get(), names); else failed++;
        }
        if (failed > 0) LOG.warn("[entropybot] structure facts: registry check: {} world-gen entries could not be encoded", failed);
        return names;
    }
}
