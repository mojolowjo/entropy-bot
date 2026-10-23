package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.BaritoneAPI;
import baritone.api.utils.BlockOptionalMetaLookup;
import io.github.mojolowjo.entropybot.aa.Nearby;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.StationBlocks;
import io.github.mojolowjo.entropybot.aa.StationFact;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * simple-tools (0.39.w1): the live side of {@link Nearby}: Baritone's cached world scan (the one its mine process uses) for the
 * nearest tree log and the nearest block of any set (live-facts), the level for the leaves and the block id, the policy for what I may touch.
 * The fallbacks (AA's sightings, the ore index) are passed in by the caller. Results are reused for
 * {@code nearby.cacheMs} while the bot stays within {@code nearby.cacheMove} blocks. Client thread only.
 */
final class NearbyLive {
    private static final Logger LOG = LoggerFactory.getLogger("entropybot");

    private final Commands c;
    private List<Block> logBlocks;
    private long logAt = -1;
    private int[] logMe;
    private Spot logSpot;
    private String lastFrom = "none";

    NearbyLive(Commands c) { this.c = c; }

    /** Where the last answer came from: "baritone", "sightings" or "none" (for aa status / aa why). */
    String lastFrom() { return lastFrom; }

    /** The nearest tree log, Baritone first, else {@code fallback}. */
    Spot log(Supplier<Spot> fallback) {
        long now = System.currentTimeMillis();
        int[] me = here();
        if (me == null) return fallback == null ? null : fallback.get();
        if (logAt >= 0 && now - logAt < Tuning.l("nearby.cacheMs") && close(me, logMe)) return logSpot;
        logAt = now;
        logMe = me;
        boolean[] fell = {false};
        logSpot = Nearby.log(scan(), me, () -> { fell[0] = true; return fallback == null ? null : fallback.get(); });
        lastFrom = logSpot == null ? "none" : fell[0] ? "sightings" : "baritone";
        return logSpot;
    }

    private record Cached(long at, int[] me, Spot spot) {}
    private final java.util.Map<java.util.Set<String>, Cached> sets = new java.util.HashMap<>();

    /** live-facts: the nearest block of the set (Nearby.nearest), Baritone first, else {@code fallback}; cached per set as the log answer is. */
    Spot nearest(java.util.Set<String> blocks, Supplier<Spot> fallback) {
        long now = System.currentTimeMillis();
        int[] me = here();
        if (me == null) return fallback == null ? null : fallback.get();
        Cached k = sets.get(blocks);
        if (k != null && now - k.at() < Tuning.l("nearby.cacheMs") && close(me, k.me())) return k.spot();
        sets.values().removeIf(e -> now - e.at() >= Tuning.l("nearby.cacheMs"));
        Spot s = Nearby.nearest(scan(), me, blocks, fallback);
        sets.put(java.util.Set.copyOf(blocks), new Cached(now, me, s));
        return s;
    }

    private record Known(long at, int[] me, long data, StationFact.Known fact) {}
    private final java.util.Map<String, Known> stations = new java.util.HashMap<>();

    /**
     * station-memory (0.39.z14), station-race (0.39.z17, rule 15): the station fact of a recipe kind (ItemWorth.CRAFT /
     * SMELT): the known ones nearest first ({@link Nearby#stations}: my placements at any distance, then Baritone's scan),
     * none, or NOT LOADED (the item data is not in yet). Only a known answer is cached (as the other answers are, and
     * dropped at once by {@link #forget} or a new item data); not loaded and none are asked again every time.
     */
    StationFact stations(String kind) {
        long now = System.currentTimeMillis();
        StationBlocks.Answer blocks = stationBlocks(kind);
        if (blocks instanceof StationBlocks.Answer.NotLoaded n) {
            notLoadedNote(kind, n.why());
            return new StationFact.NotLoaded(n.why());
        }
        int[] me = here();
        if (me == null) return new StationFact.NotLoaded("not in a world");
        long data = io.github.mojolowjo.entropybot.aa.brain.ItemWorth.version();
        Known k = stations.get(kind);
        if (k != null && k.data() == data && now - k.at() < Tuning.l("nearby.cacheMs") && close(me, k.me())) return k.fact();
        StationFact f = Nearby.stationFact(scan(), me, blocks);
        if (f instanceof StationFact.Known kn) stations.put(kind, new Known(now, me, data, kn));
        else stations.remove(kind);
        return f;
    }

    /**
     * The deposit leg (owner 2026-10-22): the known containers as a station fact (the same three states and the same one
     * lookup, {@link Nearby#stationFact}: my own placements at any distance, then Baritone's scan), nearest first. The
     * blocks: the game's chest and barrel tags ({@code c:chests}, {@code c:barrels}) that the item data calls a container.
     */
    StationFact containers() {
        if (!io.github.mojolowjo.entropybot.aa.brain.ItemWorth.hasData()) return new StationFact.NotLoaded("item data not loaded yet");
        int[] me = here();
        if (me == null) return new StationFact.NotLoaded("not in a world");
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (var tag : List.of(net.neoforged.neoforge.common.Tags.Blocks.CHESTS, net.neoforged.neoforge.common.Tags.Blocks.BARRELS))
            BuiltInRegistries.BLOCK.getTagOrEmpty(tag).forEach(h -> {
                String id = BuiltInRegistries.BLOCK.getKey(h.value()).toString();
                if (io.github.mojolowjo.entropybot.aa.brain.ItemWorth.container(id)) ids.add(id);
            });
        StationBlocks.Answer blocks = ids.isEmpty() ? new StationBlocks.Answer.None("the game tags no chest or barrel the item data calls a container")
                : new StationBlocks.Answer.Of(ids);
        return Nearby.stationFact(scan(), me, blocks);
    }

    /** station-note (0.39.z15): the lookup's log line ({@link Nearby#stationsLine}) from the same station fact, in its three states. */
    String stationsLine(String kind) {
        StationBlocks.Answer blocks = stationBlocks(kind);
        java.util.Set<String> ids = blocks instanceof StationBlocks.Answer.Of o ? o.ids() : java.util.Set.of();
        Nearby.Scan sc = scan();
        int unv = Nearby.unverified(sc, ids);                   // station-stale: records in unloaded chunks are kept, said here
        return Nearby.stationsLine(stations(kind), sc.own(), ids, Nearby.scanR()) + (unv > 0 ? " (" + unv + " unverified: chunk not loaded)" : "");
    }

    /** station-stale (0.39.z18), the owner's reset: every station record of mine in this world goes; the line with the count. */
    String forgetAllStations() {
        String r = RestoreLive.INSTANCE.forgetAllStations();
        forget();
        return "aa forget stations: " + r;
    }

    /**
     * The nearest known station of the kind that {@code skip} does not refuse, as a fact: NOT LOADED passes through, a
     * known list with every station refused is none.
     */
    StationFact station(String kind, java.util.function.Predicate<int[]> skip) {
        return switch (stations(kind)) {
            case StationFact.NotLoaded n -> n;
            case StationFact.None n -> n;
            case StationFact.Known k -> {
                List<Spot> ok = new ArrayList<>();
                for (Spot s : k.spots()) if (skip == null || !skip.test(new int[]{s.x(), s.y(), s.z()})) ok.add(s);
                yield ok.isEmpty() ? new StationFact.None("every known one is refused (untrusted, or it never opened)") : new StationFact.Known(ok);
            }
        };
    }

    /** The station at the cell is gone: the cached answers go (the ledger record is dropped by the caller). */
    void forget() { stations.clear(); }

    private long notLoadedSaidAt = -1;

    /** rule 15: a not-loaded lookup is logged, at most once a second (the waiting step asks every tick). */
    private void notLoadedNote(String kind, String why) {
        long now = System.currentTimeMillis();
        if (notLoadedSaidAt >= 0 && now - notLoadedSaidAt < 1000) return;   // structural: once per second at most (the owner's log)
        notLoadedSaidAt = now;
        LOG.info("[entropybot] {} ({} for {})", StationFact.NOT_LOADED_LINE, why, kind);
    }

    /**
     * The blocks that are a station of the kind: the station item's own block (the recipe data names the item) and every
     * member of the game's workstation tags ({@code c:player_workstations/...}) that block is in (a modded table counts).
     * Three states ({@link StationBlocks}): not loaded is never cached.
     */
    private final StationBlocks stationBlocks = new StationBlocks(NearbyLive::blocksOfStationItem);

    StationBlocks.Answer stationBlocks(String kind) {
        return stationBlocks.of(kind);
    }

    private static java.util.Set<String> blocksOfStationItem(String item) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        var own = BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.ResourceLocation.tryParse(item));
        if (own.isEmpty()) {
            LOG.warn("[entropybot] stations: no block for the station item {}", item);
            return out;
        }
        out.add(BuiltInRegistries.BLOCK.getKey(own.get()).toString());
        own.get().builtInRegistryHolder().tags()
                .filter(t -> t.location().getNamespace().equals("c") && t.location().getPath().startsWith("player_workstations/"))
                .forEach(t -> BuiltInRegistries.BLOCK.getTagOrEmpty(t).forEach(h -> out.add(BuiltInRegistries.BLOCK.getKey(h.value()).toString())));
        return out;
    }

    private static boolean close(int[] a, int[] b) {
        if (b == null) return false;
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz <= Tuning.get("nearby.cacheMove") * Tuning.get("nearby.cacheMove");
    }

    private static int[] here() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null || Minecraft.getInstance().level == null ? null : Jobs.here(p);
    }

    private List<Block> logBlocks() {
        if (logBlocks == null) {
            List<Block> l = new ArrayList<>();
            for (Block b : BuiltInRegistries.BLOCK) if (b.defaultBlockState().is(BlockTags.LOGS)) l.add(b);
            logBlocks = l;
        }
        return logBlocks;
    }

    private static List<Block> blocksOf(java.util.Set<String> ids) {
        List<Block> l = new ArrayList<>();
        for (String id : ids) BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.ResourceLocation.tryParse(id)).ifPresent(l::add);
        return l;
    }

    private List<int[]> scanFor(List<Block> blocks, int radius) {
        List<int[]> out = new ArrayList<>();
        try {
            IBaritone b = Jobs.baritone();
            if (b == null || blocks.isEmpty()) return out;
            var found = BaritoneAPI.getProvider().getWorldScanner().scanChunkRadius(b.getPlayerContext(), new BlockOptionalMetaLookup(blocks),
                    Tuning.i("nearby.scanMax"), Tuning.i("nearby.scanY"), radius);
            for (BlockPos q : found) out.add(new int[]{q.getX(), q.getY(), q.getZ()});
        } catch (RuntimeException | LinkageError e) {
            io.github.mojolowjo.entropybot.Quiet.warn("nearby: Baritone's world scan failed, the sightings answer instead", e);
        }
        return out;
    }

    private Nearby.Scan scan() {
        Minecraft mc = Minecraft.getInstance();
        String adim = Storage.dim();
        return new Nearby.Scan() {
            @Override public List<int[]> logs(int radius) { return scanFor(logBlocks(), radius); }
            @Override public List<int[]> find(java.util.Set<String> blocks, int radius) { return scanFor(blocksOf(blocks), radius); }
            @Override public boolean isLog(String block) {
                return BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.ResourceLocation.tryParse(block)).map(b -> b.defaultBlockState().is(BlockTags.LOGS)).orElse(false);
            }
            @Override public boolean fairOk(int x, int y, int z) {
                BlockPos q = new BlockPos(x, y, z);
                if (!mc.level.isLoaded(q)) return false;
                var st = mc.level.getBlockState(q);
                boolean ore = io.github.mojolowjo.entropybot.cave.OreSpec.oreId(BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString())
                        || st.is(net.neoforged.neoforge.common.Tags.Blocks.ORES);      // the ore pass's own test (McChunkReader)
                return !ore || Mining.fairOk(adim, x, y, z);
            }
            @Override public boolean leavesNear(int x, int y, int z, int reach) {
                BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
                for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++) for (int dy = 0; dy <= reach; dy++) {
                    q.set(x + dx, y + dy, z + dz);
                    if (mc.level.isLoaded(q) && mc.level.getBlockState(q).is(BlockTags.LEAVES)) return true;
                }
                return false;
            }
            @Override public boolean allowed(int x, int y, int z) { return c.workAllowed(adim, x, y, z); }
            @Override public int treeCap() {
                try {
                    return BaritoneAPI.getSettings().chopComponentMax.value;      // the fork's chop: one tree is at most this many logs
                } catch (LinkageError e) {
                    io.github.mojolowjo.entropybot.Quiet.warn("nearby: this Baritone has no chopComponentMax (not the Entropy fork), trees counted uncapped", e);
                    return Nearby.Scan.super.treeCap();
                }
            }
            @Override public String blockAt(int x, int y, int z) {
                BlockPos q = new BlockPos(x, y, z);
                return mc.level.isLoaded(q) ? BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(q).getBlock()).toString() : null;
            }
        };
    }
}
