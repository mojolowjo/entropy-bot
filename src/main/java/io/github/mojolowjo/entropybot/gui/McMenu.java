package io.github.mojolowjo.entropybot.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.regex.Pattern;

/** The open container menu for {@link GuiCore}: the game's slots, cursor and clicks (handleInventoryMouseClick). */
public final class McMenu implements GuiMenu {
    /** Upgrade, filter and ghost slots of modded storage, RS's ResourceSlot: never read from, never written to (the bridge's GUI_SKIP_RE). */
    public static final Pattern SKIP = SlotRules.SKIP;
    private static ItemStack dirt, coal;

    private final AbstractContainerMenu menu;
    private final LocalPlayer player;

    public McMenu(LocalPlayer player) {
        this.player = player;
        this.menu = player.containerMenu;
    }

    public AbstractContainerMenu menu() { return menu; }

    private Slot slot(int i) { return menu.getSlot(i); }

    private ItemStack stack(int i) { return slot(i).getItem(); }

    @Override public int size() { return menu.slots.size(); }

    @Override public String id(int i) {
        ItemStack s = stack(i);
        return s.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    @Override public int count(int i) { return stack(i).getCount(); }

    @Override public int stackMax(int i) {
        ItemStack s = stack(i);
        return s.isEmpty() ? 0 : s.getMaxStackSize();
    }

    private boolean playerSlot(int i) { return slot(i).container == player.getInventory(); }

    @Override public boolean mine(int i) { return playerSlot(i) && slot(i).getContainerSlot() < 36; }

    @Override public boolean armor(int i) { return playerSlot(i) && slot(i).getContainerSlot() >= 36; }

    @Override public String probe(int i) {
        Slot s = slot(i);
        if (dirt == null) {
            dirt = new ItemStack(Items.DIRT);
            coal = new ItemStack(Items.COAL);
        }
        // a slot the player can't take from is a display slot; only ask about one that holds something
        // (Sophisticated Storage says no for every empty slot). The rules: SlotRules.classify
        String cls = s.getClass().getName();
        if (SlotRules.skip(cls)) return "other";
        boolean holds = s.hasItem(), pickup = !holds || pickup(s);
        boolean d = pickup && place(s, dirt), c = pickup && !d && place(s, coal);
        return SlotRules.classify(menu.getClass().getName(), cls, holds, pickup, d, c);
    }

    private boolean pickup(Slot s) {
        try { return s.mayPickup(player); } catch (RuntimeException e) { return true; }
    }

    private static boolean place(Slot s, ItemStack st) {
        try { return s.mayPlace(st); } catch (RuntimeException e) { return false; }
    }

    @Override public boolean mayPlace(int to, int from) { return place(slot(to), stack(from)); }

    @Override public int slotLimit(int to, int from) {
        int n = 0;
        try { n = slot(to).getMaxStackSize(stack(from)); } catch (RuntimeException ignored) {}
        if (n <= 0) n = stack(from).getMaxStackSize();
        return n > 0 ? n : 64;
    }

    @Override public boolean same(int a, int b) {
        ItemStack x = stack(a), y = stack(b);
        return !x.isEmpty() && !y.isEmpty() && ItemStack.isSameItemSameComponents(x, y);
    }

    @Override public Boolean cursorHas() {
        try { return !menu.getCarried().isEmpty(); } catch (RuntimeException e) { return null; }
    }

    @Override public boolean cursorSame(int i) {
        ItemStack c = menu.getCarried(), s = stack(i);
        return !c.isEmpty() && !s.isEmpty() && ItemStack.isSameItemSameComponents(c, s);
    }

    @Override public void click(int i, int button, String type) {
        Minecraft.getInstance().gameMode.handleInventoryMouseClick(menu.containerId, i, button, ClickType.valueOf(type), player);
    }

    /** The slot's class name for guiDescribe. */
    public String slotClass(int i) { return slot(i).getClass().getSimpleName(); }
}
