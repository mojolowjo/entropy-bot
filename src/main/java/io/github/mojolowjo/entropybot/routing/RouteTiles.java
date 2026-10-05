package io.github.mojolowjo.entropybot.routing;

import io.github.mojolowjo.entropybot.route.RouteCounters;
import io.github.mojolowjo.entropybot.route.RouteFileHeader;
import io.github.mojolowjo.entropybot.route.RouteFiles;
import io.github.mojolowjo.entropybot.route.RouteLog;
import io.github.mojolowjo.entropybot.route.RouteStore;
import io.github.mojolowjo.entropybot.route.SectionKey;
import io.github.mojolowjo.entropybot.route.SectionRecord;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Storing and loading the map (plan section 2): one gzip tile per 128x128 blocks under
 * {@code minecraft\entropybot\routes\<dim folder>\}, through R1's {@link RouteFiles}. Saving runs on the route io thread
 * (never the game thread); a tile that fails to save is kept and tried again next time, and the failure is counted and
 * logged. Loading happens once at world join, before the builder starts.
 */
public final class RouteTiles {
    /** The file side, R1's {@link RouteFiles} by default (a fake in JUnit). */
    public interface TileIo {
        void save(Path file, RouteFileHeader header, Collection<SectionRecord> records, Set<SectionKey> stale) throws IOException;

        RouteFiles.LoadResult loadInto(RouteStore store, Path file, int dim, RouteFileHeader expected,
                                       RouteCounters counters, RouteLog log);

        static TileIo files() {
            return new TileIo() {
                @Override
                public void save(Path file, RouteFileHeader header, Collection<SectionRecord> records, Set<SectionKey> stale) throws IOException {
                    RouteFiles.save(file, header, records, stale);
                }

                @Override
                public RouteFiles.LoadResult loadInto(RouteStore store, Path file, int dim, RouteFileHeader expected,
                                                      RouteCounters counters, RouteLog log) {
                    return RouteFiles.loadInto(store, file, dim, expected, counters, log);
                }
            };
        }
    }

    /** What a load found, for the log line. */
    public record LoadSummary(int files, int boxes, int ignored, int settingsChanged, int areasChanged) {
        public String line() {
            return files + " route tiles, " + boxes + " boxes" + (ignored > 0 ? ", " + ignored + " ignored" : "")
                    + (settingsChanged > 0 ? ", " + settingsChanged + " with other Baritone settings (all stale)" : "")
                    + (areasChanged > 0 ? ", " + areasChanged + " made for other areas" : "");
        }
    }

    private final Path routesDir;
    private final IntFunction<String> dimFolder;
    private final TileIo io;
    private final RouteCounters counters;
    private final RouteLog log;
    /** Tiles that failed to save, tried again with the next save. Only the io thread touches it. */
    private final Set<List<Integer>> retry = new LinkedHashSet<>();
    private int saved, failed;

    public RouteTiles(Path routesDir, IntFunction<String> dimFolder, TileIo io, RouteCounters counters, RouteLog log) {
        this.routesDir = routesDir;
        this.dimFolder = dimFolder;
        this.io = io;
        this.counters = counters;
        this.log = log;
    }

    public Path routesDir() {
        return routesDir;
    }

    /**
     * Writes every tile changed since the last save (io thread). Returns the number of tiles written. Never throws.
     */
    public synchronized int saveDirty(RouteStore store, RouteFileHeader header) {
        Set<List<Integer>> tiles = new LinkedHashSet<>(retry);
        retry.clear();
        try {
            for (int[] t : store.takeDirtyTiles()) tiles.add(List.of(t[0], t[1], t[2]));
        } catch (Throwable t) {
            counters.workerException("route save (dirty tiles)", t, log);
            return 0;
        }
        if (tiles.isEmpty()) return 0;
        Map<List<Integer>, List<SectionRecord>> recs = new HashMap<>();
        for (List<Integer> t : tiles) recs.put(t, new ArrayList<>());
        store.forEach(r -> {
            List<SectionRecord> l = recs.get(List.of(r.key().dim(), r.key().tileX(), r.key().tileZ()));
            if (l != null) l.add(r);
        });
        Map<List<Integer>, Set<SectionKey>> stale = new HashMap<>();
        for (SectionKey k : store.staleKeys()) {
            List<Integer> t = List.of(k.dim(), k.tileX(), k.tileZ());
            if (recs.containsKey(t)) stale.computeIfAbsent(t, x -> new HashSet<>()).add(k);
        }
        int n = 0;
        for (List<Integer> t : tiles) {
            String folder = dimFolder.apply(t.get(0));
            if (folder == null) continue;
            Path file = RouteFiles.tilePath(routesDir, folder, t.get(1), t.get(2));
            try {
                Files.createDirectories(file.getParent());
                io.save(file, header, recs.get(t), stale.getOrDefault(t, Set.of()));
                n++;
                saved++;
            } catch (Throwable e) {
                failed++;
                retry.add(t);
                counters.workerException("route save " + file.getFileName(), e, log);
            }
        }
        return n;
    }

    /** Loads every tile of {@code dim} into the store (io thread, before building starts). Never throws. */
    public LoadSummary loadAll(RouteStore store, int dim, RouteFileHeader expected) {
        String folder = dimFolder.apply(dim);
        int files = 0, boxes = 0, ignored = 0, settings = 0, areas = 0;
        if (folder == null) return new LoadSummary(0, 0, 0, 0, 0);
        Path dir = routesDir.resolve(folder);
        if (!Files.isDirectory(dir)) return new LoadSummary(0, 0, 0, 0, 0);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "t.*.bin")) {
            for (Path p : ds) {
                files++;
                try {
                    RouteFiles.LoadResult r = io.loadInto(store, p, dim, expected, counters, log);
                    if (r == null || r.ignored()) {
                        ignored++;
                        continue;
                    }
                    boxes += r.records() == null ? 0 : r.records().size();
                    if (r.settingsChanged()) settings++;
                    if (r.areasChanged()) areas++;
                } catch (Throwable t) {
                    ignored++;
                    counters.workerException("route load " + p.getFileName(), t, log);
                }
            }
        } catch (Throwable t) {
            counters.workerException("route load " + dir, t, log);
        }
        return new LoadSummary(files, boxes, ignored, settings, areas);
    }

    public synchronized String line() {
        return "tiles saved " + saved + (failed > 0 ? ", save failures " + failed + " (retried: " + retry.size() + " waiting)" : "");
    }
}
