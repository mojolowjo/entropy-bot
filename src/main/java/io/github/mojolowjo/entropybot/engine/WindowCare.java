package io.github.mojolowjo.entropybot.engine;

import java.util.EnumSet;
import java.util.Set;

/**
 * B7e (E1): the bot's game window, as the bridge kept it (its ClientEvents.tick):
 * <ul>
 *   <li>{@code pauseOnLostFocus} off (the bot's window is usually in the background), checked every 100 ticks;</li>
 *   <li>"mouse free" (the default): a grabbed mouse is released while the window has focus (Minecraft grabs the
 *       cursor whenever a menu closes; the owner uses other windows on this laptop, the bot aims by code);
 *       "mouse grab" gives normal Minecraft behaviour back for someone playing in the bot's window;</li>
 *   <li>every 20 ticks: the pause menu is closed, only while the window is in the background (someone who pressed
 *       Esc in it is using it); a leftover inventory screen is closed when no job and no reflex runs (it blocks
 *       eating and crouching). No other screen is ever touched.</li>
 * </ul>
 * {@link #decide} is pure (JUnit); {@link #tick} reads and acts on the game.
 */
public final class WindowCare {
    public static final WindowCare INSTANCE = new WindowCare();

    public enum Screen { NONE, PAUSE, INVENTORY, OTHER }

    public enum Action { PAUSE_ON_LOST_FOCUS_OFF, RELEASE_MOUSE, CLOSE_PAUSE, CLOSE_INVENTORY }

    private volatile boolean freeMouse = true;
    private int errors;

    private WindowCare() {}

    public boolean freeMouse() { return freeMouse; }

    /** What to do this tick. Pure. */
    public static Set<Action> decide(long tick, boolean pauseOnLostFocus, boolean freeMouse, boolean windowActive, boolean mouseGrabbed,
                                     Screen screen, boolean jobRunning, boolean reflexActive) {
        Set<Action> out = EnumSet.noneOf(Action.class);
        if (tick % 100 == 0 && pauseOnLostFocus) out.add(Action.PAUSE_ON_LOST_FOCUS_OFF);
        if (freeMouse && windowActive && mouseGrabbed) out.add(Action.RELEASE_MOUSE);
        if (tick % 20 == 0) {
            if (screen == Screen.PAUSE && !windowActive) out.add(Action.CLOSE_PAUSE);
            else if (screen == Screen.INVENTORY && !jobRunning && !reflexActive) out.add(Action.CLOSE_INVENTORY);
        }
        return out;
    }

    /** "mouse free|grab" (PM, owner; cmd type "mouse"): the bridge's wording. */
    public String mouseCommand(String text) {
        String t = text == null ? "" : text.toLowerCase();
        if (t.contains("grab")) freeMouse = false;
        else if (t.contains("free") || t.contains("release")) freeMouse = true;
        return "ok: mouse is " + (freeMouse ? "free (the game window never keeps the cursor)" : "grabbed normally when the game window has focus");
    }

    /** Once a client tick (Core's tick, in a world). Never throws. */
    public void tick(long tick, boolean jobRunning, boolean reflexActive) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            Screen screen = mc.screen == null ? Screen.NONE
                    : mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen ? Screen.PAUSE
                    : mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen ? Screen.INVENTORY : Screen.OTHER;
            Set<Action> todo = decide(tick, mc.options.pauseOnLostFocus, freeMouse, mc.isWindowActive(), mc.mouseHandler.isMouseGrabbed(),
                    screen, jobRunning, reflexActive);
            if (todo.isEmpty()) return;
            if (todo.contains(Action.PAUSE_ON_LOST_FOCUS_OFF)) mc.options.pauseOnLostFocus = false;
            if (todo.contains(Action.RELEASE_MOUSE)) mc.mouseHandler.releaseMouse();
            if (todo.contains(Action.CLOSE_PAUSE)) mc.setScreen(null);
            if (todo.contains(Action.CLOSE_INVENTORY) && mc.player != null) mc.player.closeContainer();
        } catch (Throwable t) {
            if (errors++ < 5) com.mojang.logging.LogUtils.getLogger().warn("[entropybot] window care: {}", t.toString());
        }
    }
}
