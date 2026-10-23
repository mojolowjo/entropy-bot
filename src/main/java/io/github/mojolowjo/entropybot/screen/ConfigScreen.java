package io.github.mojolowjo.entropybot.screen;

import io.github.mojolowjo.entropybot.Core;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 0.39.z24 the mod's config screen (a full rewrite; the 0.26.0 screen is gone): Dashboard (start / stop / status /
 * copy link), Allowed players, Command box with the last replies, I drive, and an about line.
 *
 * <p>Loader API: NeoForge 21.1 opens it through {@code IConfigScreenFactory} registered with
 * {@code ModContainer.registerExtensionPoint} in {@code EntropyBot} (the Mods list's Config button); that is the only
 * loader-specific line. Fabric: ModMenu's {@code ModMenuApi.getModConfigScreenFactory} would return the same screen.
 * The screen is vanilla: {@code Screen}, {@code Button.builder}, {@code EditBox}, {@code KeyboardHandler.setClipboard}.
 * Every failure is shown on the screen (never silent).
 */
public final class ConfigScreen extends Screen {
    private final Screen parent;
    private EditBox allowBox, cmdBox;
    private Button driveButton;
    private String dashStatus = "dashboard: ...", dashNote, allowNote, cmdNote, driveNote;

    public ConfigScreen(Screen parent) {
        super(Component.literal("Entropy Bot"));
        this.parent = parent;
    }

    private static Path botDir() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gameDirectory == null ? null : mc.gameDirectory.toPath().resolve(Core.MODID);
    }

    @Override
    protected void init() {
        int x = width / 2 - 155, y = 34;
        // dashboard
        addRenderableWidget(Button.builder(Component.literal("Start dashboard"), b -> { dashNote = DashboardProcess.start(botDir(), Minecraft.getInstance().gameDirectory.toPath()); refreshDash(); })
                .bounds(x, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Stop dashboard"), b -> { dashNote = DashboardProcess.stop(botDir()); refreshDash(); })
                .bounds(x + 105, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Copy dashboard link"), b -> copyLink()).bounds(x + 210, y, 100, 20).build());
        // allowed players
        y += 56;
        allowBox = new EditBox(font, x, y, 255, 20, Component.literal("Allowed players"));
        allowBox.setMaxLength(400);
        allowBox.setValue(String.join(", ", safeAllowed()));
        addRenderableWidget(allowBox);
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> saveAllowed()).bounds(x + 260, y, 50, 20).build());
        // command box
        y += 46;
        cmdBox = new EditBox(font, x, y, 255, 20, Component.literal("Command"));
        cmdBox.setMaxLength(256);
        addRenderableWidget(cmdBox);
        addRenderableWidget(Button.builder(Component.literal("Send"), b -> send()).bounds(x + 260, y, 50, 20).build());
        // I drive
        driveButton = addRenderableWidget(Button.builder(driveLabel(), b -> {
            try {
                driveNote = DriveRuntime.INSTANCE.toggle();
            } catch (Throwable t) {
                driveNote = "I drive failed: " + t;
            }
            b.setMessage(driveLabel());
        }).bounds(x, height - 56, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x + 160, height - 56, 150, 20).build());
        refreshDash();
    }

    private static Component driveLabel() {
        return Component.literal("I drive: " + (DriveRuntime.INSTANCE.on() ? "ON" : "off"));
    }

    private void refreshDash() {
        try {
            dashStatus = DashboardProcess.status(botDir());
        } catch (Throwable t) {
            dashStatus = "dashboard: status failed: " + t;
        }
    }

    private void copyLink() {
        String link = DashboardProcess.linkWithKey(botDir());
        if (link.startsWith("error: ")) { dashNote = link; return; }
        minecraft.keyboardHandler.setClipboard(link);
        dashNote = "link with the key copied to the clipboard";
    }

    private List<String> safeAllowed() {
        try {
            return Core.INSTANCE.commands.allowedList();
        } catch (Throwable t) {
            allowNote = "allowed list unreadable: " + t;
            return List.of();
        }
    }

    private void saveAllowed() {
        List<String> bad = new ArrayList<>();
        List<String> names = AllowRule.parse(allowBox.getValue(), bad);
        try {
            allowNote = Core.INSTANCE.commands.setAllowed(names) + (bad.isEmpty() ? "" : " - not names, left out: " + String.join(" ", bad));
        } catch (Throwable t) {
            allowNote = "not saved: " + t;
        }
        allowBox.setValue(String.join(", ", safeAllowed()));
    }

    private void send() {
        try {
            cmdNote = CommandBox.submit(cmdBox.getValue(), Core.INSTANCE.commands::localLine);
            if (cmdNote == null) cmdBox.setValue("");
        } catch (Throwable t) {
            cmdNote = "not sent: " + t;
        }
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == 257 || key == 335) && cmdBox != null && cmdBox.isFocused()) { send(); return true; }     // Enter / keypad Enter
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void tick() {
        if (driveButton != null) driveButton.setMessage(driveLabel());
        if (minecraft != null && minecraft.level != null && minecraft.level.getGameTime() % 40 == 0) refreshDash();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        int x = width / 2 - 155;
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        g.drawCenteredString(font, "mod " + io.github.mojolowjo.entropybot.api.BotAPI.version(), width / 2, 19, 0x888888);
        g.drawString(font, "Dashboard (off unless started here or by the bridge)", x, 24, 0xAAAAAA);
        g.drawString(font, cut(dashStatus), x, 57, dashStatus.contains("running") ? 0x55FF55 : 0xCCCCCC);
        if (dashNote != null) g.drawString(font, cut(dashNote), x, 67, dashNote.startsWith("error") ? 0xFF5555 : 0xCCCCCC);
        g.drawString(font, "Allowed players (may PM me commands; empty = only this keyboard)", x, 80, 0xAAAAAA);
        if (allowNote != null) g.drawString(font, cut(allowNote), x, 113, 0xCCCCCC);
        g.drawString(font, "Command (as /bot <command>)", x, 126, 0xAAAAAA);
        int ly = 159;
        if (cmdNote != null) { g.drawString(font, cut(cmdNote), x, ly, 0xFF5555); ly += 10; }
        for (String l : CommandBox.REPLIES.lines()) { g.drawString(font, cut(l), x, ly, 0xDDDDDD); ly += 10; }
        int dy = height - 34;
        if (driveNote != null) { g.drawString(font, cut(driveNote), x, dy, 0xCCCCCC); dy += 10; }
        for (String p : DriveRuntime.INSTANCE.problems()) { g.drawString(font, cut("not restored: " + p), x, dy, 0xFF5555); dy += 10; }
    }

    private static String cut(String s) {
        return s.length() > 70 ? s.substring(0, 70) + "..." : s;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }
}
