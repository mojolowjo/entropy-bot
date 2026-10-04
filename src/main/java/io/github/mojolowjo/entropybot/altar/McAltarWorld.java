package io.github.mojolowjo.entropybot.altar;

import io.github.mojolowjo.entropybot.gui.Gui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link AltarWorld} over the client (client thread). Contents are read from the client's copy of the block entity
 * (its saved data: {@code {Items: [{Slot, id, count}], Active, Progress}}, as item 14 read them with KubeJS's
 * {@code entityData}); no Mystical Agriculture class is named. A click holds the item (a hotbar slot selected, or a bag
 * slot swapped into the selected one) and right-clicks the block's center like {@code Jobs.useBlock}: the pedestal or
 * the altar takes the item (a use, not a placement: see docs/wave2-e.md "The guard").
 */
public final class McAltarWorld implements AltarWorld {
    private final Minecraft mc = Minecraft.getInstance();
    private final LocalPlayer p;
    private final Level level;

    public McAltarWorld(LocalPlayer p) {
        this.p = p;
        this.level = p.level();
    }

    private static BlockPos bp(int[] pos) { return new BlockPos(pos[0], pos[1], pos[2]); }

    @Override
    public String block(int[] pos) {
        BlockPos b = bp(pos);
        if (!level.isLoaded(b)) return null;
        BlockState st = level.getBlockState(b);
        return BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
    }

    /** The block entity's saved data on the client, or null. */
    private CompoundTag data(int[] pos) {
        BlockPos b = bp(pos);
        if (!level.isLoaded(b)) return null;
        BlockEntity be = level.getBlockEntity(b);
        if (be == null) return null;
        try {
            return be.saveWithoutMetadata(level.registryAccess());
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public Map<Integer, Stack> items(int[] pos) {
        CompoundTag tag = data(pos);
        if (tag == null) return null;
        // Cucumber's inventory writes "Items" at the top (item 14: {Items:[],Size:1}); some mods nest it in "Inventory"
        ListTag list = tag.contains("Items", Tag.TAG_LIST) ? tag.getList("Items", Tag.TAG_COMPOUND)
                : tag.contains("Inventory", Tag.TAG_COMPOUND) ? tag.getCompound("Inventory").getList("Items", Tag.TAG_COMPOUND) : null;
        Map<Integer, Stack> out = new HashMap<>();
        if (list == null) return out;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            String id = e.getString("id");
            int slot = e.contains("Slot") ? e.getInt("Slot") : i;
            int count = e.contains("count") ? e.getInt("count") : e.contains("Count") ? e.getInt("Count") : 1;
            if (!id.isEmpty() && count > 0 && !id.equals("minecraft:air")) out.put(slot, new Stack(id, count));
        }
        return out;
    }

    @Override
    public Boolean active(int[] altar) {
        CompoundTag tag = data(altar);
        return tag == null || !tag.contains("Active") ? null : tag.getBoolean("Active");
    }

    @Override
    public int bag(String id) { return Gui.inventory(p).getOrDefault(id, 0); }

    @Override
    public int freeSlots() {
        int n = 0;
        for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) n++;
        return n;
    }

    @Override
    public boolean ready() {
        if (mc.screen == null && !Gui.open(p)) return true;
        Gui.close(p);
        return false;
    }

    private static String id(ItemStack s) { return s.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(s.getItem()).toString(); }

    /** Gets {@code item} (null = nothing) into the main hand: a hotbar slot selected, or a bag slot swapped in. */
    private String hold(String item) {
        Inventory inv = p.getInventory();
        if (item == null ? inv.getSelected().isEmpty() : item.equals(id(inv.getSelected()))) return null;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (item == null ? s.isEmpty() : item.equals(id(s))) {
                inv.selected = i;
                return null;
            }
        }
        if (p.containerMenu != p.inventoryMenu) return "a menu is open";
        for (int i = 9; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (item == null ? s.isEmpty() : item.equals(id(s))) {
                // inventoryMenu: bag slot i (9..35) is menu slot i; SWAP with the selected hotbar slot
                mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, i, inv.selected, ClickType.SWAP, p);
                break;
            }
        }
        ItemStack held = inv.getSelected();
        if (item == null ? !held.isEmpty() : !item.equals(id(held))) {
            return item == null ? "I have no empty slot for an empty hand" : "couldn't get " + item + " into my hand";
        }
        return null;
    }

    @Override
    public String use(int[] pos, String item) {
        if (mc.gameMode == null) return "not in a world";
        Vec3 center = new Vec3(pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5);
        double dist = p.getEyePosition().distanceTo(center);
        if (dist > AltarPlan.REACH) return "it is out of my reach (" + Math.round(dist * 10) / 10.0 + " blocks)";
        String h = hold(item);
        if (h != null) return h;
        p.lookAt(EntityAnchorArgument.Anchor.EYES, center);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(center, Direction.UP, bp(pos), false));
        p.swing(InteractionHand.MAIN_HAND);
        return null;
    }

    /** Feet and head free, something solid underneath (as the farm's standable). */
    public boolean standable(int[] pos) {
        BlockPos feet = bp(pos), head = feet.above(), below = feet.below();
        if (!level.isLoaded(feet)) return false;
        BlockState f = level.getBlockState(feet), h = level.getBlockState(head), b = level.getBlockState(below);
        if (!f.getCollisionShape(level, feet).isEmpty() || !f.getFluidState().isEmpty()) return false;
        if (!h.getCollisionShape(level, head).isEmpty() || !h.getFluidState().isEmpty()) return false;
        return !b.getCollisionShape(level, below).isEmpty() && b.getFluidState().isEmpty();
    }
}
