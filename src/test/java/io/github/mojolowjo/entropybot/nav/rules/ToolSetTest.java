package io.github.mojolowjo.entropybot.nav.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mojolowjo.entropybot.nav.config.NavConfig;
import io.github.mojolowjo.entropybot.sim.SimSearch;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** nav step 4: our ToolSet reads a hotbar snapshot; its maths equal the fork jar's, its choices are Baritone's. */
class ToolSetTest {

    @BeforeAll
    static void boot() { SimSearch.boot(); }

    @Test
    void speedEqualsTheForkForEveryItemOnEveryBlock() {
        List<Item> items = new ArrayList<>();
        BuiltInRegistries.ITEM.forEach(items::add);
        int compared = 0;
        for (Block b : BuiltInRegistries.BLOCK) {
            BlockState s = b.defaultBlockState();
            for (int i = 0; i < items.size(); i += 7) { // every 7th item: tools are spread through the registry
                ItemStack st = new ItemStack(items.get(i));
                assertEquals(baritone.utils.ToolSet.calculateSpeedVsBlock(st, s), ToolSet.calculateSpeedVsBlock(st, s), st + " on " + s);
                compared++;
            }
        }
        for (Block b : BuiltInRegistries.BLOCK) { // and every pickaxe, shovel and axe tier on every block
            for (Item it : List.of(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE,
                    Items.IRON_SHOVEL, Items.IRON_AXE, Items.SHEARS, Items.IRON_HOE, Items.IRON_SWORD)) {
                ItemStack st = new ItemStack(it);
                assertEquals(baritone.utils.ToolSet.calculateSpeedVsBlock(st, b.defaultBlockState()), ToolSet.calculateSpeedVsBlock(st, b.defaultBlockState()));
                compared++;
            }
        }
        assertTrue(compared > 10_000, "compared " + compared);
    }

    // JUnit has the registries but no tag data, so a tool's speed on a block (a tag rule in 1.21) is not known here:
    // these cases check the hotbar reading and the settings, not which tool fits which block (the game does that).
    @Test
    void readsTheSnapshotAndTheSettings() {
        NavConfig.Breaking d = NavConfig.defaults().breaking();
        BodySnapshot bar = new BodySnapshot(BodySnapshot.holding(new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.STICK)).hotbar(),
                1, -1, -1, 0, 1f, 20, false, true, false);
        NavConfig.Breaking noAutoTool = new NavConfig.Breaking(d.allowBreak(), d.allowBreakAnyway(), d.blockBreakAdditionalPenalty(), d.blocksToDisallowBreaking(),
                d.blocksToAvoidBreaking(), d.avoidBreakingMultiplier(), d.allowBreakNextToWater(), d.allowBreakNextToLava(), d.avoidUpdatingFallingBlocks(),
                d.considerPotionEffects(), d.useSwordToMine(), d.itemSaver(), d.itemSaverThreshold(), false, d.assumeExternalAutoTool(), d.preferSilkTouch());
        assertEquals(1, new ToolSet(bar, noAutoTool).getBestSlot(Blocks.STONE, false, true), "autoTool off: the search prices the held item");
        ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
        worn.setDamageValue(worn.getMaxDamage() - 3);
        NavConfig.Breaking saver = new NavConfig.Breaking(d.allowBreak(), d.allowBreakAnyway(), d.blockBreakAdditionalPenalty(), d.blocksToDisallowBreaking(),
                d.blocksToAvoidBreaking(), d.avoidBreakingMultiplier(), d.allowBreakNextToWater(), d.allowBreakNextToLava(), d.avoidUpdatingFallingBlocks(),
                d.considerPotionEffects(), d.useSwordToMine(), true, d.itemSaverThreshold(), true, d.assumeExternalAutoTool(), d.preferSilkTouch());
        ToolSet t = new ToolSet(new BodySnapshot(BodySnapshot.holding(worn).hotbar(), 0, -1, -1, 0, 1f, 20, false, true, false), saver);
        assertTrue(t.getBestSlot(Blocks.STONE, false) != 0, "itemSaver: a tool 3 uses from breaking is not used");
        double stone = t.getStrVsBlock(Blocks.STONE.defaultBlockState());
        assertEquals(ToolSet.calculateSpeedVsBlock(ItemStack.EMPTY, Blocks.STONE.defaultBlockState()), stone, 1e-12);
    }

    @Test
    void hasteAndFatigueScaleAsBaritone() {
        NavConfig.Breaking cfg = NavConfig.defaults().breaking();
        BodySnapshot plain = BodySnapshot.holding(new ItemStack(Items.IRON_PICKAXE));
        BodySnapshot haste2 = new BodySnapshot(plain.hotbar(), 0, 1, -1, 0, 1f, 20, false, true, false);
        BodySnapshot fatigue3 = new BodySnapshot(plain.hotbar(), 0, -1, 2, 0, 1f, 20, false, true, false);
        BlockState stone = Blocks.STONE.defaultBlockState();
        double base = new ToolSet(plain, cfg).getStrVsBlock(stone);
        assertEquals(base * 1.4, new ToolSet(haste2, cfg).getStrVsBlock(stone), 1e-12);
        assertEquals(base * 0.0027, new ToolSet(fatigue3, cfg).getStrVsBlock(stone), 1e-12);
    }

    @Test
    void avoidedBlocksCostTheMultiplier() {
        NavConfig.Breaking cfg = NavConfig.defaults().breaking();
        ToolSet t = new ToolSet(BodySnapshot.holding(new ItemStack(Items.IRON_AXE)), cfg);
        double plank = ToolSet.calculateSpeedVsBlock(new ItemStack(Items.IRON_AXE), Blocks.CRAFTING_TABLE.defaultBlockState());
        assertEquals(plank * cfg.avoidBreakingMultiplier(), t.getStrVsBlock(Blocks.CRAFTING_TABLE.defaultBlockState()), 1e-12);
    }
}
