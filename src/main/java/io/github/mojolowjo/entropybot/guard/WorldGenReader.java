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
 * until the read is done (not loaded), then a set (empty = the jars gave no world-gen data, said). A failed read is said
 * and gives the empty set (crafted blocks refused outside areas), never a silent open. Loader notes: vanilla data-pack API
 * (MultiPackResourceManager.listResources); Fabric: the same calls.
 */
final class WorldGenReader {
    private static final Logger LOG = LogUtils.getLogger();

    private WorldGenReader() {}

    private static int gen;                       // guarded by WorldGenReader.class: the read now running
    /** The world-gen block ids; null = not loaded yet. */
    static volatile Set<String> blocks;
    /** The jar packs the running or last read used; -1 while they are counted. */
    static volatile int jars = -1;
    /** The jar read gave no world-gen file at all (before the override). */
    static volatile boolean noData;

    /** Game thread, once per world: drops the old set and starts the read. dir: the entropybot folder (null: no cache, no override). */
    static void start(Path dir, String game) {
        int my;
        synchronized (WorldGenReader.class) { my = ++gen; }
        blocks = null;
        jars = -1;
        noData = false;
        JarPacks.POOL.execute(() -> {
            Set<String> s;
            boolean none = false;
            try {
                long t0 = System.nanoTime();
                var packs = JarPacks.select();
                jars = packs.idVersions.size();
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
                        for (String d : WorldGenFiles.JSON_DIRS) add(rm.listResources(d, rl -> rl.getPath().endsWith(".json")), json);
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
                s = r.blocks();
                LOG.info("[entropybot] structure facts: world gen places {} blocks (from the {}: {} packs, {} files, {} skipped{}; configured features and noise settings only; structure templates are not read: villages and fossils are built things, their blocks stay structure blocks); {} ms",
                        s.size(), from, jars, r.files(), r.bad(), r.firstBad() == null ? "" : ", first: " + r.firstBad(), (System.nanoTime() - t0) / 1_000_000);  // structural: ns to ms
            } catch (RuntimeException | Error e) {
                LOG.warn("[entropybot] structure facts: the world-gen read failed ({}) - crafted blocks are refused outside areas until the override file names them", e.toString());
                s = Set.of();
                none = true;
            }
            s = override(s, dir);
            synchronized (WorldGenReader.class) {
                if (my != gen) return;                // a newer world started its own read
                noData = none;
                blocks = s;
            }
        });
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
