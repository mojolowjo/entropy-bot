package io.github.mojolowjo.entropycompanion;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The quick menu (G): the usual commands as buttons (number keys 1-9 run the first nine), the last 3 commands,
 * and C3's row: a name box with "area" / "protect" from the two corners and the box view switch. An entry whose
 * verb the bot answered "unknown command" this session is hidden (C2-C7 verbs not built yet).
 */
final class QuickMenu extends Screen {
    private final Companion c;
    private final List<String> lines = new ArrayList<>();
    private EditBox name;

    QuickMenu(Companion c) {
        super(Component.literal("Entropy Bot"));
        this.c = c;
    }

    @Override
    protected void init() {
        lines.clear();
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("come", "come");
        entries.put(c.following ? "stop following" : "follow me", c.following ? "stop" : "follow");
        entries.put("stop", "stop");
        entries.put("deposit", "deposit");
        entries.put("eat", "eat");
        entries.put("escort me", "escort");                  // 0.2.2 (V1b): escort until dismiss
        entries.put("dismiss", "dismiss");
        entries.put("assist me", "assist");                  // 0.3.1: help with what I'm doing
        entries.put("assist off", "assist off");
        entries.put("cut 16 logs", "cut 16 logs");
        entries.put("done (free time)", "done");
        entries.put("fetch food", "fetch food 8");
        entries.put("scan base", "scan base");
        entries.put("status", "status");
        ItemStack held = minecraft != null && minecraft.player != null ? minecraft.player.getMainHandItem() : ItemStack.EMPTY;
        if (!held.isEmpty()) {
            String id = BuiltInRegistries.ITEM.getKey(held.getItem()).getPath();
            entries.put("have " + id, "have " + id);
        }
        for (String r : c.recent()) entries.putIfAbsent("again: " + r, r);
        int w = 150, h = 20, gap = 4, cols = 2;
        List<Map.Entry<String, String>> shown = new ArrayList<>();
        for (Map.Entry<String, String> e : entries.entrySet()) if (c.known(e.getValue())) shown.add(e);
        int rows = (shown.size() + cols - 1) / cols;
        int x0 = width / 2 - w - gap / 2, y0 = Math.max(24, height / 2 - (rows + 2) * (h + gap) / 2);
        for (int i = 0; i < shown.size(); i++) {
            Map.Entry<String, String> e = shown.get(i);
            lines.add(e.getValue());
            String label = (i < 9 ? (i + 1) + ". " : "") + e.getKey();
            int x = x0 + (i % cols) * (w + gap), y = y0 + (i / cols) * (h + gap);
            String cmd = e.getValue();
            addRenderableWidget(Button.builder(Component.literal(label), b -> run(cmd)).bounds(x, y, w, h).build());
        }
        int y = y0 + rows * (h + gap) + 6;
        name = new EditBox(font, x0, y, 96, h, Component.literal("name"));
        name.setMaxLength(24);
        name.setHint(Component.literal("area name"));
        addRenderableWidget(name);
        addRenderableWidget(Button.builder(Component.literal("area"), b -> corners("area")).bounds(x0 + 100, y, 32, h).build());
        addRenderableWidget(Button.builder(Component.literal("destroy"), b -> corners("destroy")).bounds(x0 + 134, y, 32, h).build());
        addRenderableWidget(Button.builder(Component.literal("main"), b -> corners("main")).bounds(x0 + 168, y, 32, h).build());
        addRenderableWidget(Button.builder(Component.literal("safe"), b -> corners("safe")).bounds(x0 + 202, y, 32, h).build());
        addRenderableWidget(Button.builder(Component.literal(c.boxView ? "boxes: on" : "boxes: off"), b -> {
            c.setBoxView(!c.boxView);
            onClose();
        }).bounds(x0 + 236, y, w * 2 + gap - 236, h).build());
    }

    private void run(String cmd) {
        onClose();
        if (cmd.equals("follow")) c.following = true;
        if (cmd.equals("stop")) c.following = false;
        if (cmd.equals("status")) c.handle("status");
        else c.send(cmd);
    }

    private void corners(String kind) {
        String n = name.getValue().trim().toLowerCase(java.util.Locale.ROOT);
        onClose();
        c.corners(kind + " " + n);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (name != null && !name.isFocused() && key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
            int i = key - GLFW.GLFW_KEY_1;
            if (i < lines.size()) {
                run(lines.get(i));
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        g.drawCenteredString(font, "Entropy Bot - 1-9 or click", width / 2, 8, 0xFFFFFF);
        Corners.Pos a = c.corners.get(1), b = c.corners.get(2);
        g.drawCenteredString(font, "corners: " + (a == null ? "-" : a.text()) + " / " + (b == null ? "-" : b.text()), width / 2, height - 14, 0xAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
