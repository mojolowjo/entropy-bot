package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.aa.brain.LootBook;
import io.github.mojolowjo.entropybot.aa.brain.LootFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * loot-book-bg (0.39.z13, owner 2026-10-22: "On a server the loot book must work too. Not on connect: after the connection,
 * in the background, because a modpack has thousands of tables. Manual correction later, no learning by observation.").
 *
 * <p>The adapter half of the loot book read. Started on the game thread at the first AA tick in a world (after the join,
 * never at connect): it reads the registries there (which table is which block's and mob's, the passive mobs: a few ms),
 * hands back a not-ready placeholder book, and runs the read on its own daemon thread. The game thread takes the finished
 * book with {@link #poll} and publishes it to the blackboard in one step.
 * <ul>
 * <li>0.39.z45 (rule 19): the integrated server's loot path is deleted; singleplayer, a LAN host and a server's client all
 * read the jars below, so they behave the same (a world's own data pack is not read: said in the report, not hidden).</li>
 * <li>Every client: the loot-table JSON in the client's own jars, through the game's data-pack API:
 * {@code ServerPacksSource.createVanillaTrustedRepository()} (the game jar's vanilla pack plus every mod's data pack: NeoForge
 * adds them to that repository), the packs a new world would turn on (shouldAddAutomatically, default features), read with
 * a {@code MultiPackResourceManager}: {@code listResources("loot_table")} and {@code "loot_tables"}. Later packs win, as on
 * a server. Cached in {@code entropybot/lootbook-<hash>.json} ({@link LootFiles#hash}: the packs' ids and versions and the
 * game version). A server's own data packs and server-only mods are not on the client: their drops stay unknown.</li>
 * </ul>
 * Trades: the game's VillagerTrades lists on both (the client has no server trade lists). Then the owner's override file.
 * Every failure is logged (a skipped table counted, a broken cache or override file named); a failed read gives a ready,
 * empty book whose origin says "read failed", and {@code aa want} starts a new read.
 *
 * <p>Loader notes: vanilla API only (ServerPacksSource, PackRepository, MultiPackResourceManager, ReloadableServerRegistries,
 * the LootTable codec, VillagerTrades). Fabric: the same vanilla calls; Fabric API adds mod data packs to the same
 * repository (its ModResourcePackCreator), so the read is the same code there.
 */
final class LootBookReader {
    private static final Logger LOG = LogUtils.getLogger();
    private static final ExecutorService POOL = io.github.mojolowjo.entropybot.JarPacks.POOL;   // shared with the world-gen read

    private int gen;               // the read now running (a newer world drops an older read's result)
    private LootBook done;         // guarded by this
    private int doneGen;

    /** The world's passive mobs (friendly and not fighting back), read with the registries at {@link #start}. */
    volatile Set<String> passive = Set.of();

    /** Game thread: starts the read for this world in the background; the placeholder book to publish now. dir: the entropybot folder (null: no cache, no override). */
    LootBook start(Minecraft mc, Path dir) {
        int my;
        synchronized (this) {
            my = ++gen;
            done = null;
        }
        Map<String, List<String>> blocks = new HashMap<>(), mobs = new HashMap<>();
        Set<String> pas = new HashSet<>();
        for (var e : BuiltInRegistries.ENTITY_TYPE.entrySet()) {
            var type = e.getValue();
            if (type.getCategory() == net.minecraft.world.entity.MobCategory.MISC) continue;
            String id = e.getKey().location().toString();
            if (type.getCategory().isFriendly() && !AaRuntime.fightsBack(id)) pas.add(id);     // review: neutral ones fight back
            mobs.computeIfAbsent(type.getDefaultLootTable().location().toString(), k -> new ArrayList<>()).add(id);
        }
        for (var e : BuiltInRegistries.BLOCK.entrySet()) {
            var key = e.getValue().getLootTable();
            if (key == null || key == net.minecraft.world.level.storage.loot.BuiltInLootTables.EMPTY) continue;
            blocks.computeIfAbsent(key.location().toString(), k -> new ArrayList<>()).add(e.getKey().location().toString());
        }
        passive = Set.copyOf(pas);
        // 0.39.z45 (rule 19): the jars on singleplayer and a server alike; the integrated server's loot path is deleted
        LootBook.Progress p = new LootBook.Progress("from the jars");
        String game = net.minecraft.SharedConstants.getCurrentVersion().getName();
        LOG.info("[entropybot] aa: loot book: reading in the background ({})", p.from);
        synchronized (this) { tables = -1; fish = null; }
        POOL.execute(() -> {
            LootBook b;
            Set<String> f = null;
            int[] n = {-1};
            try {
                var packs = io.github.mojolowjo.entropybot.JarPacks.select();
                b = jars(packs, blocks, mobs, p, dir, game, n);
                f = fishing(packs);
            } catch (RuntimeException | Error e) {
                LOG.warn("[entropybot] aa: loot book read failed ({}) - aa want uses recipes, ores and gather sources only; aa want reads again", e.toString());
                b = LootBook.empty("read failed: " + e.getClass().getSimpleName());
            }
            b = override(b, dir);
            synchronized (this) {
                if (my == gen) {
                    done = b;
                    doneGen = my;
                    tables = n[0];
                    fish = f;
                } else LOG.info("[entropybot] aa: loot book of an earlier world dropped (a newer read runs)");
            }
        });
        return LootBook.reading(p);
    }

    private int tables = -1;           // guarded by this
    private Set<String> fish;          // guarded by this

    /** 0.39.z45: the loot tables the finished read took (from the jars or its cache); -1: not loaded. */
    synchronized int tablesRead() { return tables; }

    /** 0.39.z45: the fishing table's fish (the game's fishing loot from the jars); null: not loaded or not read (said). */
    synchronized Set<String> fishPool() { return fish; }

    /**
     * 0.39.z45: the items of the game's fishing fish table (BuiltInLootTables.FISHING_FISH) from the jars, nested tables
     * through the same packs; null when it is not in them or does not read (said, never an empty pool).
     */
    private static Set<String> fishing(io.github.mojolowjo.entropybot.JarPacks packs) {
        String table = net.minecraft.world.level.storage.loot.BuiltInLootTables.FISHING_FISH.location().toString();
        try (var rm = packs.open()) {
            Map<String, JsonElement> seen = new HashMap<>();
            java.util.function.Function<String, JsonElement> lookup = k -> {
                if (seen.containsKey(k)) return seen.get(k);
                JsonElement j = null;
                var rl = net.minecraft.resources.ResourceLocation.tryParse(k);
                if (rl != null) for (String d : LootFiles.DIRS) {
                    var res = rm.getResource(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(rl.getNamespace(), d + "/" + rl.getPath() + ".json"));
                    if (res.isEmpty()) continue;
                    try (Reader rd = res.get().openAsReader()) {
                        j = JsonParser.parseReader(rd);
                    } catch (IOException e) {
                        throw new IllegalStateException(k + ": " + e, e);
                    }
                    break;
                }
                seen.put(k, j);
                return j;
            };
            JsonElement t = lookup.apply(table);
            if (t == null) {
                LOG.warn("[entropybot] aa: the fishing table {} is not in the jars - no item counts as a fish", table);
                return null;
            }
            return Set.copyOf(LootBook.parse(t, lookup).keySet());
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] aa: the fishing table {} did not read ({}) - no item counts as a fish", table, e.toString());
            return null;
        }
    }

    /** Game thread: the finished book of the current read, once; null while it runs (or after it was taken). */
    synchronized LootBook poll() {
        if (done == null || doneGen != gen) return null;
        LootBook b = done;
        done = null;
        return b;
    }

    // ---- the jars on the client's disk (singleplayer and a server alike, rule 19) ----

    private static LootBook jars(io.github.mojolowjo.entropybot.JarPacks packs, Map<String, List<String>> blocks, Map<String, List<String>> mobs, LootBook.Progress p, Path dir, String game, int[] tablesOut) {
        long t0 = System.nanoTime();
        Set<String> idv = packs.idVersions;
        String hash = LootFiles.hash(idv, game);
        Path cacheFile = dir == null ? null : dir.resolve(LootFiles.cacheName(hash));
        if (cacheFile != null && Files.isRegularFile(cacheFile)) {
            p.from = "from the cache";
            try {
                LootFiles.Read r = LootFiles.fromCache(Files.readString(cacheFile), hash);
                tablesOut[0] = r.tables();
                List<LootBook.Source> all = new ArrayList<>(r.sources());
                int tradesBad = trades(all);
                var book = new LootBook(all, "cache");
                LOG.info("[entropybot] aa: loot book: {}; {} trades skipped; {} ms ({})", book.counts(), tradesBad, (System.nanoTime() - t0) / 1_000_000, cacheFile.getFileName());  // structural: nanoseconds to milliseconds
                return book;
            } catch (IOException | RuntimeException e) {
                LOG.warn("[entropybot] aa: loot book cache {} ignored ({}) - reading the jars again", cacheFile.getFileName(), e.getMessage());
                p.from = "from the jars";
            }
        }
        Map<String, JsonElement> tables = new HashMap<>();
        int bad = 0;
        String firstBad = null;
        try (var rm = packs.open()) {
            Map<String, net.minecraft.server.packs.resources.Resource> byId = new LinkedHashMap<>();
            for (int i = LootFiles.DIRS.size() - 1; i >= 0; i--)          // the older plural first: the 1.21 folder wins a clash
                for (var e : rm.listResources(LootFiles.DIRS.get(i), rl -> rl.getPath().endsWith(".json")).entrySet()) {
                    String id = LootFiles.tableId(e.getKey().getNamespace(), e.getKey().getPath());
                    if (id != null) byId.put(id, e.getValue());
                }
            p.total.set(byId.size());
            for (var e : byId.entrySet()) {
                try (Reader rd = e.getValue().openAsReader()) {
                    tables.put(e.getKey(), JsonParser.parseReader(rd));
                } catch (IOException | RuntimeException x) {
                    bad++;
                    if (firstBad == null) firstBad = e.getKey() + " in " + e.getValue().sourcePackId() + ": " + x;
                }
                p.done.incrementAndGet();
            }
        }
        LootFiles.Read r = LootFiles.build(tables.keySet(), tables::get, blocks, mobs, null);
        tablesOut[0] = tables.size();
        if (firstBad == null) firstBad = r.firstBad();
        double s = (System.nanoTime() - t0) / 1e9;  // structural: nanoseconds to seconds
        if (cacheFile != null) {
            try {
                Files.createDirectories(dir);
                Files.writeString(cacheFile, LootFiles.toCache(hash, new LootFiles.Read(r.sources(), r.tables(), bad + r.bad(), null)));
            } catch (IOException e) {
                LOG.warn("[entropybot] aa: loot book cache {} not written ({}) - the next join reads the jars again", cacheFile.getFileName(), e.toString());
            }
        }
        List<LootBook.Source> all = new ArrayList<>(r.sources());
        int tradesBad = trades(all);
        var book = new LootBook(all, String.format(Locale.ROOT, "jars: %,d tables, %.1f s", tables.size(), s));
        LOG.info("[entropybot] aa: loot book: {}; {} packs, {} tables and {} trades skipped{}; {} ms", book.counts(), idv.size(), bad + r.bad(), tradesBad,
                firstBad == null ? "" : " (first: " + firstBad + ")", (System.nanoTime() - t0) / 1_000_000);  // structural: nanoseconds to milliseconds
        return book;
    }

    // ---- both: trades, the override ----

    /** The game's villager and wandering trader trade lists (after NeoForge's trade events); returns the offers skipped. */
    private static int trades(List<LootBook.Source> all) {
        int[] bad = {0};
        for (var pe : net.minecraft.world.entity.npc.VillagerTrades.TRADES.entrySet()) {
            String prof = String.valueOf(BuiltInRegistries.VILLAGER_PROFESSION.getKey(pe.getKey()));
            for (var lv : pe.getValue().values()) trades(all, prof, lv, bad);
        }
        for (var lv : net.minecraft.world.entity.npc.VillagerTrades.WANDERING_TRADER_TRADES.values()) trades(all, "minecraft:wandering_trader", lv, bad);
        return bad[0];
    }

    /** One level's trade listings: each offer once (null trader: the ones that need a villager or a map throw and are skipped, counted). */
    private static void trades(List<LootBook.Source> all, String prof, net.minecraft.world.entity.npc.VillagerTrades.ItemListing[] lv, int[] bad) {
        if (lv == null) return;
        for (var l : lv) {
            if (l == null || l instanceof net.minecraft.world.entity.npc.VillagerTrades.TreasureMapForEmeralds) continue;   // would search for a structure
            try {
                var o = l.getOffer(null, net.minecraft.util.RandomSource.create(7));
                if (o == null || o.getResult().isEmpty()) continue;
                var a = o.getCostA();
                var b = o.getCostB();
                String cost = a.getCount() + " " + BuiltInRegistries.ITEM.getKey(a.getItem()).getPath()
                        + (b.isEmpty() ? "" : " + " + b.getCount() + " " + BuiltInRegistries.ITEM.getKey(b.getItem()).getPath());
                all.add(new LootBook.Source(LootBook.Kind.TRADE, prof, BuiltInRegistries.ITEM.getKey(o.getResult().getItem()).toString(), o.getResult().getCount(), 1, false, cost));
            } catch (RuntimeException e) {
                bad[0]++;
            }
        }
    }

    /** The owner's {@code entropybot/lootbook-override.json}, when there: its items' sources replace the book's (said with the count); a broken file is not used (said). */
    private static LootBook override(LootBook b, Path dir) {
        if (dir == null) return b;
        Path f = dir.resolve(LootFiles.OVERRIDE_FILE);
        if (!Files.isRegularFile(f)) return b;
        try {
            var o = LootFiles.override(Files.readString(f));
            LOG.info("[entropybot] aa: loot book: {} replaces the sources of {} items", f.getFileName(), o.size());
            return b.withOverride(o, LootFiles.OVERRIDE_FILE);
        } catch (IOException | RuntimeException e) {
            LOG.warn("[entropybot] aa: loot book: {} not used ({}) - fix it; the book read from the game stands", f.getFileName(), e.getMessage());
            return b;
        }
    }
}
