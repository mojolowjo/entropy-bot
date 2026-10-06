package io.github.mojolowjo.entropycompanion;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** The reply overlay, top left (a GUI layer above all): up to 3 replies for a few seconds, newest on top. */
final class Overlay {
    private Overlay() {}

    static void render(GuiGraphics g, DeltaTracker dt) {
        Companion c = Companion.INSTANCE;
        if (c == null) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui || mc.player == null) return;
            List<String> lines = c.replies.visible(System.currentTimeMillis());
            int y = 4;
            for (String s : lines) {
                int w = mc.font.width(s);
                g.fill(2, y - 2, 6 + w, y + 10, 0x80000000);
                g.drawString(mc.font, s, 4, y, s.contains("unreachable") || s.contains("refused") ? 0xFFFF7070 : 0xFFE0E0E0);
                y += 12;
            }
        } catch (RuntimeException e) {
            c.error("overlay", e);
        }
    }
}
