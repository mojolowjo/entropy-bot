package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.GameTimes;
import io.github.mojolowjo.entropybot.aa.brain.ToolWear;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * aa-times: the thin adapter of {@link GameTimes}: reads the game's own terms with vanilla calls (the same ones exist on
 * Fabric) and never swaps the held item. Calls used:
 * <ul>
 * <li>{@code ItemStack.getDestroySpeed(BlockState)}, {@code ItemStack.isCorrectToolForDrops(BlockState)},
 *     {@code BlockState.requiresCorrectToolForDrops()}, {@code BlockState.getDestroySpeed(BlockGetter, BlockPos)} (hardness);</li>
 * <li>{@code LivingEntity.getAttributeBaseValue(MINING_EFFICIENCY)} + the candidate tool's enchantment attribute modifiers
 *     ({@code EnchantmentHelper.forEachModifier(stack, EquipmentSlot.MAINHAND, ...)}: Efficiency level² + 1, data-driven, so a
 *     modded mining enchantment counts too), {@code getAttributeValue(BLOCK_BREAK_SPEED)}, {@code getAttributeValue(SUBMERGED_MINING_SPEED)}
 *     (the helmet's Aqua Affinity is already in it);</li>
 * <li>{@code MobEffectUtil.hasDigSpeed / getDigSpeedAmplification} (Haste, Conduit Power), {@code getEffect(MobEffects.DIG_SLOWDOWN)};</li>
 * <li>{@code Entity.isEyeInFluid(FluidTags.WATER)}, {@code Entity.onGround()};</li>
 * <li>{@code LivingEntity.getAttribute(MOVEMENT_SPEED)}: its value with the "minecraft:sprinting" modifier taken out again,
 *     {@code getAttributeValue(WATER_MOVEMENT_EFFICIENCY)} (Depth Strider).</li>
 * </ul>
 * NeoForge's {@code EventHooks.getBreakSpeed} (PlayerEvent.BreakSpeed, where other mods may change the speed) is not run here:
 * a candidate tool cannot be passed to it. Never throws: a read that fails falls back to the plain formula and logs once.
 */
public final class GameTimesLive {
    private GameTimesLive() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final ResourceLocation SPRINTING = ResourceLocation.withDefaultNamespace("sprinting");
    private static boolean warned;

    /** The player's terms for a candidate tool (the attribute base + that tool's own enchantment modifiers). */
    public static GameTimes.Dig dig(Player p, ItemStack tool, boolean inWater, boolean onGround) {
        double eff = p.getAttributeBaseValue(Attributes.MINING_EFFICIENCY);
        if (tool != null && !tool.isEmpty()) eff = withModifiers(tool, eff);
        int haste = MobEffectUtil.hasDigSpeed(p) ? MobEffectUtil.getDigSpeedAmplification(p) : -1;
        var fatigue = p.getEffect(MobEffects.DIG_SLOWDOWN);
        return new GameTimes.Dig(eff, haste, fatigue == null ? -1 : fatigue.getAmplifier(),
                p.getAttributeValue(Attributes.BLOCK_BREAK_SPEED), p.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED), inWater, onGround);
    }

    /** MINING_EFFICIENCY with the candidate tool's enchantment modifiers applied as an attribute instance would. */
    private static double withModifiers(ItemStack tool, double base) {
        double[] add = {0}, mulBase = {0}, mulTotal = {1};
        EnchantmentHelper.forEachModifier(tool, EquipmentSlot.MAINHAND, (attr, mod) -> {
            if (attr.value() != Attributes.MINING_EFFICIENCY.value()) return;
            switch (mod.operation()) {
                case ADD_VALUE -> add[0] += mod.amount();
                case ADD_MULTIPLIED_BASE -> mulBase[0] += mod.amount();
                case ADD_MULTIPLIED_TOTAL -> mulTotal[0] *= 1 + mod.amount();
            }
        });
        return (base + add[0]) * (1 + mulBase[0]) * mulTotal[0];
    }

    /**
     * Seconds to break {@code block} with {@code tool} (empty = by hand) the way the game counts them; the player's
     * own water / ground state when the flags are not given. Infinite for an unbreakable block.
     */
    public static double breakSeconds(Player p, BlockState block, ItemStack tool, boolean inWater, boolean onGround) {
        try {
            ItemStack t = tool == null ? ItemStack.EMPTY : tool;
            float hardness = block.getDestroySpeed(p.level(), p.blockPosition());
            double speed = t.getDestroySpeed(block);
            boolean correct = !block.requiresCorrectToolForDrops() || t.isCorrectToolForDrops(block);
            return GameTimes.breakSeconds(hardness, speed, correct, dig(p, t, inWater, onGround));
        } catch (RuntimeException e) {
            warn("break time", e);
            return ToolWear.withTool(ToolWear.STONE_SPEED);
        }
    }

    public static double breakSeconds(Player p, BlockState block, ItemStack tool) {
        return breakSeconds(p, block, tool, p.isEyeInFluid(FluidTags.WATER), p.onGround());
    }

    /** Seconds per stone block with {@code tool} (empty = by hand) here and now. */
    public static double stoneSeconds(Player p, ItemStack tool) {
        return breakSeconds(p, Blocks.STONE.defaultBlockState(), tool);
    }

    /**
     * Seconds per stone block with the best pickaxe in the bag (36 slots, the lowest time wins); by hand when there is
     * none. The ToolWear default (a stone pickaxe) when the player cannot be read.
     */
    public static double bestPickaxeStoneSeconds(Player p) {
        try {
            BlockState stone = Blocks.STONE.defaultBlockState();
            boolean water = p.isEyeInFluid(FluidTags.WATER), ground = p.onGround();
            double best = breakSeconds(p, stone, ItemStack.EMPTY, water, ground);
            for (int i = 0; i < 36; i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (st.isEmpty() || !Commands.itemId(st).endsWith("_pickaxe")) continue;
                best = Math.min(best, breakSeconds(p, stone, st, water, ground));
            }
            return best;
        } catch (RuntimeException e) {
            warn("best pickaxe", e);
            return ToolWear.withTool(ToolWear.STONE_SPEED);
        }
    }

    /**
     * Blocks a second walking (not sprinting) from the MOVEMENT_SPEED attribute: Speed / Slowness and Soul Speed are
     * modifiers on it; the sprint modifier is taken out when present. {@link GameTimes#BASE_WALK_BPS} when unreadable.
     */
    public static double walkBps(Player p) {
        try {
            AttributeInstance a = p.getAttribute(Attributes.MOVEMENT_SPEED);
            if (a == null) return GameTimes.BASE_WALK_BPS;
            AttributeModifier sprint = a.getModifier(SPRINTING);
            return GameTimes.walkBps(a.getValue(), sprint != null);
        } catch (RuntimeException e) {
            warn("walk speed", e);
            return GameTimes.BASE_WALK_BPS;
        }
    }

    /** Blocks a second swimming, Depth Strider (WATER_MOVEMENT_EFFICIENCY) in. */
    public static double swimBps(Player p) {
        try {
            return GameTimes.swimBps(walkBps(p), p.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY));
        } catch (RuntimeException e) {
            warn("swim speed", e);
            return GameTimes.BASE_SWIM_BPS;
        }
    }

    /** One line for status / check: "walk 4.3 b/s, stone 0.6 s (hand 7.5 s)". */
    public static String status(Player p) {
        return String.format(java.util.Locale.ROOT, "walk %.2f b/s, stone %.2f s with the best pickaxe (hand %.2f s)",
                walkBps(p), bestPickaxeStoneSeconds(p), stoneSeconds(p, ItemStack.EMPTY));
    }

    private static void warn(String what, RuntimeException e) {
        if (warned) return;
        warned = true;
        LOG.warn("[entropybot] aa-times: {} could not be read from the game, plain formula used: {}", what, e.toString());
    }
}
