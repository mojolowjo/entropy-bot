package io.github.mojolowjo.entropybot.engine;

import baritone.api.pathing.goals.GoalNear;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.events.EventRing;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import io.github.mojolowjo.entropybot.threat.ReachGrid;
import io.github.mojolowjo.entropybot.threat.RunGuard;
import io.github.mojolowjo.entropybot.threat.ThreatRuntime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
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
 * 0.23.6 survival reflexes, the game side of {@link SurvivalRules}: fire (step out, water bucket placed at the feet and
 * picked up again, fire resistance, walk into water, move away), milk against poison/wither/hunger, a healing potion in
 * a fight under 6 health. Runs from {@link Reflexes#tick} before the fight code; while it acts, the reflexes hold the
 * job. Never throws (errors counted and logged, the reflex then stands down).
 *
 * <p>Loader notes: vanilla only: Entity.getRemainingFireTicks/hasEffect, MobEffects holders, DataComponents.POTION_CONTENTS,
 * MultiPlayerGameMode.useItemOn/useItem/handleInventoryMouseClick (SWAP), KeyMapping.setDown; Baritone goals through
 * {@link EngineProcess}; the water placement asks {@link GuardCore#check} first (and the guard mixin vetoes as always).
 */
public final class Survival {
    private static final Logger LOG = LogUtils.getLogger();

    private enum Use { NONE, DRINK, WATER_PLACED }

    private final EventRing events;
    private final EngineProcess engine;
    private boolean active;
    private String doing = "none";
    private Use use = Use.NONE;
    private int useTicks;
    private BlockPos waterAt;
    private long lastHeal = -100000, noMilkAt = -1, lastLog = -100000, lastStep = -100000;
    private String lastEffectsLine = "";
    private int errors;

    public Survival(EventRing events, EngineProcess engine) {
        this.events = events;
        this.engine = engine;
    }

    public boolean active() { return active; }

    public String doing() { return doing; }

    /** When the bot last had poison/wither/hunger and no milk (ms, -1 = never); for "check". */
    public long noMilkAt() { return noMilkAt; }

    /** One tick; true when it took the tick (the fight code waits). fighting: a fight or duel runs. */
    public boolean tick(Minecraft mc, LocalPlayer p, long now, boolean fighting) {
        try {
            return step(mc, p, now, fighting);
        } catch (Throwable t) {
            errors++;
            if (errors <= 5 || errors % 1200 == 0) LOG.error("[entropybot] survival error #{}: {}", errors, t.toString());
            stand(mc, "error");
            return false;
        }
    }

    private boolean step(Minecraft mc, LocalPlayer p, long now, boolean fighting) {
        // a running use goes first
        if (use == Use.DRINK) {
            useTicks++;
            mc.options.keyUse.setDown(true);
            if (useTicks > 45 || !p.isUsingItem() && useTicks > 5) {
                mc.options.keyUse.setDown(false);
                use = Use.NONE;
                note("drank (" + doing + ")");
                stand(mc, "drank");
            }
            return true;
        }
        if (use == Use.WATER_PLACED) {
            useTicks++;
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
            boolean may = bucket >= 0 && SurvivalRules.mayPlaceWater(guardAllows(level, feet), nether, containerNear(level, feet), feetFree(level, feet));
            int potion = find(p, s -> potionHas(s, MobEffects.FIRE_RESISTANCE));
            BlockPos water = nearestWater(p);
            SurvivalRules.Fire act = SurvivalRules.fire(onFire, resistant, inHazard, bucket >= 0, may, water != null, potion >= 0);
            switch (act) {
                case STEP_OUT -> {
                    BlockPos out = safeNeighbour(level, feet);
                    begin("stepping out of " + (level.getBlockState(feet).getFluidState().is(FluidTags.LAVA) ? "lava" : "fire"));
                    if (out != null && (now - lastStep > 10 || engine.mode() != EngineProcess.Mode.OVERRIDE)) {
                        engine.override(new GoalNear(out, 0));
                        lastStep = now;
                    }
                    return true;
                }
                case PLACE_WATER -> {
                    begin("on fire: water bucket at my feet");
                    if (!select(mc, p, bucket)) return true;
                    p.setXRot(90);
                    BlockPos below = feet.below();
                    mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false));
                    waterAt = feet;
                    use = Use.WATER_PLACED;
                    useTicks = 0;
                    engine.hold();
                    return true;
                }
                case DRINK_FIRE_RES -> {
                    begin("on fire: drinking fire resistance");
                    return drink(mc, p, potion);
                }
                case WALK_WATER -> {
                    begin("on fire: walking into the water at " + water.getX() + " " + water.getY() + " " + water.getZ());
                    if (now - lastStep > 20 || engine.mode() != EngineProcess.Mode.OVERRIDE) {
                        engine.override(new GoalNear(water, 0));
                        lastStep = now;
                    }
                    return true;
                }
                case MOVE_AWAY -> {
                    begin("on fire: moving away from the fire");
                    if (now - lastStep > 20 || engine.mode() != EngineProcess.Mode.OVERRIDE) {
                        double yaw = Math.toRadians(p.getYRot());
                        RunGuard.Verdict v = ThreatRuntime.INSTANCE.runGuard(p, -Math.sin(yaw), Math.cos(yaw));
                        double dx = v.stop() ? -(-Math.sin(yaw)) : v.dirX(), dz = v.stop() ? -Math.cos(yaw) : v.dirZ();
                        engine.override(new GoalNear(BlockPos.containing(p.getX() + dx * 6, p.getY(), p.getZ() + dz * 6), 1));
                        lastStep = now;
                    }
                    return true;
                }
                default -> { }
            }
        }
        if (active && doing.startsWith("on fire") || active && doing.startsWith("stepping")) stand(mc, onFire ? "fire resistant" : "the fire is out");
        // effects
        Set<String> eff = new LinkedHashSet<>();
        for (MobEffectInstance i : p.getActiveEffects()) {
            var key = BuiltInRegistries.MOB_EFFECT.getKey(i.getEffect().value());
            if (key != null) eff.add(key.getPath());
        }
        if (eff.isEmpty()) return false;
        int milk = find(p, s -> s.is(Items.MILK_BUCKET));
        int heal = find(p, s -> potionHas(s, MobEffects.HEAL) || potionHas(s, MobEffects.REGENERATION));
        SurvivalRules.Effect e = SurvivalRules.effects(eff, milk >= 0, p.getHealth(), fighting, heal >= 0, now - lastHeal);
        switch (e) {
            case DRINK_HEAL -> {
                lastHeal = now;
                begin("health " + Math.round(p.getHealth()) + " in a fight: drinking a potion");
                return drink(mc, p, heal);
            }
            case DRINK_MILK -> {
                if (fighting) return false;          // after the fight
                begin("drinking milk (" + String.join(", ", eff) + ")");
                return drink(mc, p, milk);
            }
            case NOTE_NO_MILK -> {
                if (noMilkAt < 0 || System.currentTimeMillis() - noMilkAt > 60_000) LOG.info("[entropybot] survival: {} and no milk bucket on me", eff);
                noMilkAt = System.currentTimeMillis();
                logEffects(eff, now);
                return false;
            }
            case LOG -> {
                logEffects(eff, now);
                return false;
            }
            default -> { return false; }
        }
    }

    private void logEffects(Set<String> eff, long now) {
        String line = String.join(", ", eff);
        if (line.equals(lastEffectsLine) && now - lastLog < 1200) return;
        lastEffectsLine = line;
        lastLog = now;
        events.push("reflex", "effects: " + line, null);
    }

    private void begin(String what) {
        if (active && what.equals(doing)) return;
        active = true;
        doing = what;
        note(what);
    }

    private void note(String what) {
        events.push("reflex", what, null);
        LOG.info("[entropybot] survival: {}", what);
    }

    private void stand(Minecraft mc, String why) {
        if (!active) return;
        active = false;
        mc.options.keyUse.setDown(false);
        use = Use.NONE;
        events.push("reflex", "done " + doing + ": " + why, null);
        doing = "none";
        engine.release();
    }

    private boolean drink(Minecraft mc, LocalPlayer p, int slot) {
        if (!select(mc, p, slot)) return true;
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
        p.setXRot(90);
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

    private static boolean guardAllows(Level level, BlockPos pos) {
        return Guard.INSTANCE.mayPlaceLiquid(level, pos);
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
