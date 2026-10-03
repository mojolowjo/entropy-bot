package io.github.mojolowjo.entropybot.gui;

import java.util.regex.Pattern;

/**
 * The probe rules for a container slot's role (package F, machine GUIs): pure, so JUnit checks them. {@link McMenu}
 * feeds them the menu's and the slot's class names and what the slot accepts; the bridge's guiRoles has the same rules.
 * <ul>
 *   <li>"other": upgrade, filter, ghost and settings slots (by class name), Refined Storage's ResourceSlot (its filter
 *   and config slots: a click on one sets a filter, it never moves an item), and a slot that holds something the
 *   player can't take. Never read from, never written to.</li>
 *   <li>"plain": takes dirt (storage when 9 or more, else a machine's input).</li>
 *   <li>"special": a Refined Storage menu's slot that takes only certain items (the disk drive's disk slots, an
 *   importer's or exporter's upgrade slots when their class says nothing). Only a named item goes in or out
 *   ("put storage_disk_1k", "take storage_disk_1k"); "put all" and "take all" never touch it, so a disk is never
 *   pulled out of the network by accident.</li>
 *   <li>"fuel": takes coal, not dirt. "output": takes neither.</li>
 * </ul>
 */
public final class SlotRules {
    private SlotRules() {}

    /** Slot class names that are never real slots (the bridge's GUI_SKIP_RE). */
    public static final Pattern SKIP = Pattern.compile("upgrade|filter|ghost|fake|phantom|settings|resourceslot", Pattern.CASE_INSENSITIVE);
    /** Menus whose item slots only take certain items: Refined Storage's (com.refinedmods...). */
    public static final Pattern SPECIAL_MENU = Pattern.compile("refinedmods", Pattern.CASE_INSENSITIVE);

    /** A slot class that is never read from or written to. */
    public static boolean skip(String slotClass) { return slotClass != null && SKIP.matcher(slotClass).find(); }

    /**
     * The probe's answer for one container slot: "other", "plain", "special", "fuel" or "output".
     *
     * @param menuClass the menu's full class name
     * @param slotClass the slot's full class name
     * @param holds     the slot holds something
     * @param pickup    the player may take it (asked only when it holds something)
     * @param dirt      the slot would take dirt
     * @param coal      the slot would take coal
     */
    public static String classify(String menuClass, String slotClass, boolean holds, boolean pickup, boolean dirt, boolean coal) {
        if (skip(slotClass) || (holds && !pickup)) return "other";
        if (dirt) return "plain";
        if (menuClass != null && SPECIAL_MENU.matcher(menuClass).find()) return "special";
        return coal ? "fuel" : "output";
    }
}
