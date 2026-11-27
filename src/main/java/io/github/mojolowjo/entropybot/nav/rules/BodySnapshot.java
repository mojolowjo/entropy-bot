package io.github.mojolowjo.entropybot.nav.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * nav step 4 (to-do/nav-steps-1-6.md): what the planner needs to know about the body, copied once on the client thread
 * before a search starts. Baritone's {@code CalculationContext} and {@code ToolSet} read these straight off the live
 * {@code LocalPlayer} (from the search thread, too); ours read this frozen copy, so a search is repeatable and JUnit
 * can build one by hand. The game side (step 8) fills it from the player: hotbar slots 0-8, the selected slot, the
 * DIG_SPEED / DIG_SLOWDOWN amplifiers (-1 = no effect), the frost walker level (0 = none) and the multiplier
 * Baritone reads from a worn enchantment's WATER_MOVEMENT_EFFICIENCY attribute (1.0 = none, Baritone's start value), the food level, whether a water bucket is on the hotbar, whether a throwaway
 * block is in the bag (Baritone: {@code InventoryBehavior.hasGenericThrowaway}), and whether this is the nether.
 *
 * <p>Pure (JUnit: ToolSetTest, RulesMatchForkTest). Loader notes: ItemStack only (common side, Mojang names).
 */
public record BodySnapshot(List<ItemStack> hotbar, int selected, int hasteAmplifier, int fatigueAmplifier,
                           int frostWalker, float waterSpeedMultiplier, int foodLevel, boolean waterBucketOnHotbar,
                           boolean hasThrowaway, boolean nether) {

    public BodySnapshot {
        if (hotbar.size() != 9) throw new IllegalArgumentException("a hotbar has 9 slots, got " + hotbar.size());
        List<ItemStack> copy = new ArrayList<>(9);
        for (ItemStack s : hotbar) copy.add(s == null ? ItemStack.EMPTY : s.copy());
        hotbar = Collections.unmodifiableList(copy);
    }

    /** The stack in hotbar slot i (0-8); never null. */
    public ItemStack hotbar(int i) { return hotbar.get(i); }

    /** An empty-handed, fed, effect-free body in the overworld with throwaway blocks: the planner's plain case. */
    public static BodySnapshot bare() {
        return new BodySnapshot(Collections.nCopies(9, ItemStack.EMPTY), 0, -1, -1, 0, 1f, 20, false, true, false);
    }

    /** The same body holding these stacks in slots 0.. (the rest empty). */
    public static BodySnapshot holding(ItemStack... stacks) {
        List<ItemStack> bar = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
        for (int i = 0; i < stacks.length; i++) bar.set(i, stacks[i]);
        return new BodySnapshot(bar, 0, -1, -1, 0, 1f, 20, false, true, false);
    }
}
