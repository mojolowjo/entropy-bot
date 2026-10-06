package io.github.mojolowjo.entropycompanion;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * The hotkeys (Options -> Controls -> "Entropy Companion", rebindable there). Point = V and menu = G; the rest are
 * unbound until the owner binds them. Read with consumeClick() in the client tick.
 */
final class Keys {
    static final String CATEGORY = "key.categories.entropycompanion";
    static final long POINT_DEBOUNCE_MS = 300;

    static final KeyMapping POINT = key("point", GLFW.GLFW_KEY_V);
    static final KeyMapping MENU = key("menu", GLFW.GLFW_KEY_G);
    static final KeyMapping COME = key("come", -1);
    static final KeyMapping FOLLOW = key("follow", -1);
    static final KeyMapping STOP = key("stop", -1);
    static final KeyMapping HOLD = key("hold", -1);
    static final KeyMapping CORNER1 = key("corner1", -1);
    static final KeyMapping CORNER2 = key("corner2", -1);
    static final KeyMapping BOXES = key("boxes", -1);
    static final KeyMapping[] ALL = {POINT, MENU, COME, FOLLOW, STOP, HOLD, CORNER1, CORNER2, BOXES};

    private static long lastPoint;

    private Keys() {}

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.entropycompanion." + name, InputConstants.Type.KEYSYM, code < 0 ? InputConstants.UNKNOWN.getValue() : code, CATEGORY);
    }

    static void register(RegisterKeyMappingsEvent e) {
        for (KeyMapping k : ALL) e.register(k);
    }

    /** Client tick: run what was pressed. */
    static void tick(Minecraft mc, Companion c) {
        if (mc.player == null) return;
        while (POINT.consumeClick()) {
            long now = System.currentTimeMillis();
            if (now - lastPoint < POINT_DEBOUNCE_MS) continue;
            lastPoint = now;
            c.point(false);
        }
        while (MENU.consumeClick()) if (mc.screen == null) mc.setScreen(new QuickMenu(c));
        while (COME.consumeClick()) c.send("come");
        while (FOLLOW.consumeClick()) {
            c.following = !c.following;
            c.send(c.following ? "follow" : "stop");
        }
        while (STOP.consumeClick()) {
            c.following = false;
            c.send("stop");
        }
        while (HOLD.consumeClick()) c.send("hold this");
        while (CORNER1.consumeClick()) c.corner(1);
        while (CORNER2.consumeClick()) c.corner(2);
        while (BOXES.consumeClick()) c.setBoxView(!c.boxView);
    }

    /** "point=V menu=G" for the status line. */
    static String summary() {
        return "point=" + POINT.getTranslatedKeyMessage().getString() + " menu=" + MENU.getTranslatedKeyMessage().getString();
    }
}
