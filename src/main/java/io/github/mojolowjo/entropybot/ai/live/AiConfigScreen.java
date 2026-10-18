package io.github.mojolowjo.entropybot.ai.live;

import io.github.mojolowjo.entropybot.ai.AiSettings;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.Locale;

/**
 * 0.35.0: the AI operator's settings (Mods > Entropy Bot > Config > AI operator...). On/off (off by default), provider,
 * model, base URL, the API key (masked while typing unless "Show" is on; the box starts empty, a typed key replaces
 * the stored one), paid models on/off with the USD per day, the token and request caps with a line explaining them,
 * Test connection (off the game thread), the status line and Open log folder.
 *
 * <p>Loader API: none (a vanilla {@code Screen} with {@code Button}, {@code EditBox}); opened from
 * {@link io.github.mojolowjo.entropybot.blind.BotConfigScreen}, which the NeoForge {@code IConfigScreenFactory} opens.
 */
public final class AiConfigScreen extends Screen {
    private final Screen parent;
    private AiSettings s;
    private EditBox model, baseUrl, key, usdDay, tokensDay, requestsHour;
    private Button onButton, providerButton, paidButton, showButton;
    private boolean showKey;
    private String error, note;

    public AiConfigScreen(Screen parent) {
        super(Component.literal("AI operator"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (s == null) s = AiRuntime.INSTANCE.settingsCopy();
        int cx = width / 2, x0 = cx - 150, y = 28;
        onButton = addRenderableWidget(Button.builder(onLabel(), b -> {
            s.enabled = !s.enabled;
            b.setMessage(onLabel());
        }).bounds(x0, y, 148, 20).build());
        providerButton = addRenderableWidget(Button.builder(providerLabel(), b -> {
            int i = AiSettings.PROVIDER_ORDER.indexOf(s.provider);
            s.provider = AiSettings.PROVIDER_ORDER.get((i + 1) % AiSettings.PROVIDER_ORDER.size());
            b.setMessage(providerLabel());
        }).bounds(cx + 2, y, 148, 20).build());
        y += 24;
        model = box(x0 + 70, y, 230, 200, s.model, "nvidia/nemotron-3-super-120b-a12b:free");
        y += 22;
        baseUrl = box(x0 + 70, y, 230, 300, s.baseUrl, "only for OpenAI-compatible / local");
        y += 22;
        key = box(x0 + 70, y, 176, 400, "", "a key is stored? type a new one to replace it");
        key.setFormatter((text, pos) -> FormattedCharSequence.forward(showKey ? text : "*".repeat(text.length()), Style.EMPTY));
        showButton = addRenderableWidget(Button.builder(showLabel(), b -> {
            showKey = !showKey;
            b.setMessage(showLabel());
        }).bounds(x0 + 250, y, 50, 20).build());
        y += 24;
        paidButton = addRenderableWidget(Button.builder(paidLabel(), b -> {
            s.paid = !s.paid;
            b.setMessage(paidLabel());
        }).bounds(x0, y, 148, 20).build());
        usdDay = box(x0 + 230, y, 70, 10, String.format(Locale.ROOT, "%.2f", s.usdPerDay), "0.50");
        y += 24;
        tokensDay = box(x0 + 90, y, 60, 10, String.valueOf(s.tokensPerDay), "200000");
        requestsHour = box(x0 + 250, y, 50, 6, String.valueOf(s.requestsPerHour), "120");
        int by = height - 52;
        addRenderableWidget(Button.builder(Component.literal("Test connection"), b -> {
            AiSettings t = read();
            if (t != null) AiRuntime.INSTANCE.testConnection(t, key.getValue());
        }).bounds(x0, by, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Open log folder"), b -> {
            try {
                if (AiRuntime.INSTANCE.folder() != null) {
                    java.io.File f = AiRuntime.INSTANCE.folder().resolve("ai").toFile();
                    f.mkdirs();
                    Util.getPlatform().openFile(f);
                }
            } catch (RuntimeException e) {
                error = "couldn't open the folder: " + e.getMessage();
            }
        }).bounds(x0 + 101, by, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(x0 + 202, by, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done (no save)"), b -> onClose()).bounds(x0, by + 24, 300, 20).build());
    }

    private EditBox box(int x, int y, int w, int max, String value, String hint) {
        EditBox b = new EditBox(font, x, y, w, 18, Component.literal(hint));
        b.setMaxLength(max);
        b.setValue(value == null ? "" : value);
        b.setHint(Component.literal(hint));
        return addRenderableWidget(b);
    }

    private Component onLabel() { return Component.literal("AI operator: " + (s.enabled ? "ON" : "off")); }

    private Component providerLabel() {
        String n = switch (s.provider) {
            case "openrouter" -> "OpenRouter";
            case "openai" -> "OpenAI-compatible";
            case "local" -> "Local (Ollama...)";
            case "demo" -> "Demo (no network)";
            default -> s.provider;
        };
        return Component.literal("Provider: " + n);
    }

    private Component paidLabel() { return Component.literal("Paid models: " + (s.paid ? "ON" : "off (free only)")); }

    private Component showLabel() { return Component.literal(showKey ? "Hide" : "Show"); }

    /** The fields into a settings copy; null (and the reason in red) when a number is wrong. */
    private AiSettings read() {
        AiSettings t = s.copy();
        t.model = model.getValue().trim();
        t.baseUrl = baseUrl.getValue().trim();
        try {
            t.usdPerDay = Double.parseDouble(usdDay.getValue().trim());
            t.tokensPerDay = Long.parseLong(tokensDay.getValue().trim());
            t.requestsPerHour = Integer.parseInt(requestsHour.getValue().trim());
        } catch (NumberFormatException e) {
            error = "a number is wrong: USD per day like 0.50, tokens per day and requests per hour whole numbers (0 = no cap)";
            return null;
        }
        java.util.List<String> p = t.problems();
        if (!p.isEmpty()) {
            error = String.join("; ", p);
            return null;
        }
        error = null;
        return t;
    }

    private void save() {
        AiSettings t = read();
        if (t == null) return;
        String why = AiRuntime.INSTANCE.saveFromScreen(t, key.getValue());
        if (why != null) {
            error = why;
            return;
        }
        s = t;
        key.setValue("");
        note = "saved" + (t.enabled ? " - the AI operator is on: tell the bot what to do (ai <request>)" : " - the AI operator is off");
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        int cx = width / 2, x0 = cx - 150, y = 28;
        g.drawCenteredString(font, title, cx, 12, 0xFFFFFF);
        y += 24;
        g.drawString(font, "Model id", x0, y + 5, 0xCCCCCC);
        y += 22;
        g.drawString(font, "Base URL", x0, y + 5, 0xCCCCCC);
        y += 22;
        g.drawString(font, "API key", x0, y + 5, 0xCCCCCC);
        g.drawString(font, "key: " + AiRuntime.INSTANCE.keySource(s.provider), x0 + 305, y + 5, 0x999999);
        y += 24;
        g.drawString(font, "USD/day", x0 + 180, y + 5, s.paid ? 0xCCCCCC : 0x777777);
        y += 24;
        g.drawString(font, "Tokens/day", x0, y + 5, 0xCCCCCC);
        g.drawString(font, "Requests/hour", x0 + 170, y + 5, 0xCCCCCC);
        y += 24;
        int w = 300;
        for (FormattedCharSequence line : font.split(Component.literal(
                "Tokens are the bits of text the model reads and writes (about 4 letters each). The token cap stops the AI for the day once it has used "
                        + "that many; free models cost nothing, the cap keeps it inside their limits. With paid models on, it also stops at the USD per day."), w)) {
            g.drawString(font, line, x0, y, 0xAAAAAA);
            y += 10;
        }
        y += 4;
        g.drawString(font, cut("status: " + AiRuntime.INSTANCE.shortStatus(), 60), x0, y, 0xCCCCCC);
        y += 11;
        String t = AiRuntime.INSTANCE.testResult();
        if (t != null) {
            g.drawString(font, cut("test: " + t, 60), x0, y, t.startsWith("ok") ? 0x55FF55 : t.startsWith("testing") ? 0xCCCCCC : 0xFF5555);
            y += 11;
        }
        if (error != null) {
            for (FormattedCharSequence line : font.split(Component.literal(error), w)) {
                g.drawString(font, line, x0, y, 0xFF5555);
                y += 10;
            }
        } else if (note != null) g.drawString(font, cut(note, 60), x0, y, 0x55FF55);
    }

    private static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 3) + "..."; }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }
}
