package io.github.mojolowjo.entropybot.guard;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Action;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.BlockFacts;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Fluid;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Snapshot;
import io.github.mojolowjo.entropybot.guard.BreakPolicy.Where;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * {@link BreakPolicy} as the game sees it (mining plan 3.1, 3.4, 5): the rule snapshot (rebuilt when the guard's
 * policy or the connection changes), the active intent, the two {@link BlockFacts} adapters (a {@code Level} on the
 * client thread, Baritone's {@code BlockStateInterface} through {@link StateAt} on the pathfinder thread), the
 * counters and the shadow comparisons ("would differ"). Everything the pathfinder thread reads is a volatile
 * snapshot; nothing here touches {@code Level} off the client thread.
 *
 * <p>Phase 0: shadow only (no behaviour change). Phase 1 ({@link #LIVE}): the TRAVEL column is live through the cost
 * hook and the click veto.
 */
public final class PolicyRuntime {
    private static final Logger LOG = LogUtils.getLogger();
    public static final PolicyRuntime INSTANCE = new PolicyRuntime();

    /** Phase 1 switch: the TRAVEL intent decides breaks (with a cost hook running). Phase 0 keeps it off: shadow only. */
    public static final boolean LIVE = true;

    /**
     * A block state by cell on whatever thread the caller is on ({@code bsi.get0} on the pathfinder thread, which reads
     * AIR for an unloaded chunk: hence {@link #loaded}, {@code bsi.isLoaded}, so an unknown cell fails closed).
     */
    public interface StateAt {
        BlockState at(int x, int y, int z);

        default boolean loaded(int x, int z) { return true; }

        static StateAt of(baritone.utils.BlockStateInterface bsi) {
            return new StateAt() {
                public BlockState at(int x, int y, int z) { return bsi.get0(x, y, z); }
                public boolean loaded(int x, int z) { return bsi.isLoaded(x, z); }
            };
        }
    }

    public final PolicyStats stats = new PolicyStats();

    private volatile Snapshot snapshot;
    private volatile Policy snapshotOf;
    private volatile boolean multiplayer;
    private volatile Intent intent = Intent.TRAVEL;
    private volatile String intentBy = "default";
    /** The cost hook that is running: "fork" (our Baritone's CostAdvisor), "mixin" (the stock-jar fallback) or "none". */
    private volatile String hook = "none";
    /** A thread-confined intent for one context construction (the route map's walking-only context). */
    private final ThreadLocal<Intent> scoped = new ThreadLocal<>();
    // travel breaks on the current walk (the cap), by job id
    private volatile long travelJob = -1;
    private volatile int travelBreaks;
    private volatile String lastTravelBreak;
    private long lastOverrideLogMs;
    private int overrideLogged;

    private PolicyRuntime() {}

    // ---- the snapshot ----

    /** Client tick: the connection kind and the guard's policy into the snapshot when either changed. Never throws. */
    public void tick(Minecraft mc, GuardCore core) {
        try {
            boolean mp = mc != null && !mc.hasSingleplayerServer();      // a LAN host counts as singleplayer, a LAN guest as a server
            var pl = mc == null ? null : mc.player;
            boolean wet = pl != null && pl.isInWater();
            // review: one snapshot, one volatile write (the pathfinder thread never sees a torn mix)
            body = new Where(wet, 0, pl == null ? 300 : pl.getAirSupply(),
                    pl == null || !wet ? 0 : waterAbove(pl.level(), pl.blockPosition()), pl == null ? Integer.MIN_VALUE : pl.blockPosition().getY(), pl == null ? DrownRisk.DEFAULT_TIER : pickTier(pl));
            inWater = wet;
            Policy p = core.policy();
            if (snapshot == null || p != snapshotOf || mp != multiplayer) {
                multiplayer = mp;
                snapshotOf = p;
                snapshot = Snapshot.of(p, mp);
            }
        } catch (Throwable t) {
            stats.error("tick: " + t);
        }
    }

    /** The snapshot the pathfinder thread reads; null before the first tick in a world (every ask then denies). */
    public Snapshot snapshot() { return snapshot; }

    // ---- the intent ----

    public Intent intent() { return intent; }

    /** The intent for a cost calculation on this thread: a scoped one (route map) wins, else the job's. */
    public Intent intentNow() {
        Intent s = scoped.get();
        return s != null ? s : intent;
    }

    public void setIntent(Intent i, String by) {
        if (i == null) i = Intent.TRAVEL;
        if (intent != i) LOG.debug("[entropybot] rules: intent {} -> {} ({})", intent, i, by);
        intent = i;
        intentBy = by;
    }

    /** Runs {@code make} with the intent {@code i} on this thread only (a {@code CalculationContext} built for walking only). */
    public <T> T withIntent(Intent i, Supplier<T> make) {
        Intent was = scoped.get();
        scoped.set(i);
        try {
            return make.get();
        } finally {
            if (was == null) scoped.remove(); else scoped.set(was);
        }
    }

    // ---- the hook ----

    public String hook() { return hook; }

    public void setHook(String kind) { hook = kind == null ? "none" : kind; }

    /** Phase 1 is live: a cost hook runs and the switch is on. Without a hook the old rules hold (fail safe). */
    public boolean travelLive() { return LIVE && !"none".equals(hook); }

    /**
     * A walk may turn Baritone's breaking on: the policy is live, has a snapshot, and the outside rule set lets travel
     * break at all (both connection presets do; the cost hook does the limiting, the click veto the floor).
     */
    public boolean travelMayBreak() {
        Snapshot s = snapshot;
        return travelLive() && s != null && s.outside().breakNatural() != BreakPolicy.Natural.NEVER;
    }

    /**
     * Baritone's allowBreak is on only because of the travel policy (no build, dig or mine job owns it): for the route
     * map (review R5) that is still a walking-only world when the fork hook runs, because its contexts are built under
     * the NONE intent ({@link #withIntent}); the mixin fallback reads the live intent and cannot promise that.
     */
    public boolean travelOnlyBreaking() {
        return travelMayBreak() && hook.startsWith("fork") && intent == Intent.TRAVEL;
    }

    /** The same for placing: allowPlace is on only because of the travel policy (no plan-go or build owns it). */
    public boolean travelOnlyPlacing() {
        return travelMayPlace() && hook.startsWith("fork") && intent == Intent.TRAVEL;
    }

    /**
     * Owner's place rule (aa372): a walk may turn Baritone's placing on: the policy is live with a cost hook and has a
     * snapshot. The cost hook prices every placement (x3 for travel, about nothing into a cell the bot dug itself, COST_INF
     * where the rule set places nothing) and the click veto asks the same policy, so A* and the click agree.
     */
    public boolean travelMayPlace() {
        return travelLive() && snapshot != null;
    }

    // ---- the cells the bot dug itself (the refill rule) ----

    /** Keys "dim x y z" of cells the bot broke itself (the ledger at load, every job break since). Read on the pathfinder thread. */
    private final java.util.Set<String> dug = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** More than this and the set starts over from the ledger (a bound, not a policy; the ledger itself is capped). */
    static final int DUG_CAP = 8192;

    /** A new world's ledger: the dug set starts over with its entries' keys (null: nothing). */
    public void resetDug(java.util.Collection<String> keys) {
        dug.clear();
        if (keys != null) for (String k : keys) if (dug.size() < DUG_CAP) dug.add(k);
    }

    /** The bot broke this cell (any job's break, recorded by the ledger or not): placing there is a refill. */
    public void markDug(String key) {
        if (dug.size() >= DUG_CAP) dug.clear();
        dug.add(key);
    }

    public boolean dug(String dim, int x, int y, int z) {
        return dim != null && dug.contains(dim + " " + x + " " + y + " " + z);
    }

    public int dugCount() { return dug.size(); }

    /** The facts for a placement at the cell: {@link #facts} plus whether the bot dug the cell itself. */
    public BlockFacts placeFacts(StateAt at, String dim, int x, int y, int z) {
        return facts(at, x, y, z, false).withDug(dug(dim, x, y, z));
    }

    // ---- the facts ----

    /** Client thread: what the guard knows about the block at pos, from the level. Unknown when not loaded. */
    public BlockFacts facts(Level level, BlockPos pos, boolean torch) {
        if (level == null || !level.isLoaded(pos)) return BlockFacts.UNKNOWN;
        return facts((x, y, z) -> {
            BlockPos p = new BlockPos(x, y, z);
            return level.isLoaded(p) ? level.getBlockState(p) : null;
        }, pos.getX(), pos.getY(), pos.getZ(), torch);
    }

    /**
     * Any thread: the facts from a state lookup ({@code bsi.get0} on the pathfinder thread). A null state or an unloaded
     * column = unknown. The block id is looked up only for a block entity (the chopped-log exception) - this runs for
     * every break candidate of an A* search, so it stays a handful of array reads.
     */
    public BlockFacts facts(StateAt at, int x, int y, int z, boolean torch) {
        if (!at.loaded(x, z)) return BlockFacts.UNKNOWN;
        BlockState st = at.at(x, y, z);
        if (st == null) return BlockFacts.UNKNOWN;
        Guard g = Guard.INSTANCE;
        if (!g.floorReady()) return BlockFacts.UNKNOWN;
        String id = null;
        boolean be = st.hasBlockEntity();
        if (be) {
            id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
            be = !io.github.mojolowjo.entropybot.clear.ClearRules.choppedLog(id);
        }
        boolean built = g.isProtectedBlock(st.getBlock());
        boolean ore = st.is(Tags.Blocks.ORES);
        boolean unbreakable = st.getBlock().defaultDestroyTime() < 0;
        // in the cell: a pure liquid block only (kelp, seagrass and waterlogged stairs are broken like any block)
        Fluid in = st.getBlock() instanceof LiquidBlock ? fluidOf(st) : Fluid.NONE;
        BlockState up = at.at(x, y + 1, z);
        Fluid above = up == null ? Fluid.NONE : fluidOf(up);
        boolean falling = up != null && up.getBlock() instanceof FallingBlock;
        BlockState dn = at.at(x, y - 1, z);
        Fluid below = dn == null ? Fluid.NONE : fluidOf(dn);
        Fluid sides = Fluid.NONE;
        for (int[] s : SIDES) {
            BlockState n = at.at(x + s[0], y, z + s[1]);
            if (n == null) continue;
            Fluid f = fluidOf(n);
            if (f == Fluid.LAVA) { sides = Fluid.LAVA; break; }
            if (f == Fluid.WATER) sides = Fluid.WATER;
        }
        return new BlockFacts(true, id, be, built, ore, unbreakable, in, above, sides, below, falling, torch);
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** The fluid of a state (a waterlogged block counts as water here: Baritone's adjacency rule does the same). */
    public static Fluid fluidOf(BlockState st) {
        var f = st.getFluidState();
        if (f.isEmpty()) return Fluid.NONE;
        return f.is(FluidTags.LAVA) ? Fluid.LAVA : Fluid.WATER;
    }

    /** Where the bot is, as of the last client tick (the pathfinder thread never touches the player). */
    public Where where() {
        return body.withBreaks(travelBreaks(currentJob));
    }

    private volatile boolean inWater;
    private volatile Where body = new Where(false, 0, 300, 0, Integer.MIN_VALUE);

    /** aa-costs (director): the best pickaxe tier in the bag (DrownRisk's dig time), 0 none. */
    public static int pickTier(net.minecraft.world.entity.player.Player pl) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (var st : pl.getInventory().items)
            if (!st.isEmpty()) ids.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
        return DrownRisk.pickTier(ids);
    }

    /** aa-costs (DrownRisk): water cells from the feet up (at most 32), the swim back to air. */
    public static int waterAbove(net.minecraft.world.level.Level lv, net.minecraft.core.BlockPos feet) {
        int n = 0;
        var m = feet.mutable();
        while (n < 32 && fluidOf(lv.getBlockState(m)) == Fluid.WATER) {
            n++;
            m.move(0, 1, 0);
        }
        // aa-water (live water1, drowned): a flooded cave's water ends at a stone ceiling, not at air: no straight
        // swim up to breathe there, so it counts as the deepest
        return n > 0 && lv.getBlockState(m).isCollisionShapeFullBlock(lv, m) ? DrownRisk.SURFACE_SCAN : n;      // a lily pad or thin cap is not a ceiling
    }

    /** aa-water (pathfinder thread safe): water cells from (x, y, z) up through the snapshot (at most DrownRisk.SURFACE_SCAN). */
    public static int waterColumn(StateAt at, int x, int y, int z) {
        int n = 0;
        while (n < DrownRisk.SURFACE_SCAN) {
            BlockState st = at.at(x, y + n, z);
            if (st == null || fluidOf(st) != Fluid.WATER) {
                if (n > 0 && (st == null || st.isSolid())) return DrownRisk.SURFACE_SCAN;     // a ceiling (or unknown: fail closed), not air
                break;
            }
            n++;
        }
        return n;
    }

    // ---- asks ----

    public BreakPolicy.Verdict judge(String site, Action action, Intent in, String dim, int x, int y, int z, BlockFacts f, Where me) {
        return judge(site, action, in, dim, x, y, z, f, me, snapshot);
    }

    /** With an explicit snapshot (the one a calculation captured at its start). */
    public BreakPolicy.Verdict judge(String site, Action action, Intent in, String dim, int x, int y, int z, BlockFacts f, Where me, Snapshot s) {
        stats.ask(site);
        try {
            BreakPolicy.Verdict v = BreakPolicy.judge(s, new BreakPolicy.Ask(action, in, dim, x, y, z, f, me));
            if (!v.allowed()) stats.deny(site, v.reason());
            return v;
        } catch (Throwable t) {
            stats.error(site + ": " + t);
            if (stats.errors() <= 5) LOG.warn("[entropybot] rules: policy error at {}: {}", site, t.toString());
            return new BreakPolicy.Verdict(false, Double.POSITIVE_INFINITY, "policy error: " + t.getClass().getSimpleName(), false, "error");
        }
    }

    /**
     * Shadow mode (phase 0): today's decision and the policy's answer for the same cell. Counts and logs (rate-limited)
     * when they differ. {@code todayAllowed}: what the existing rule decided; its reason names the category (a lease or
     * box rule vs a fact).
     */
    public void shadow(String site, boolean todayAllowed, String todayReason, BreakPolicy.Verdict policy, Intent in, int x, int y, int z) {
        if (policy == null || policy.allowed() == todayAllowed) return;
        String why = todayAllowed ? "policy denies: " + policy.reason() : "policy allows (" + policy.rule() + "), today: " + todayReason;
        if (stats.differ(site, why, System.currentTimeMillis())) {
            LOG.info("[entropybot] rules would differ at {} ({}, {} {} {}): {}", site, in.name().toLowerCase(), x, y, z, why);
        }
    }

    // ---- travel breaks (phase 1) ----

    /** The running job (set each client tick, -1 when none): the cap and the ledger marks belong to it. */
    private volatile long currentJob = -1;
    private volatile String lastMarkedKey;

    /** Client tick: the running job's id, or -1. A new job starts with an empty cap and no stale ledger mark. */
    public void setCurrentJob(long id) {
        if (id == currentJob) return;
        currentJob = id;
        travelJob = id;
        travelBreaks = 0;
        lastTravelBreak = null;
        lastMarkedKey = null;
    }

    /**
     * A travel break was let through at this key ("dim x y z"): counted once toward the cap (the click veto fires every
     * tick of a multi-tick break; a repeat of the same cell is not a new break) and handed to the ledger.
     */
    public void markTravelBreak(String key) {
        if (key.equals(lastMarkedKey)) return;
        lastMarkedKey = key;
        travelBreaks++;
        lastTravelBreak = key;
    }

    /** The restore hook asks: was this break a travel break (then it is a path break for the ledger)? One answer per mark. */
    public boolean takeTravelBreak(String key) {
        String k = lastTravelBreak;
        if (k == null || !k.equals(key)) return false;
        lastTravelBreak = null;
        return true;
    }

    public int travelBreaks(long jobId) { return jobId == travelJob ? travelBreaks : 0; }

    /** Phase 1: a TRAVEL click or plan the box rules would have refused went through; one log line per 10 s after the first 5. */
    public void logTravelOverride(String site, int x, int y, int z, BreakPolicy.Verdict v, String todayReason) {
        logOverride(site, "travel may break", x, y, z, v, todayReason);
    }

    /** The same for a placement the policy let through ("may place": a refill or a lenient rule set). */
    public void logOverride(String site, String what, int x, int y, int z, BreakPolicy.Verdict v, String todayReason) {
        stats.travelOverride(site);
        long now = System.currentTimeMillis();
        if (overrideLogged < 5 || now - lastOverrideLogMs >= PolicyStats.LOG_EVERY_MS) {
            overrideLogged++;
            lastOverrideLogMs = now;
            LOG.info("[entropybot] rules: {} at {} {} {} ({}; the fence alone said: {})", what, x, y, z, v.text(), todayReason);
        }
    }

    // ---- status ----

    /** "fence rules": the snapshot, the intent, the hook, the counters. */
    public String status() {
        Snapshot s = snapshot;
        StringBuilder sb = new StringBuilder("rules: ");
        sb.append(LIVE ? "phase 1 (travel live)" : "phase 0 (shadow, no behaviour change)");
        sb.append(" | cost hook: ").append(hookText());
        sb.append(" | intent ").append(intent.name().toLowerCase()).append(" (").append(intentBy).append(")");
        if (s == null) sb.append(" | no snapshot yet");
        else sb.append(" | outside: ").append(s.outside().describe()).append(" | areas ").append(s.policy().areas.size()).append(", protect ").append(s.policy().protect.size())
                .append(" | travel cap ").append(s.travelCap()).append(" (this walk: ").append(travelBreaks).append(")")
                .append(" | dug cells ").append(dug.size()).append(" (refill x").append(BreakPolicy.fmt(BreakPolicy.Costs.REFILL)).append(")");
        sb.append(" | ").append(stats.summary());
        return sb.toString();
    }

    public String hookText() {
        return switch (hook) {
            case "fork" -> "fork hook (keep-out pathed around, danger x" + fmtMult(io.github.mojolowjo.entropybot.baritone.CostAdvisorHook.dangerMultiplier()) + ")";
            case "fork-nomove" -> "fork hook (NO move hook: this Baritone is older than 0.39 - keep-out is command-level only, danger costs nothing)";
            case "mixin" -> "mixin fallback (no move hook: keep-out is command-level only, danger costs nothing)";
            default -> "NOT running - Baritone's own rules only";
        };
    }

    private static String fmtMult(double m) { return m == Math.rint(m) ? String.valueOf((long) m) : String.valueOf(m); }

    /** "fence why x y z [break|place] [intent]": the verdict for one cell with the rule that decided (client thread). */
    public String why(Level level, String dim, int x, int y, int z, Action action, Intent in) {
        Snapshot s = snapshot;
        if (s == null) return "rules: no snapshot yet";
        BlockPos pos = new BlockPos(x, y, z);
        BlockFacts f = facts(level, pos, false).withDug(dug(dim, x, y, z));
        BreakPolicy.Verdict v = BreakPolicy.judge(s, new BreakPolicy.Ask(action, in, dim, x, y, z, f, where()));
        BreakPolicy.RuleSet r = s.ruleSetAt(dim, x, y, z);
        String id = f.loaded() && level != null ? BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString() : null;
        return "rules " + action.name().toLowerCase() + " " + in.name().toLowerCase() + " at " + x + " " + y + " " + z + ": " + v.text()
                + " | rule set " + r.describe()
                + " | block " + (f.loaded() ? id + (f.ore() ? " (ore)" : "") + (f.builtBlock() ? " (built)" : "") + (f.blockEntity() ? " (block entity)" : "")
                + ", in cell " + f.inCell().name().toLowerCase() + ", above " + f.above().name().toLowerCase() + (f.fallingAbove() ? " (falling)" : "")
                + ", sides " + f.sides().name().toLowerCase() + (f.dugByMe() ? ", dug by me" : "") : "not loaded");
    }
}
