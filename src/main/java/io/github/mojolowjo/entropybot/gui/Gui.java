package io.github.mojolowjo.entropybot.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.TreeMap;

/** The game-side helpers of the GUI toolkit: is a container open, close it, what it holds, what the bot carries. */
public final class Gui {
    private Gui() {}

    /** A container menu (not the bot's own inventory) is open. */
    public static boolean open(LocalPlayer p) { return p.containerMenu != p.inventoryMenu; }

    /** Closes the open screen or container (tells the server). */
    public static void close(LocalPlayer p) {
        Minecraft mc = Minecraft.getInstance();
        if (open(p)) p.closeContainer();
        else if (mc.screen != null) mc.setScreen(null);
    }

    /** "close": the bridge's closeScreen. */
    public static String closeVerb(LocalPlayer p) {
        if (open(p) || Minecraft.getInstance().screen != null) {
            close(p);
            return "ok: closed";
        }
        return "ok: nothing open";
    }

    public static String menuName(LocalPlayer p) { return p.containerMenu.getClass().getSimpleName(); }

    public static String screenName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.screen == null ? "" : mc.screen.getClass().getSimpleName();
    }

    /** Why take/put must leave the open menu alone, or null. */
    public static String wrongScreen(LocalPlayer p) { return GuiCore.wrongScreen(screenName() + " " + menuName(p)); }

    public static String itemId(ItemStack s) { return BuiltInRegistries.ITEM.getKey(s.getItem()).toString(); }

    /** What the open container holds by item, upgrade and filter slots left out; null when none is open. */
    public static Map<String, Integer> containerContents(LocalPlayer p) {
        if (!open(p)) return null;
        Map<String, Integer> out = new TreeMap<>();
        for (Slot s : p.containerMenu.slots) {
            if (s.container == p.getInventory() || McMenu.SKIP.matcher(s.getClass().getName()).find() || !s.hasItem()) continue;
            out.merge(itemId(s.getItem()), s.getItem().getCount(), Integer::sum);
        }
        return out;
    }

    /** {id: n} in the inventory and hotbar. */
    public static Map<String, Integer> inventory(LocalPlayer p) {
        Map<String, Integer> out = new TreeMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty()) out.merge(itemId(s), s.getCount(), Integer::sum);
        }
        return out;
    }

    public static int totalItems(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) n += p.getInventory().getItem(i).getCount();
        return n;
    }

    public static boolean isFood(ItemStack s) { return !s.isEmpty() && s.has(DataComponents.FOOD); }

    /** "ChestMenu / ContainerScreen | storage 0-26 x27 (Slot) | ...": for checking slot roles in game. */
    public static String describe(LocalPlayer p) {
        McMenu m = new McMenu(p);
        String screen = screenName();
        return menuName(p) + " / " + (screen.isEmpty() ? "no screen" : screen) + " | " + GuiCore.describe(GuiCore.roles(m), m::slotClass);
    }

    /** "wear": puts on every armor piece in the inventory whose slot is free. */
    public static String wearArmor(LocalPlayer p) {
        if (open(p)) return "error: close the open container first";
        var menu = p.inventoryMenu;
        java.util.List<String> worn = new java.util.ArrayList<>();
        java.util.Map<String, Integer> slotOf = java.util.Map.of("helmet", 5, "chestplate", 6, "leggings", 7, "boots", 8);
        // 0.23.6 armour care: a worn piece (10 % or less) comes off when the bag holds a good one for that slot
        java.util.List<String> off = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Integer> e : slotOf.entrySet()) {
            ItemStack on = menu.getSlot(e.getValue()).getItem();
            if (on.isEmpty() || !worn(on)) continue;
            boolean spare = false;
            for (int i = 9; i < 45 && i < menu.slots.size() && !spare; i++) {
                ItemStack st = menu.getSlot(i).getItem();
                spare = !st.isEmpty() && itemId(st).endsWith("_" + e.getKey()) && !worn(st);
            }
            if (!spare || p.getInventory().getFreeSlot() < 0) continue;
            Minecraft.getInstance().gameMode.handleInventoryMouseClick(menu.containerId, e.getValue(), 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE, p);
            off.add(GuiCore.shortId(itemId(on)));
        }
        for (int i = 9; i < 45 && i < menu.slots.size(); i++) {
            ItemStack st = menu.getSlot(i).getItem();
            if (st.isEmpty()) continue;
            String id = itemId(st);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("_(helmet|chestplate|leggings|boots)$").matcher(id);
            if (!m.find() || !menu.getSlot(slotOf.get(m.group(1))).getItem().isEmpty() || worn(st)) continue;
            Minecraft.getInstance().gameMode.handleInventoryMouseClick(menu.containerId, i, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE, p);
            worn.add(GuiCore.shortId(id));
        }
        String took = off.isEmpty() ? "" : " (took off the worn " + String.join(", ", off) + ")";
        return worn.isEmpty() ? "error: no armor to put on (or those slots are full)" + took : "ok: put on " + String.join(", ", worn) + took;
    }

    /** 0.23.6: a damageable item at 10 % of its uses or less. */
    static boolean worn(ItemStack st) {
        return st.isDamageableItem() && st.getMaxDamage() > 0 && st.getMaxDamage() - st.getDamageValue() <= st.getMaxDamage() * 0.10;
    }
}
