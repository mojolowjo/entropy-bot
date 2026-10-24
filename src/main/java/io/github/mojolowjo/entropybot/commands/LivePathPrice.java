package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPathCost;
import baritone.api.pathing.goals.GoalGetToBlock;
import io.github.mojolowjo.entropybot.aa.PathPrice;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * path-cost: the chain's walk price over the fork's {@code IBaritone.getPathCost()} (Baritone's own A* from the feet to
 * stand by the block, with its settings and the mod's CostAdvisor policy, a node/time budget and a cost ceiling; it never
 * moves or touches a process). Rule 2: Baritone prices the walk, nothing here re-computes a path. A complete path is its
 * ticks / 20; a partial one within budget is used and marked "about"; no path, another thread or an older Baritone jar:
 * null, and the chain takes the line (said in its line; each reason logged once). Loader notes: Minecraft#isSameThread
 * (the client thread); the fork's IPathCost (baritone-unoptimized-neoforge-1.11.3-entropy, 2026-10-22).
 */
public final class LivePathPrice implements PathPrice {
    private static final Logger LOG = LoggerFactory.getLogger("entropybot");
    public static final LivePathPrice INSTANCE = new LivePathPrice();
    private final Set<String> said = ConcurrentHashMap.newKeySet();

    private LivePathPrice() {}

    @Override
    public Walk toBlock(int x, int y, int z) { return price(x, y, z).walk(); }

    /** 0.39.z28: the answer with the no-path reason (the chain's trace prints it; aa debug). */
    @Override
    public Answer price(int x, int y, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !mc.isSameThread()) return none("the chain was priced off the game thread: Baritone's path cost runs on it only");
        IBaritone b = Jobs.baritone();
        if (b == null) return none("Baritone is not loaded");
        IPathCost.Answer a;
        try {
            a = b.getPathCost().estimate(new GoalGetToBlock(new BlockPos(x, y, z)), (int) Math.ceil(PathPrice.budgetS() * io.github.mojolowjo.entropybot.aa.brain.GameTimes.TPS), PathPrice.budgetMs());
        } catch (LinkageError e) {
            return none("this Baritone has no IPathCost (the stock jar? install the entropy fork): " + e);
        }
        if (a.noPath) {
            LOG.debug("[entropybot] path cost to {} {} {}: no path ({})", x, y, z, a.reason);
            return new Answer(null, "no path: " + a.reason);
        }
        double tps = io.github.mojolowjo.entropybot.aa.brain.GameTimes.TPS;
        return new Answer(new Walk(a.ticks / tps, !a.complete, lavaTicks(mc, a) / tps), null);
    }

    /**
     * Lava distance (0.39.z40): the lava price inside the walk (the hook's LavaNear rule over the path's cells, at the Tuning
     * values; rough, rule 14: the level now, not the calculation's snapshot). 0: none, or no cells in the answer.
     */
    private static double lavaTicks(Minecraft mc, IPathCost.Answer a) {
        if (a.path == null || a.path.isEmpty() || mc.level == null) return 0;
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
        if (said.add(why)) LOG.warn("[entropybot] path cost: {}; walks are priced by the line x 1.3", why);
        return new Answer(null, why);
    }
}
