package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPathCost;
import baritone.api.pathing.goals.GoalGetToBlock;
import io.github.mojolowjo.entropybot.aa.PathPrice;
import io.github.mojolowjo.entropybot.aa.TickPrice;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * path-cost: the chain's walk price over the fork's {@code IPathCost}. 0.39.z41 (owner 2026-10-23: "math the actual path
 * it will take ... Use Baritone's numbers"): the FULL A* runs in the background ({@link IPathCost#estimateInBackground}:
 * built on the game thread, searched on Baritone's executor with pathCost.fullMaxMs / pathCost.maxNodes / pathCost.budgetS);
 * its answer is posted back to the game thread ({@link Minecraft#execute}) and cached per target. An ask (game thread):
 * a cached answer -> it (a complete path is the price; noPath is Baritone's reason); the target's own chunk not loaded ->
 * "not loaded" (rule 15, no search); one search in flight for the target, or pathCost.maxPending in flight -> "computing";
 * else the search starts -> "computing". The partial "about" answer is never a price (said "budget"). A cached answer is
 * dropped when the bot stands more than pathCost.reuseBlocks from where it was asked, the bag changed (its contents'
 * hash: Baritone's throwaway blocks and tools change its costs) or the level, or it is older than pathCost.keepS. Rule 2: Baritone
 * prices the walk, nothing here re-computes a path. Loader notes: Minecraft#isSameThread / #execute (the client thread);
 * Level#isLoaded; the fork's IPathCost.estimateInBackground (baritone-unoptimized-neoforge-1.11.3-entropy, z41).
 */
public final class LivePathPrice implements PathPrice {
    private static final Logger LOG = LoggerFactory.getLogger("entropybot");
    public static final LivePathPrice INSTANCE = new LivePathPrice();
    private final Set<String> said = ConcurrentHashMap.newKeySet();

    /** One landed answer and what it was asked from. Game thread only (the maps too). */
    private record Kept(Answer answer, int[] from, int bag, long atMs) {}

    private final Map<String, Kept> kept = new HashMap<>();
    private final Map<String, int[]> inFlight = new HashMap<>();
    private volatile long landed;
    private volatile String lastLanded = "";

    private LivePathPrice() {}

    @Override public boolean pending() { return !inFlight.isEmpty(); }

    @Override public long landed() { return landed; }

    /** The last landed answer's words ("walk to 3 12 -40: 812 ticks, complete"), for the re-price line. */
    public String lastLanded() { return lastLanded; }

    @Override
    public Answer price(int x, int y, int z, double dist) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !mc.isSameThread()) return none("the chain was priced off the game thread: the path search is started on it only");
        if (mc.player == null || mc.level == null) return Answer.none("no player or level");
        IBaritone b = Jobs.baritone();
        if (b == null) return none("Baritone is not loaded");
        BlockPos pos = new BlockPos(x, y, z);
        // 0.39.z46 (S57): with the priced path, "chunks not loaded" is Baritone's own world view's answer (the fork's)
        if (!PathPrice.pricedPathOn() && !mc.level.isLoaded(pos)) return Answer.chunks("not loaded: the target's chunk is not loaded");
        String key = x + "," + y + "," + z;
        int[] me = Jobs.here(mc.player);
        int bag = 31 * bagHash(mc) + System.identityHashCode(mc.level);     // another level (dimension, world): not the same answer
        long now = System.currentTimeMillis();
        Kept k = kept.get(key);
        if (k != null && fresh(k, me, bag, now)) return k.answer();
        if (k != null) kept.remove(key);
        if (inFlight.containsKey(key)) return Answer.computing(null);
        if (inFlight.size() >= PathPrice.maxPending())
            return Answer.computing("queued: pathCost.maxPending " + PathPrice.maxPending() + " searches in flight");
        java.util.concurrent.CompletableFuture<IPathCost.Answer> f;
        try {
            // 0.39.z46: the fork's priced path (Baritone's own search to a full path, kept for the walk: one search);
            // pricedPath.on 0: z41's route (one background A* with our budget, the walk searches fresh)
            f = PathPrice.pricedPathOn()
                    ? b.getPricedPath().price(goal(pos), PathPrice.pricedKeepTicks(), PathPrice.pricedMaxSegments())
                    : b.getPathCost().estimateInBackground(goal(pos),
                    (int) Math.ceil(PathPrice.budgetS() * io.github.mojolowjo.entropybot.aa.brain.GameTimes.TPS), PathPrice.fullMaxMs(), PathPrice.maxNodes());
        } catch (LinkageError e) {
            return none("this Baritone has no IPricedPath / IPathCost.estimateInBackground (the stock jar or an older fork? install the entropy fork z46): " + e);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] path cost to {}: the search could not start", key, e);
            return Answer.none("the search could not start: " + e);
        }
        inFlight.put(key, me);
        f.whenComplete((a, err) -> mc.execute(() -> land(mc, key, me, bag, a, err)));
        return Answer.computing(null);
    }

    /** The goal a walk is priced with, and walked with when the price's path is reused (the fork matches goals by equals). */
    public static GoalGetToBlock goal(BlockPos pos) { return new GoalGetToBlock(pos); }

    /**
     * 0.39.z46: walk to stand by the block at pos: the fork's priced path walks the path its price kept (one search) when
     * the bot still stands at its start; else Baritone searches fresh. pricedPath.on 0: Baritone's CustomGoalProcess as z41.
     * True: the kept path is walked.
     */
    public static boolean walk(IBaritone b, BlockPos pos) {
        if (!PathPrice.pricedPathOn()) {
            b.getCustomGoalProcess().setGoalAndPath(goal(pos));
            return false;
        }
        boolean kept = b.getPricedPath().walk(goal(pos));
        LOG.info("[entropybot] path: walk to {} {} {}: {}", pos.getX(), pos.getY(), pos.getZ(),
                kept ? "the priced path (one search)" : "no kept path from here - Baritone searches fresh");
        return kept;
    }

    /** Game thread: one search's answer is kept and the chain is told (landed++). */
    private void land(Minecraft mc, String key, int[] from, int bag, IPathCost.Answer a, Throwable err) {
        inFlight.remove(key);
        Answer out;
        if (err != null) {
            LOG.warn("[entropybot] path cost to {}: the background search threw", key, err);
            out = Answer.none("the search threw: " + err);
        } else out = answer(mc, a);
        long now = System.currentTimeMillis();
        kept.values().removeIf(old -> now - old.atMs() > PathPrice.keepS() * 1000);   // structural: milliseconds per second
        kept.put(key, new Kept(out, from, bag, now));
        lastLanded = "walk to " + key.replace(',', ' ') + ": " + (out.walk() != null ? Math.round(out.walk().seconds()) + " s, complete" : out.kind() + " (" + out.reason() + ")");
        LOG.debug("[entropybot] path cost: {}", lastLanded);
        landed++;
    }

    /** Baritone's answer as the chain's: complete -> the price; noPath -> its reason, by kind; "about" -> not a price. */
    private static Answer answer(Minecraft mc, IPathCost.Answer a) {
        if (a == null) return Answer.none("the search gave no answer");
        if (!a.noPath && a.complete) return Answer.priced(new Walk(TickPrice.walkS(a.ticks), TickPrice.walkS(lavaTicks(mc, a))));
        if (!a.noPath) return Answer.none("budget: the search ran out before the goal (" + a + ")");
        if (IPathCost.Answer.UNREACHABLE.equals(a.reason)) return Answer.unreachable("no path: the complete search found none");
        if (baritone.api.pathing.calc.IPricedPath.NO_PROGRESS.equals(a.reason))       // 0.39.z46: a segment search went nowhere
            return Answer.unreachable("no path: Baritone's search made no progress (" + a.reason + ")");
        if (IPathCost.Answer.CHUNKS.equals(a.reason)) return Answer.chunks(IPathCost.Answer.CHUNKS);
        return Answer.none(a.reason);
    }

    private static boolean fresh(Kept k, int[] me, int bag, long now) {
        if (k.bag() != bag || now - k.atMs() > PathPrice.keepS() * 1000) return false;  // structural: milliseconds per second
        double dx = me[0] - k.from()[0], dy = me[1] - k.from()[1], dz = me[2] - k.from()[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= PathPrice.reuseBlocks();
    }

    /** The bag's contents as one number (items and counts): a change drops the kept answers. */
    private static int bagHash(Minecraft mc) {
        var inv = mc.player.getInventory();
        int h = 1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var s = inv.getItem(i);
            if (!s.isEmpty()) h = 31 * h + s.getItem().hashCode() * 7 + s.getCount();
        }
        return h;
    }

    /**
     * Lava distance (0.39.z40): the lava price inside the walk (the hook's LavaNear rule over the path's cells, at the Tuning
     * values; rough, rule 14: the level now, when the answer lands). 0: none, or no cells in the answer.
     */
    private static double lavaTicks(Minecraft mc, IPathCost.Answer a) {
        // 0.39.z42 (rule 7): the one null here is the level: the answer lands later than the ask, and the world may have
        // been left meanwhile. The fork's Answer.path is never null (List.of() when it gives no cells: no lava to price).
        if (mc.level == null) return 0;
        if (a.path.isEmpty()) return 0;
        java.util.List<int[]> cells = new java.util.ArrayList<>();
        for (BlockPos p : a.path) cells.add(new int[]{p.getX(), p.getY(), p.getZ()});
        var lv = mc.level;
        io.github.mojolowjo.entropybot.guard.LavaNear.Lava lava = (x, y, z) -> {
            BlockPos q = new BlockPos(x, y, z);
            return lv.isLoaded(q) && lv.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA);
        };
        return io.github.mojolowjo.entropybot.guard.LavaNear.pathTicks(lava, cells, io.github.mojolowjo.entropybot.guard.LavaNear.reach(),
                io.github.mojolowjo.entropybot.guard.LavaNear.penalty());
    }

    private Answer none(String why) {
        if (said.add(why)) LOG.warn("[entropybot] path cost: {}; walks have no price (path not known)", why);
        return Answer.none(why);
    }
}
