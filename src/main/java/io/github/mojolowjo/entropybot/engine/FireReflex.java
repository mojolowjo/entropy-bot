package io.github.mojolowjo.entropybot.engine;

import baritone.api.pathing.goals.GoalNear;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.events.EventRing;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import io.github.mojolowjo.entropybot.threat.ReachGrid;
import io.github.mojolowjo.entropybot.threat.RunGuard;
import io.github.mojolowjo.entropybot.threat.ThreatRuntime;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * The lava/fire reflex (0.23.6 survival; AA_CLEANUP 4: the milk and the fight potion went with Survival, the fire part
 * stays a true reflex and runs under AA too), the game side of {@link SurvivalRules}: step out of lava or fire, a water
 * bucket placed at the feet and picked up again, fire resistance, walk into water, move away. Runs from
 * {@link Reflexes#tick} before the fight code; while it acts, the reflexes hold the job. Never throws (errors counted
 * and logged, the reflex then stands down).
 *
 * <p>Loader notes: vanilla only: Entity.getRemainingFireTicks/hasEffect, MobEffects holders, DataComponents.POTION_CONTENTS,
 * MultiPlayerGameMode.useItemOn/useItem/handleInventoryMouseClick (SWAP), KeyMapping.setDown; Baritone goals through
 * {@link EngineProcess}; the water placement asks {@link GuardCore#check} first (and the guard mixin vetoes as always).
 */
public final class FireReflex {
    private static final Logger LOG = LogUtils.getLogger();

    private enum Use { NONE, AIM_WATER, DRINK, WATER_PLACED }

    /** Backbone step 4: ticks the bucket waits for LookBehavior to bring the camera down before it is used anyway (logged). */
    static final int AIM_TICKS = 10;

    private final EventRing events;
    private final EngineProcess engine;
    /** Backbone step 4: the reflexes' Moves (reflex priority): the escape GoalRunAway, the water GoalBlock, the looks. */
    private final io.github.mojolowjo.entropybot.aa.moves.Moves moves;
    private boolean active;
    private String doing = "none";
    private Use use = Use.NONE;
    private int useTicks;
    private BlockPos waterAt;
    private net.minecraft.world.item.Item drinkItem;
    private String[] leases;
    private long lastWhyLog = -100000;
    private long lastStep = -100000;
    private int errors;

    public FireReflex(EventRing events, EngineProcess engine, io.github.mojolowjo.entropybot.aa.moves.Moves moves) {
        this.events = events;
        this.engine = engine;
        this.moves = moves;
    }

    public boolean active() { return active; }

    public String doing() { return doing; }

    /** One tick; true when it took the tick (the fight code waits). fighting: a fight or duel runs. */
    public boolean tick(Minecraft mc, LocalPlayer p, long now, boolean fighting) {
        try {
            return step(mc, p, now, fighting);
        } catch (Throwable t) {
            errors++;
            if (errors <= 5 || errors % 1200 == 0) LOG.error("[entropybot] fire reflex error #{}: {}", errors, t.toString());
            stand(mc, "error");
            return false;
        }
    }

    private boolean step(Minecraft mc, LocalPlayer p, long now, boolean fighting) {
        // a running use goes first
        if (use == Use.AIM_WATER) {
            // backbone step 4: the camera comes down through LookBehavior; the bucket is used once it looks down
            useTicks++;
            BlockPos below = waterAt.below();
            moves.lookAt(below.getX() + 0.5, below.getY() + 1.0, below.getZ() + 0.5);
            if (p.getXRot() < 60 && useTicks < AIM_TICKS) return true;
            if (p.getXRot() < 60) LOG.info("[entropybot] fire reflex: the look did not come down in {} ticks (pitch {}), using the bucket anyway", AIM_TICKS, Math.round(p.getXRot()));
            if (!p.getMainHandItem().is(Items.WATER_BUCKET) && !select(mc, p, find(p, s -> s.is(Items.WATER_BUCKET)))) {
                stand(mc, "the water bucket is gone");
                return true;
            }
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false));
            use = Use.WATER_PLACED;
            useTicks = 0;
            return true;
        }
        if (use == Use.DRINK) {
            useTicks++;
            moves.lookAt(p.getX(), p.getEyeY() + 10, p.getZ());     // the sky, through LookBehavior (see drink)
            // the drink is gone from the hand (an empty bucket or bottle now): let go at once, never a second use
            boolean gone = drinkItem != null && !p.getMainHandItem().is(drinkItem);
            if (!gone) mc.options.keyUse.setDown(true);
            if (gone || useTicks > 45 || !p.isUsingItem() && useTicks > 5) {
                mc.options.keyUse.setDown(false);
                use = Use.NONE;
                note("drank (" + doing + ")");
                stand(mc, "drank");
            }
            return true;
        }
        if (use == Use.WATER_PLACED) {
            useTicks++;
            moves.lookAt(waterAt.getX() + 0.5, waterAt.getY() + 0.5, waterAt.getZ() + 0.5);
            if (useTicks == 4 || useTicks == 12) pickUpWater(mc, p);
            if (useTicks >= 16 || !p.level().getFluidState(waterAt).is(FluidTags.WATER)) {
                use = Use.NONE;
                stand(mc, "water picked up");
            }
            return true;
        }
        if (mc.screen != null || p.containerMenu != p.inventoryMenu) return false;
        Level level = p.level();
        BlockPos feet = p.blockPosition();
        boolean resistant = p.hasEffect(MobEffects.FIRE_RESISTANCE);
        boolean inHazard = hazard(level, feet) || hazard(level, feet.above());
        boolean onFire = p.getRemainingFireTicks() > 0 || p.isOnFire();
        if (onFire || inHazard) {
            int bucket = find(p, s -> s.is(Items.WATER_BUCKET));
            boolean nether = level.dimensionType().ultraWarm();
            // the guard part: restore-kind leases for the own feet cell (and the block clicked under it), held until picked up
            boolean pre = bucket >= 0 && onFire && !resistant && !inHazard && SurvivalRules.mayPlaceWater(true, nether, containerNear(level, feet), feetFree(level, feet));
            String[] got = pre ? Guard.INSTANCE.waterAtFeet(level, feet) : null;
            boolean may = pre && got != null;
            if (bucket >= 0 && onFire && !inHazard && !may && now - lastWhyLog > 100) {
                lastWhyLog = now;
                LOG.info("[entropybot] fire reflex: water bucket not used: {}", nether ? "the Nether" : containerNear(level, feet) ? "a container near"
                        : !feetFree(level, feet) ? "my feet cell is taken" : resistant ? "fire resistant" : "the guard (see above)");
            }
            int potion = find(p, s -> potionHas(s, MobEffects.FIRE_RESISTANCE));
            BlockPos water = nearestWater(p);
            SurvivalRules.Fire act = SurvivalRules.fire(onFire, resistant, inHazard, bucket >= 0, may, water != null, potion >= 0);
            if (act == SurvivalRules.Fire.PLACE_WATER) leases = got;
            else Guard.INSTANCE.releaseLeases(got);
            switch (act) {
                case STEP_OUT -> {
                    // backbone step 4: GoalRunAway from the lava / fire cells around, at the reflex priority, at once
                    String what = level.getBlockState(feet).getFluidState().is(FluidTags.LAVA) ? "lava" : "fire";
                    begin("stepping out of " + what);
                    escape(now, level, what, ESCAPE_STEP, hazards(level, feet, 2), feet);
                    return true;
                }
                case PLACE_WATER -> {
                    begin("on fire: water bucket at my feet");
                    if (!select(mc, p, bucket)) {
                        Guard.INSTANCE.releaseLeases(leases);
                        leases = null;
                        return true;
                    }
                    // backbone step 4: the look through LookBehavior; the bucket goes on the first tick it looks down
                    endWay();
                    waterAt = feet;
                    use = Use.AIM_WATER;
                    useTicks = 0;
                    engine.hold();
                    BlockPos below = feet.below();
                    moves.lookAt(below.getX() + 0.5, below.getY() + 1.0, below.getZ() + 0.5);
                    return true;
                }
                case DRINK_FIRE_RES -> {
                    begin("on fire: drinking fire resistance");
                    return drink(mc, p, potion);
                }
                case WALK_WATER -> {
                    begin("on fire: walking into the water at " + water.getX() + " " + water.getY() + " " + water.getZ());
                    // backbone step 4: GoalBlock into the water at the reflex priority; a failed one again after a second
                    boolean mine = "walk into water".equals(moves.way());
                    boolean failed = mine && moves.phase() == io.github.mojolowjo.entropybot.aa.moves.Moves.Phase.FAILED;
                    if (!mine || moves.phase() == io.github.mojolowjo.entropybot.aa.moves.Moves.Phase.IDLE || failed && now - lastStep > 20) {
                        if (failed) LOG.info("[entropybot] fire reflex: {} - again", moves.why());
                        moves.reach("walk into water", new int[]{water.getX(), water.getY(), water.getZ()});
                        lastStep = now;
                    }
                    return true;
                }
                case MOVE_AWAY -> {
                    begin("on fire: moving away from the fire");
                    // backbone step 4: GoalRunAway from the fire / lava near (none seen: from where I burn)
                    escape(now, level, "fire", ESCAPE_AWAY, hazards(level, feet, 3), feet);
                    return true;
                }
                default -> { }
            }
        }
        if (active && doing.startsWith("on fire") || active && doing.startsWith("stepping")) stand(mc, onFire ? "fire resistant" : "the fire is out");
        return false;
    }


    /** Backbone step 4: blocks the escape runs from the hazard cells (stepping out; moving away while burning). */
    static final int ESCAPE_STEP = 3, ESCAPE_AWAY = 6;

    /**
     * Backbone step 4: the escape way on the reflexes' Moves: started once (the car re-asks after a Baritone cancel), a
     * failed or finished one started again after a second (a failure logged). No hazard cells: from the feet.
     */
    private void escape(long now, Level level, String what, int dist, java.util.List<int[]> cells, BlockPos feet) {
        if (cells.isEmpty()) cells = java.util.List.of(new int[]{feet.getX(), feet.getY(), feet.getZ()});
        var ph = moves.phase();
        String w = moves.way();
        boolean mine = ("escape " + what).equals(w), fallback = STEP_OUT_WAY.equals(w);
        if ((mine || fallback) && ph == io.github.mojolowjo.entropybot.aa.moves.Moves.Phase.RUNNING) return;
        // 0.39.m2 (documented exception to "GoalRunAway"): Baritone found no run-away cell: at once the old step-out,
        // GoalBlock on a dry neighbour (safeNeighbour), so a failed path never leaves the bot burning
        if (mine && ph == io.github.mojolowjo.entropybot.aa.moves.Moves.Phase.FAILED) {
            BlockPos out = safeNeighbour(level, feet);
            LOG.info("[entropybot] fire reflex: {} - {}", moves.why(), out == null ? "no dry neighbour either, the run again" : "stepping to the dry cell " + out.getX() + " " + out.getY() + " " + out.getZ());
            if (out != null) {
                moves.reach(STEP_OUT_WAY, new int[]{out.getX(), out.getY(), out.getZ()});
                lastStep = now;
                return;
            }
        } else if ((mine || fallback) && ph != io.github.mojolowjo.entropybot.aa.moves.Moves.Phase.IDLE && now - lastStep < 20) return;
        if (fallback && ph == io.github.mojolowjo.entropybot.aa.moves.Moves.Phase.FAILED) LOG.info("[entropybot] fire reflex: {} - the run again", moves.why());
        moves.escape(what, dist, cells);
        lastStep = now;
    }

    static final String STEP_OUT_WAY = "step out";

    /** The lava / fire cells within r of the feet (dy -1..2). */
    private static java.util.List<int[]> hazards(Level level, BlockPos feet, int r) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (BlockPos q : BlockPos.betweenClosed(feet.offset(-r, -1, -r), feet.offset(r, 2, r)))
            if (hazard(level, q)) out.add(new int[]{q.getX(), q.getY(), q.getZ()});
        return out;
    }

    /** Ends the reflexes' way when it is one of the fire reflex's own. */
    private void endWay() {
        String w = moves.way();
        if (moves.phase() != io.github.mojolowjo.entropybot.aa.moves.Moves.Phase.IDLE && (w.startsWith("escape ") || w.equals("walk into water") || w.equals(STEP_OUT_WAY))) moves.release();
    }

    private void begin(String what) {
        if (active && what.equals(doing)) return;
        active = true;
        doing = what;
        note(what);
    }

    private void note(String what) {
        events.push("reflex", what, null);
        LOG.info("[entropybot] fire reflex: {}", what);
    }

    private void stand(Minecraft mc, String why) {
        if (!active) return;
        active = false;
        mc.options.keyUse.setDown(false);
        endWay();
        use = Use.NONE;
        Guard.INSTANCE.releaseLeases(leases);
        leases = null;
        events.push("reflex", "done " + doing + ": " + why, null);
        doing = "none";
        engine.release();
    }

    private boolean drink(Minecraft mc, LocalPlayer p, int slot) {
        if (!select(mc, p, slot)) return true;
        drinkItem = p.getMainHandItem().getItem();
        // look at the sky: the key is still down the tick the drink ends, and an empty bottle's use then hits nothing
        // (backbone step 4: through LookBehavior, held every drink tick; the drink takes 32 ticks, the look a few)
        endWay();
        moves.lookAt(p.getX(), p.getEyeY() + 10, p.getZ());
        engine.hold();
        use = Use.DRINK;
        useTicks = 0;
        mc.options.keyUse.setDown(true);
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        return true;
    }

    private void pickUpWater(Minecraft mc, LocalPlayer p) {
        int b = find(p, s -> s.is(Items.BUCKET));
        if (b < 0 || !select(mc, p, b)) return;
        if (p.getXRot() < 60) LOG.info("[entropybot] fire reflex: picking the water up with the look at pitch {} (LookBehavior not down yet)", Math.round(p.getXRot()));
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
    }

    /** The slot (0..35) of the first matching stack, or -1. */
    static int find(LocalPlayer p, Predicate<ItemStack> f) {
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && f.test(st)) return i;
        }
        return -1;
    }

    /** Puts the slot in the hand (a hotbar slot is selected, a bag slot SWAPped into the selected one). */
    private static boolean select(Minecraft mc, LocalPlayer p, int slot) {
        if (slot < 0) return false;
        var inv = p.getInventory();
        if (slot < 9) inv.selected = slot;
        else mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, slot, inv.selected, ClickType.SWAP, p);
        return true;
    }

    static boolean potionHas(ItemStack s, Holder<MobEffect> effect) {
        if (!s.is(Items.POTION)) return false;          // drinkable only (never splash or lingering)
        PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
        if (pc == null) return false;
        for (MobEffectInstance i : pc.getAllEffects()) if (i.getEffect().equals(effect)) return true;
        return false;
    }

    private static boolean hazard(Level level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        return st.is(BlockTags.FIRE) || st.getFluidState().is(FluidTags.LAVA);
    }

    private static boolean feetFree(Level level, BlockPos feet) {
        BlockState st = level.getBlockState(feet);
        return st.isAir() || st.is(BlockTags.FIRE) || st.canBeReplaced() && !st.getFluidState().is(FluidTags.LAVA);
    }


    private static boolean containerNear(Level level, BlockPos feet) {
        for (BlockPos q : BlockPos.betweenClosed(feet.offset(-2, -1, -2), feet.offset(2, 2, 2))) {
            if (level.getBlockEntity(q) != null) return true;
        }
        return false;
    }

    private static BlockPos safeNeighbour(Level level, BlockPos feet) {
        BlockPos best = null;
        for (int r = 1; r <= 2 && best == null; r++) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos q = feet.relative(d, r);
                if (!hazard(level, q) && !hazard(level, q.above()) && !hazard(level, q.below())
                        && level.getBlockState(q).getCollisionShape(level, q).isEmpty()
                        && !level.getBlockState(q.below()).getCollisionShape(level, q.below()).isEmpty()) {
                    best = q;
                    break;
                }
            }
        }
        return best;
    }

    /** The nearest water cell within 8 on the threat grid, or null. */
    private static BlockPos nearestWater(LocalPlayer p) {
        ReachGrid g = ThreatRuntime.INSTANCE.grid();
        if (g == null) return null;
        int bx = p.getBlockX(), by = p.getBlockY(), bz = p.getBlockZ(), r = SurvivalRules.WATER_R;
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int dy = -3; dy <= 2; dy++) for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            int x = bx + dx - g.ox, y = by + dy - g.oy, z = bz + dz - g.oz;
            if (!g.in(x, y, z) || g.codes[g.idx(x, y, z)] != ReachGrid.WATER) continue;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bd && d <= r * r) {
                bd = d;
                best = new BlockPos(bx + dx, by + dy, bz + dz);
            }
        }
        return best;
    }
}