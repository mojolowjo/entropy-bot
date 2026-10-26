package io.github.mojolowjo.entropybot.craft;

/**
 * 0.39.z77 furnace (run BN 11:39:27.738: "the container at 23 4 12 did not open (while smelting 8 iron_ingot)" two ticks
 * after the fork's click ended): the open step's wait for a container's menu after its click. The wait counts from the
 * click's end, never from the step's start - the smelt's reopen splices its open step at the same index while the furnace
 * works, so the step's clock was 300+ ticks old and the first look without a menu (the server's answer is a round trip
 * away) already read "did not open". A menu that does not come retries the click ONCE at the same container, the reason
 * logged; the second end fails with the facts of that moment ({@link #reason}).
 * Pure (JUnit: Z77FurnaceTest). Loader notes: none here; the caller reads the vanilla client's LocalPlayer.containerMenu
 * against inventoryMenu, Minecraft.screen / hitResult and Player.blockInteractionRange() (the same on Fabric).
 */
public final class OpenWait {
    private OpenWait() {}

    public enum Next { WAIT, RETRY, FAIL }

    /** The open step's wait for the menu (Seq.openStep's literal of before, 4 s; Tuning crafting.openWaitTicks), counted from the click's end. */
    public static long waitTicks() { return io.github.mojolowjo.entropybot.aa.brain.Tuning.i("crafting.openWaitTicks"); }

    /** No menu yet {@code sinceClick} client ticks after the click: wait, click once more, or fail (after the one retry). */
    public static Next noMenu(long sinceClick, boolean retried) {
        if (sinceClick <= waitTicks()) return Next.WAIT;
        return retried ? Next.FAIL : Next.RETRY;
    }

    /**
     * The interact's own facts at the end of a wait: "the container at 23 4 12 did not open 81 ticks after the click (no
     * menu came; screen: none; eyes 1.6 m from its centre, in reach 4.5; crosshair: on it; the block: furnace)".
     * {@code screen}: the open screen's class name ("" or null: none; a menu of another block would have ended the wait as
     * opened - the furnace step checks it is a furnace's); {@code crosshair}: what the crosshair is on (the caller's words);
     * {@code reach}: the player's block reach (NaN: not read).
     */
    public static String reason(String where, long sinceClick, String screen, double eyeDist, double reach, String crosshair, String block) {
        String scr = screen == null || screen.isEmpty() ? "none" : screen;
        String inReach = Double.isNaN(reach) ? "reach not read" : (eyeDist <= reach ? "in reach " : "OUT of reach ") + fmt(reach);
        return "the container at " + where + " did not open " + sinceClick + " ticks after the click (no menu came"
                + "; screen: " + scr + "; eyes " + fmt(eyeDist) + " m from its centre, " + inReach
                + "; crosshair: " + (crosshair == null ? "not read" : crosshair) + "; the block: " + (block == null ? "not read" : block) + ")";
    }

    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.1f", v); }
}
