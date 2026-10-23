package io.github.mojolowjo.entropybot;

import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.flag.FeatureFlags;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The client's own jars as data packs (loot-book-bg 0.39.z13, shared since 0.39.z26 (2) with the world-gen read): the game
 * jar's vanilla pack plus every mod's data pack ({@code ServerPacksSource.createVanillaTrustedRepository()}, NeoForge adds the
 * mods' packs to it), the packs a new world would turn on (shouldAddAutomatically, default features). Later packs win, as on
 * a server. {@link #idVersions} is the per-mod-list cache key's input. {@link #POOL} is the one background thread both reads
 * use (after the join, never on the game thread). Loader notes: vanilla API only; Fabric API adds mod packs to the same
 * repository (ModResourcePackCreator), so this is the same code there.
 */
public final class JarPacks {
    /** The one low-priority daemon thread for the jar reads (loot book, world-gen blocks). */
    private static final ThreadPoolExecutor EXEC = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "entropybot-jars");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    public static final ExecutorService POOL = EXEC;

    /** The jars thread runs or holds a job now (a new job waits behind it). */
    public static boolean busy() { return EXEC.getActiveCount() > 0 || !EXEC.getQueue().isEmpty(); }

    private final PackRepository repo;
    /** Every selected pack and its children as "id@version" (the cache key's input). */
    public final Set<String> idVersions;

    private JarPacks(PackRepository repo, Set<String> idVersions) {
        this.repo = repo;
        this.idVersions = idVersions;
    }

    /** Builds and selects the repository (background thread: it scans the mod jars). */
    public static JarPacks select() {
        var repo = ServerPacksSource.createVanillaTrustedRepository();
        repo.reload();
        List<String> sel = new ArrayList<>();
        for (var pk : repo.getAvailablePacks())
            if (pk.getPackSource().shouldAddAutomatically() && pk.getRequestedFeatures().isSubsetOf(FeatureFlags.DEFAULT_FLAGS)) sel.add(pk.getId());
        repo.setSelected(sel);
        Set<String> idv = new HashSet<>();
        for (var pk : repo.getSelectedPacks())
            pk.streamSelfAndChildren().forEach(x -> idv.add(x.getId() + "@" + x.location().knownPackInfo().map(KnownPack::version).orElse("?")));
        return new JarPacks(repo, Set.copyOf(idv));
    }

    /** A resource manager over the selected packs' data; the caller closes it. */
    public MultiPackResourceManager open() {
        return new MultiPackResourceManager(PackType.SERVER_DATA, repo.openAllSelected());
    }
}
