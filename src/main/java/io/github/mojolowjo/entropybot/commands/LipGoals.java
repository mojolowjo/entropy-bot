package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.cave.ExploreRules;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * 0.39.z78 run EY (rule 23 (2); 13:30:45: the lip's PRICE asked a stand-by goal on the entry block, "complete" at 2.9 s, while
 * the lip STEP walked the fork's reach goal, which was empty - the planner sent the bot to a lip it could not stand at): the
 * ONE lip test, asked by the lip step (StepHands) and by the cave-way snapshot (AaRuntime.caveWay) alike. The fork's
 * GoalReachBlock on the entry cell: a standable cell (feet and head open, something solid under, no fluid), the entry's
 * centre within {@link ExploreRules#OPENING_REACH} of the eye, a face of it seen (a collider-free ray). Built on the game
 * thread from the loaded world; the entry's cube not loaded = not known (rule 15). The snapshot's answer per opening is
 * kept for a second (structural) so a pick does not rebuild it per ask, and asked for the nearest pathCost.pricedTop caves'
 * openings only (AaRuntime.caveWay; an unloaded cube costs four isLoaded reads, a loaded one the cube's cells and a ray per
 * standable cell). Rule 17 (review): the snapshot reads loaded-but-unseen blocks round an entry from afar - it only ever
 * EXCLUDES an opening (no stand), never makes one a source; the step reads the same blocks at the lip. The hold a failed lip
 * leaves (FactChange.lipFailed) lifts by DISTANCE only (WorldFacts: failures.moved + the margin from where it was learned),
 * as the climb and place facts do; a changed world at the entry is seen again by this test once the bot is back near it.
 * Loader notes: the fork's GoalReachBlock (api); vanilla Level#isLoaded, BlockState#getCollisionShape, Level#getFluidState,
 * Level#clip.
 */
public final class LipGoals {
    private LipGoals() {}

    /** The fork's reach goal on the entry; null while its cube is not loaded (rule 15: not known). */
    public static baritone.api.pathing.goals.GoalReachBlock reachGoal(LocalPlayer p, int[] at) {
        var lv = p.level();
        if (!cubeLoaded(lv, at)) return null;
        return new baritone.api.pathing.goals.GoalReachBlock(new BlockPos(at[0], at[1], at[2]), ExploreRules.OPENING_REACH, 1.62,   // game fact: eye height 1.62
                (x, y, z) -> standable(lv, x, y, z), (x, y, z) -> seesEntry(p, x, y, z, at));
    }

    /** The reach goal's cube (ceil(reach) + 1 each way) round the entry is loaded: its cells can be judged (rule 15). */
    static boolean cubeLoaded(net.minecraft.world.level.Level lv, int[] at) {
        int r = (int) Math.ceil(ExploreRules.OPENING_REACH) + 1;   // the fork's cube (GoalReachBlock)
        return lv.isLoaded(new BlockPos(at[0] - r, at[1], at[2] - r)) && lv.isLoaded(new BlockPos(at[0] + r, at[1], at[2] - r))
                && lv.isLoaded(new BlockPos(at[0] - r, at[1], at[2] + r)) && lv.isLoaded(new BlockPos(at[0] + r, at[1], at[2] + r));
    }

    /** The reach goal as the pure lip rule reads it (null: none yet). */
    public static ExploreRules.LipGoal lipGoalOf(baritone.api.pathing.goals.GoalReachBlock g) {
        if (g == null) return null;
        return new ExploreRules.LipGoal() {
            @Override public boolean isEmpty() { return g.isEmpty(); }
            @Override public boolean isInGoal(int x, int y, int z) { return g.isInGoal(x, y, z); }
        };
    }

    /** The eyes at feet cell x y z see the entry's air (nothing that collides between, a fluid passed). */
    static boolean seesEntry(LocalPlayer p, int x, int y, int z, int[] at) {
        var lv = p.level();
        var from = new net.minecraft.world.phys.Vec3(x + 0.5, y + 1.62, z + 0.5);   // game fact: eye height 1.62
        var to = new net.minecraft.world.phys.Vec3(at[0] + 0.5, at[1] + 0.5, at[2] + 0.5);
        var r = lv.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
        return r.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    /**
     * A cell a player can stand in: its feet and head open (no collision), something solid under it, no fluid - AND a cell
     * Baritone itself stands in (run HB site1, 2026-11-01: a branch goal's only stands were "on pointed_dripstone", a block
     * the fork's MovementHelper.canWalkOn never takes (not a full cube), so Baritone's search failed on its budget with the
     * feet as its best for 30 s; rule 2: the car picks the stand, so a stand it will not take is no stand). Baritone not
     * loaded: the mod's test alone; an older fork without the reader: said once, the mod's test alone.
     * Loader notes: the fork's MovementHelper.canWalkOn / canWalkThrough (the main jar, read-only; Baritone's settings read).
     */
    static boolean standable(net.minecraft.world.level.Level lv, int x, int y, int z) {
        BlockPos f = new BlockPos(x, y, z), h = f.above(), u = f.below();
        if (!lv.isLoaded(f)) return false;
        if (!(lv.getBlockState(f).getCollisionShape(lv, f).isEmpty() && lv.getBlockState(h).getCollisionShape(lv, h).isEmpty()
                && !lv.getBlockState(u).getCollisionShape(lv, u).isEmpty() && lv.getFluidState(f).isEmpty())) return false;
        var b = Jobs.baritone();
        if (b == null) return true;
        try {
            var ctx = b.getPlayerContext();
            return baritone.pathing.movement.MovementHelper.canWalkOn(ctx, u)
                    && baritone.pathing.movement.MovementHelper.canWalkThrough(ctx, new baritone.api.utils.BetterBlockPos(x, y, z))
                    && baritone.pathing.movement.MovementHelper.canWalkThrough(ctx, new baritone.api.utils.BetterBlockPos(x, y + 1, z));
        } catch (RuntimeException | LinkageError e) {
            if (!carStandWarned) {
                carStandWarned = true;
                LOG.warn("[entropybot] lip: Baritone's stand rule is not readable from this fork ({}); the mod's stand test alone from here on", e.toString());
            }
            return true;
        }
    }

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static volatile boolean carStandWarned;

    /** structural: ticks an opening's snapshot answer is kept (a second) */
    private static final long KEEP_TICKS = 20;
    /** opening "x,y,z" -> {the tick it was judged, its Lip} */
    private static final Map<String, Object[]> kept = new HashMap<>();

    /**
     * Run EY: the cave-way snapshot's lip test of one opening, as the lip step reads it ({@link ExploreRules#lip} with no
     * feet): APPROACH = its cube not loaded (not known: the opening stays), NO_CELL = the reach goal is empty (no standable
     * cell within reach and sight: the opening is left out with that reason), WALKING = standable cells exist. Kept a second.
     */
    public static ExploreRules.Lip lipOf(LocalPlayer p, int[] at) {
        String k = at[0] + "," + at[1] + "," + at[2];
        long now = Core.INSTANCE == null ? 0 : Core.INSTANCE.tick();
        Object[] e = kept.get(k);
        if (e != null && now - (long) e[0] < KEEP_TICKS) return (ExploreRules.Lip) e[1];
        ExploreRules.Lip l = ExploreRules.lip(lipGoalOf(reachGoal(p, at)), null);
        kept.put(k, new Object[]{now, l});
        if (kept.size() > 256) kept.clear();     // structural: a few caves' openings; never grows past a run's worth
        return l;
    }
}
