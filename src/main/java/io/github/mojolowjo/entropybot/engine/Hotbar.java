package io.github.mojolowjo.entropybot.engine;

import io.github.mojolowjo.entropybot.gui.Gui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The hotbar layout in the game (package B, 2026-10-03; the rules are {@link HotbarRules}): the commands keep the
 * layout and the ore-tool setting here (from commands.json), the reflexes and {@code BotAPI} read them, and once
 * the bot has been idle for 2 seconds (no job but a walk or a wait, no reflex, no menu, no swing, nothing in use)
 * it makes one swap every half second until each laid-out slot holds its item. That covers "after a deposit, a
 * restock, a craft, a pickup": whatever changed the bag, the slots are put right when the bot is next idle.
 * Round 2 ({@link HotbarKeeper}): also in the first 10 seconds of a job, and mid-job for a laid-out slot whose item
 * was used up (a broken pickaxe, the last torch, the food eaten); never mid-swing or mid-break (except a broken tool's
 * refill), never with a menu open, never swapping away an item a job put in the hand.
 */
public final class Hotbar {
    private Hotbar() {}

    private static volatile Map<Integer, String> layout = Map.of();
    private static volatile String toolOres = "iron";
    /** Ticks of quiet before the first idle swap (HotbarKeeper's). */
    public static final int IDLE_TICKS = HotbarKeeper.IDLE_TICKS;

    public static void set(Map<Integer, String> l, String ores) {
        layout = java.util.Collections.unmodifiableMap(new TreeMap<>(l));
        toolOres = HotbarRules.toolOres(ores);
    }

    public static Map<Integer, String> layout() { return layout; }

    public static String toolOres() { return toolOres; }

    /** The 36 bag slots as the rules see them (food scored as for eating at full health). */
    public static List<HotbarRules.Item> items(LocalPlayer p) {
        List<HotbarRules.Item> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            out.add(new HotbarRules.Item(i, Gui.itemId(s), s.getCount(), Reflexes.foodScore(s, 20f)));
        }
        return out;
    }

    /** Inventory index (0-8 hotbar, 9-35 bag) -> its slot in the player's inventory menu. */
    static int menuSlot(int index) { return index < 9 ? 36 + index : index; }

    /**
     * Puts bag slot {@code index} in the hand, into its laid-out hotbar slot when the layout has one (HotbarRules.
     * handSlot). False when a swap is needed but a container menu is open.
     */
    public static boolean toHand(Minecraft mc, LocalPlayer p, int index) {
        var inv = p.getInventory();
        int to = HotbarRules.handSlot(layout, items(p), index, inv.selected);
        if (to == index) {
            inv.selected = index;
            return true;
        }
        if (p.containerMenu != p.inventoryMenu) return false;
        mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, menuSlot(index), to, ClickType.SWAP, p);
        inv.selected = to;
        return true;
    }

    /**
     * Every tick from the commands (round 2: {@link HotbarKeeper} decides when: idle, at a job's start, or a refill of a
     * slot that emptied mid-job). busy: a job other than a walk or a wait, or a reflex; reflex: a reflex holds the bot;
     * job: the running job's identity (null = none, or a walk or a wait). Returns what it moved (for the log), or null.
     */
    public static String tick(Minecraft mc, LocalPlayer p, long tick, boolean busy, boolean reflex, Object job) {
        Map<Integer, String> l = layout;
        if (l.isEmpty()) {
            KEEPER.decide(l, new HotbarKeeper.Look(tick, busy, reflex, job, false, false, false, false, false, 0, List.of()));
            return null;
        }
        var gm = mc.gameMode;
        if (gm == null) return null;
        boolean menu = mc.screen != null || p.containerMenu != p.inventoryMenu, using = p.isUsingItem(), carried = !p.inventoryMenu.getCarried().isEmpty();
        // blocked: nothing can move, so the bag isn't scanned (the keeper compares the next scan with the last one)
        List<HotbarRules.Item> inv = menu || using || carried ? null : items(p);
        HotbarKeeper.Look look = new HotbarKeeper.Look(tick, busy, reflex, job, menu, using, carried,
                p.swinging, gm.isDestroying(), p.getInventory().selected, inv);
        HotbarKeeper.Move m = KEEPER.decide(l, look);
        if (m == null) return null;
        HotbarRules.Swap s = m.swap();
        String id = "";
        for (HotbarRules.Item it : inv) if (it.slot() == s.from()) id = it.id();
        gm.handleInventoryMouseClick(p.inventoryMenu.containerId, menuSlot(s.from()), s.to(), ClickType.SWAP, p);
        p.getInventory().selected = m.select();
        return "moved " + id.replaceFirst("^minecraft:", "") + " from slot " + (s.from() < 9 ? "hotbar " + (s.from() + 1) : s.from())
                + " to hotbar slot " + (s.to() + 1) + " (" + m.why() + ")";
    }

    private static final HotbarKeeper KEEPER = new HotbarKeeper();
}
