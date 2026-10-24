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
 * {@code entropybot/worldgen-<hash>.json}, then the owner's {@code worldgen-override.json}. Rule 15: {@link #blocks} is null
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
    /** The jar read gave no world-gen file at all (before the override). */
    static volatile boolean noData;
    /** FAILED: the read's reason (null: not failed); {@link #blocks} stays null (not loaded) and the next join reads again. */
    static volatile String failed;
    private static volatile boolean saidWaiting;

    /** Game thread, once per world: drops the old set and starts the read. dir: the entropybot folder (null: no cache, no override). */
    static void start(Path dir, String game) {
        int my;
        synchronized (WorldGenReader.class) {
            my = ++gen;
            blocks = null;
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
            Set<String> s;
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
                        r = WorldGenFiles.read(json);
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
                LOG.info("[entropybot] aa: ore placements from the {} (rule 19, singleplayer and server alike): {} ore placed features, {} biomes, {} biome modifiers{}",
                        from, oreRead.placed().size(), oreRead.biomes().size(), oreRead.modifiers().size(),
                        oreRead.unread() == 0 ? "" : "; " + oreRead.unread() + " entries not read (first: " + oreRead.firstUnread() + ")");
                s = r.blocks();
                LOG.info("[entropybot] structure facts: world gen places {} blocks (from the {}: {} packs, {} files, {} skipped{}; configured features and noise settings only; structure templates are not read: villages and fossils are built things, their blocks stay structure blocks); {} ms",
                        s.size(), from, jars, r.files(), r.bad(), r.firstBad() == null ? "" : ", first: " + r.firstBad(), (System.nanoTime() - t0) / 1_000_000);  // structural: ns to ms
            } catch (RuntimeException | Error e) {
                // its own state FAILED: crafted blocks answer not loaded with this reason (never the empty set); read again on the next join
                LOG.warn("[entropybot] structure facts: the world-gen read failed ({}) - crafted blocks answer not loaded and are refused; it is read again on the next join", e.toString());
                synchronized (WorldGenReader.class) {
                    if (my == gen) failed = e.toString();
                }
                return;
            }
            s = override(s, dir);
            synchronized (WorldGenReader.class) {
                if (my != gen) return;                // a newer world started its own read
                noData = none;
                ores = oreRead;
                filesRead = readFiles;
                blocks = s;
            }
        });
    }

    /** The folders of the one read: the block-state names and the ore placements. */
    private static List<String> dirs() {
        List<String> l = new ArrayList<>(WorldGenFiles.JSON_DIRS);
        l.addAll(WorldGenFiles.PLACEMENT_DIRS);
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

    /** The owner's {@code worldgen-override.json}: said when used, when missing (optional) and when broken (not used). */
    private static Set<String> override(Set<String> s, Path dir) {
        if (dir == null) {
            LOG.info("[entropybot] structure facts: no entropybot folder - {} not read", WorldGenFiles.OVERRIDE_FILE);
            return s;
        }
        Path f = dir.resolve(WorldGenFiles.OVERRIDE_FILE);
        if (!Files.isRegularFile(f)) {
            LOG.info("[entropybot] structure facts: no {} (optional: {\"<block id>\": true|false} adds or removes a world-gen block)", f);
            return s;
        }
        try {
            var o = WorldGenFiles.override(Files.readString(f));
            LOG.info("[entropybot] structure facts: {} sets {} blocks", f.getFileName(), o.size());
            return WorldGenFiles.apply(s, o);
        } catch (IOException | RuntimeException e) {
            LOG.warn("[entropybot] structure facts: {} not used ({}) - fix it; the jars' set stands", f.getFileName(), e.getMessage());
            return s;
        }
    }
}
