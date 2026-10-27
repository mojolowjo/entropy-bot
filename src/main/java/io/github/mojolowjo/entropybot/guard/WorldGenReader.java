package io.github.mojolowjo.entropybot.guard;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.JarPacks;
import io.github.mojolowjo.entropybot.aa.brain.LootFiles;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 0.39.z26 (2): the adapter half of the world-gen block read. On a server's client the CONFIGURED_FEATURE and NOISE_SETTINGS
 * registries are not there, so the set comes from the client's own jars on singleplayer and on a server alike (one path):
 * the loot book's jar packs (JarPacks), read on the shared background thread after the join, cached per mod list in
 * {@code entropybot/worldgen-<hash>.json} (0.39.z77 natural 2: no override file, a name list). Rule 15: {@link #blocks} is null
 * until the read is done (not loaded), then a set (empty = the jars gave no world-gen data, said). A failed read is its
 * own state ({@link #failed}, the reason kept): the set stays null, so crafted blocks answer not loaded with that reason,
 * never the empty set; the next join reads again. The writes that start a read share the lock with {@code gen}. Loader notes: vanilla data-pack API
 * (MultiPackResourceManager.listResources); Fabric: the same calls.
 */
public final class WorldGenReader {
    private static final Logger LOG = LogUtils.getLogger();

    private WorldGenReader() {}

    private static int gen;                       // guarded by WorldGenReader.class: the read now running
    /** The world-gen block ids; null = not loaded yet. */
    static volatile Set<String> blocks;
    /** 0.39.z77 natural 2: the block tags the jars' carvers name (ids without "#"); null = not loaded yet. Set before {@link #blocks}. */
    static volatile Set<String> carverTags;
    /** 0.39.z45: the ore placements of the same read (rule 19: the jars on singleplayer and a server alike); null = not loaded yet. */
    static volatile WorldGenFiles.Ores ores;
    /** 0.39.z45: the world-gen files the last finished read took (from the jars or its cache); -1 while not done. */
    static volatile int filesRead = -1;

    /** The ore placements; null while the read runs or after it failed (not loaded, rule 15). */
    public static WorldGenFiles.Ores ores() { return ores; }

    /** The world-gen files the finished read took; -1: not loaded. */
    public static int filesRead() { return filesRead; }

    /** The reason the last read failed; null: it did not. */
    public static String failed() { return failed; }
    /** The jar packs the running or last read used; -1 while they are counted. */
    static volatile int jars = -1;
    /** The jar read gave no world-gen file at all. */
    static volatile boolean noData;
    /** FAILED: the read's reason (null: not failed); {@link #blocks} stays null (not loaded) and the next join reads again. */
    static volatile String failed;
    private static volatile boolean saidWaiting;

    /** Game thread, once per world: drops the old set and starts the read. dir: the entropybot folder (null: no cache). */
    static void start(Path dir, String game) {
        int my;
        synchronized (WorldGenReader.class) {
            my = ++gen;
            blocks = null;
            carverTags = null;
            ores = null;
            filesRead = -1;
            jars = -1;
            noData = false;
            failed = null;
            saidWaiting = false;                  // said once per world
        }
        if (JarPacks.busy() && !saidWaiting) {
            saidWaiting = true;
            LOG.info("[entropybot] structure facts: the world-gen read waits behind the loot book's read on the shared jars thread - crafted blocks answer not loaded until both are done");
        }
        JarPacks.POOL.execute(() -> {
            Set<String> s, ct;
            boolean none;
            WorldGenFiles.Ores oreRead;
            int readFiles;
            try {
                long t0 = System.nanoTime();
                var packs = JarPacks.select();
                synchronized (WorldGenReader.class) {
                    if (my != gen) return;            // a newer world started its own read
                    jars = packs.idVersions.size();
                }
                String hash = LootFiles.hash(packs.idVersions, game);
                Path cache = dir == null ? null : dir.resolve(WorldGenFiles.cacheName(hash));
                WorldGenFiles.Read r = null;
                if (cache != null && Files.isRegularFile(cache)) {
                    try {
                        r = WorldGenFiles.fromCache(Files.readString(cache), hash);
                    } catch (IOException | RuntimeException e) {
                        LOG.warn("[entropybot] structure facts: world-gen cache {} ignored ({}) - reading the jars again", cache.getFileName(), e.getMessage());
                    }
                }
                String from = "cache";
                if (r == null) {
                    from = "jars";
                    List<WorldGenFiles.Entry> json = new ArrayList<>();
                    try (var rm = packs.open()) {
                        for (String d : dirs()) add(rm.listResources(d, rl -> rl.getPath().endsWith(".json")), json);
                        r = WorldGenFiles.read(json, WorldGenReader::isBlock);
                    }
                    if (cache != null) {
                        try {
                            Files.createDirectories(dir);
                            Files.writeString(cache, WorldGenFiles.toCache(hash, r));
                        } catch (IOException e) {
                            LOG.warn("[entropybot] structure facts: world-gen cache {} not written ({}) - the next join reads the jars again", cache.getFileName(), e.toString());
                        }
                    }
                }
                none = r.blocks().isEmpty();
                oreRead = r.ores();
                readFiles = r.files();
                LOG.info("[entropybot] aa: ore placements from the {} (rule 19, singleplayer and server alike): {} ore placed features, {} biomes, {} biome modifiers, {} placed features that say where (heightmap = surface, height_range = a band; 0.39.z68){}",
                        from, oreRead.placed().size(), oreRead.biomes().size(), oreRead.modifiers().size(), oreRead.placers().size(),
                        oreRead.unread() == 0 ? "" : "; " + oreRead.unread() + " entries not read (first: " + oreRead.firstUnread() + ")");
                s = r.blocks();
                ct = r.carverTags();
                LOG.info("[entropybot] structure facts: world gen places {} blocks (from the {}: {} packs, {} files, {} skipped{}; configured features (block states and plain block ids) and noise settings only; structure templates are not read: villages and fossils are built things, their blocks stay structure blocks); the carvers name {} block tags {}; {} ms",
                        s.size(), from, jars, r.files(), r.bad(), r.firstBad() == null ? "" : ", first: " + r.firstBad(), ct.size(), ct, (System.nanoTime() - t0) / 1_000_000);  // structural: ns to ms
            } catch (RuntimeException | Error e) {
                // its own state FAILED: crafted blocks answer not loaded with this reason (never the empty set); read again on the next join
                LOG.warn("[entropybot] structure facts: the world-gen read failed ({}) - crafted blocks answer not loaded and are refused; it is read again on the next join", e.toString());
                synchronized (WorldGenReader.class) {
                    if (my == gen) failed = e.toString();
                }
                return;
            }
            oldOverride(dir);
            synchronized (WorldGenReader.class) {
                if (my != gen) return;                // a newer world started its own read
                noData = none;
                ores = oreRead;
                filesRead = readFiles;
                carverTags = ct;                      // before blocks: a reader that sees the set sees the tags
                blocks = s;
            }
        });
    }

    /**
     * 0.39.z77 natural: a block id of the game's block registry (WorldGenFiles.blockIds). The built-in registry, the same on
     * singleplayer and a server (rule 19: not the integrated server's world-gen registries), frozen at startup, so a
     * read from the jars thread is safe. Loader notes: vanilla BuiltInRegistries / ResourceLocation.tryParse.
     */
    static boolean isBlock(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl != null && net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(rl);
    }

    /** The folders of the one read: the block-state names and the ore placements. */
    private static List<String> dirs() {
        List<String> l = new ArrayList<>(WorldGenFiles.JSON_DIRS);
        l.addAll(WorldGenFiles.PLACEMENT_DIRS);
        l.addAll(WorldGenFiles.CARVER_DIRS);
        return l;
    }

    private static void add(Map<ResourceLocation, Resource> found, List<WorldGenFiles.Entry> out) {
        for (var e : found.entrySet()) {
            String id = e.getKey().toString();
            Resource res = e.getValue();
            out.add(new WorldGenFiles.Entry() {
                @Override public String id() { return id; }
                @Override public InputStream open() throws IOException { return res.open(); }
            });
        }
    }

    /** 0.39.z77 natural 2: the deleted override file's name, only to say that one left in the folder is not read. */
    private static final String OLD_OVERRIDE = "worldgen-override.json";

    /** An old {@code worldgen-override.json} left in the folder is said, never read (a name list, rule 4). */
    private static void oldOverride(Path dir) {
        if (dir != null && Files.isRegularFile(dir.resolve(OLD_OVERRIDE)))
            LOG.warn("[entropybot] structure facts: {} is no longer read (0.39.z77: a name list, rule 4) - the jars' world-gen and carver tags decide", dir.resolve(OLD_OVERRIDE));
    }
}
