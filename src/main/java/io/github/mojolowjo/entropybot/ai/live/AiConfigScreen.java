package io.github.mojolowjo.entropybot.ai.live;

import io.github.mojolowjo.entropybot.ai.AiScreenText;
import io.github.mojolowjo.entropybot.ai.AiSettings;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 0.35.0 (two pages since 0.35.2): the AI operator's settings, Mods > Entropy Bot > Config > AI operator...
 * Page 1 (connection): on/off (off by default), provider, model, base URL, the API key (masked while typing unless
 * Show; the box starts empty, a typed key replaces the stored one), Test connection, status, Open log folder.
 * Page 2 (limits): paid models + USD/day, tokens/day, requests/hour, reasoning, max output, one wrapped explanation.
 * Every text is wrapped to the column ({@code Font.split}) and cut to the lines that fit above the buttons; the
 * budget is checked in JUnit ({@link AiScreenText}) for 427x240 and 480x270 scaled pixels.
 *
 * <p>Loader API: none (a vanilla {@code Screen} with {@code Button}, {@code EditBox}); opened from
 * {@link io.github.mojolowjo.entropybot.blind.BotConfigScreen}, which the NeoForge {@code IConfigScreenFactory} opens.
 */
public final class AiConfigScreen extends Screen {
    private final Screen parent;
    private AiSettings s;
    private int page = 1;
    // the fields' texts survive a page switch (the widgets are rebuilt)
    private String vModel, vUrl, vKey = "", vUsd, vTokens, vRequests, vMaxOut, vThinks;
    private EditBox model, baseUrl, key, usdDay, tokensDay, requestsHour, maxOut, thinks;
    private boolean showKey;
    private String error, note;

    public AiConfigScreen(Screen parent) {
        super(Component.literal("AI operator"));
        this.parent = parent;
    }

    private int col() { return AiScreenText.column(width); }

    private int x0() { return width / 2 - col() / 2; }

    @Override
    protected void init() {
        if (s == null) {
            s = AiRuntime.INSTANCE.settingsCopy();
            vModel = s.model;
            vUrl = s.baseUrl;
            vUsd = String.format(Locale.ROOT, "%.2f", s.usdPerDay);
            vTokens = String.valueOf(s.tokensPerDay);
            vRequests = String.valueOf(s.requestsPerHour);
            vMaxOut = String.valueOf(s.maxOutputTokens);
            vThinks = String.valueOf(s.maxThinksPerGoal);
        }
        model = baseUrl = key = usdDay = tokensDay = requestsHour = maxOut = thinks = null;
        int x0 = x0(), c = col(), half = (c - 4) / 2, y = 28, by = height - AiScreenText.BUTTONS_FROM_BOTTOM;
        if (page == 1) {
            addRenderableWidget(Button.builder(onLabel(), b -> { s.enabled = !s.enabled; b.setMessage(onLabel()); }).bounds(x0, y, half, 20).build());
            addRenderableWidget(Button.builder(providerLabel(), b -> {
                int i = AiSettings.PROVIDER_ORDER.indexOf(s.provider);
                s.provider = AiSettings.PROVIDER_ORDER.get((i + 1) % AiSettings.PROVIDER_ORDER.size());
                b.setMessage(providerLabel());
            }).bounds(x0 + half + 4, y, half, 20).build());
            y += 24;
            model = box(x0 + 70, y, c - 70, 200, vModel, "nvidia/...:free");
            y += 22;
            baseUrl = box(x0 + 70, y, c - 70, 300, vUrl, "OpenAI-compatible / local only");
            y += 22;
            key = box(x0 + 70, y, c - 70 - 52, 400, vKey, "type to replace");
            key.setFormatter((text, pos) -> FormattedCharSequence.forward(showKey ? text : "*".repeat(text.length()), Style.EMPTY));
            addRenderableWidget(Button.builder(showLabel(), b -> { showKey = !showKey; b.setMessage(showLabel()); }).bounds(x0 + c - 48, y, 48, 20).build());
            int third = (c - 8) / 3;
            addRenderableWidget(Button.builder(Component.literal("Test the key"), b -> {
                AiSettings t = read();
                if (t != null) AiRuntime.INSTANCE.testConnection(t, key.getValue());
            }).bounds(x0, by, third, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Log folder"), b -> openLogs()).bounds(x0 + third + 4, by, third, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(x0 + 2 * (third + 4), by, third, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Next: limits >"), b -> switchTo(2)).bounds(x0, by + 24, half, 20).build());
        } else {
            addRenderableWidget(Button.builder(paidLabel(), b -> { s.paid = !s.paid; b.setMessage(paidLabel()); }).bounds(x0, y, half, 20).build());
            usdDay = box(x0 + c - 60, y, 60, 10, vUsd, "0.50");
            y += 24;
            tokensDay = box(x0 + 64, y, half - 66, 10, vTokens, "200000");
            requestsHour = box(x0 + c - 50, y, 50, 6, vRequests, "120");
            y += 24;
            addRenderableWidget(Button.builder(reasoningLabel(), b -> {
                int i = AiSettings.REASONING.indexOf(s.reasoning);
                s.reasoning = AiSettings.REASONING.get((i + 1) % AiSettings.REASONING.size());
                b.setMessage(reasoningLabel());
            }).bounds(x0, y, half, 20).build());
            maxOut = box(x0 + c - 50, y, 50, 4, vMaxOut, "400");
            y += 24;
            thinks = box(x0 + 70, y, 50, 4, vThinks, "25");     // 0.35.3
            addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(x0, by, c, 20).build());
            addRenderableWidget(Button.builder(Component.literal("< Back"), b -> switchTo(1)).bounds(x0, by + 24, half, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Done (no save)"), b -> onClose()).bounds(x0 + half + 4, by + 24, half, 20).build());
    }

    private void keep() {
        if (model != null) { vModel = model.getValue(); vUrl = baseUrl.getValue(); vKey = key.getValue(); }
        if (usdDay != null) { vUsd = usdDay.getValue(); vTokens = tokensDay.getValue(); vRequests = requestsHour.getValue(); vMaxOut = maxOut.getValue(); vThinks = thinks.getValue(); }
    }

    private void switchTo(int p) {
        keep();
        page = p;
        rebuildWidgets();
    }

    private void openLogs() {
        try {
            if (AiRuntime.INSTANCE.folder() != null) {
                java.io.File f = AiRuntime.INSTANCE.folder().resolve("ai").toFile();
                f.mkdirs();
                Util.getPlatform().openFile(f);
            }
        } catch (RuntimeException e) {
            error = "couldn't open the folder: " + e.getMessage();
        }
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
            case "local" -> "Local";
            case "demo" -> "Demo (no network)";
            default -> s.provider;
        };
        return Component.literal(n);
    }

    private Component paidLabel() { return Component.literal("Paid models: " + (s.paid ? "ON" : "off")); }

    private Component reasoningLabel() { return Component.literal("Reasoning: " + s.reasoning); }

    private Component showLabel() { return Component.literal(showKey ? "Hide" : "Show"); }

    /** The fields into a settings copy; null (and the reason in red) when a value is wrong. */
    private AiSettings read() {
        keep();
        AiSettings t = s.copy();
        t.model = vModel.trim();
        t.baseUrl = vUrl.trim();
        try {
            t.usdPerDay = Double.parseDouble(vUsd.trim());
            t.tokensPerDay = Long.parseLong(vTokens.trim());
            t.requestsPerHour = Integer.parseInt(vRequests.trim());
            t.maxOutputTokens = Integer.parseInt(vMaxOut.trim());
            t.maxThinksPerGoal = Integer.parseInt(vThinks.trim());
        } catch (NumberFormatException e) {
            error = "a number is wrong (page 2): USD like 0.50, the others whole numbers, 0 = no cap";
            return null;
        }
        List<String> p = t.problems();
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
        String why = AiRuntime.INSTANCE.saveFromScreen(t, vKey);
        if (why != null) {
            error = why;
            return;
        }
        s = t;
        vKey = "";
        if (key != null) key.setValue("");
        note = "saved - the AI operator is " + (t.enabled ? "on: ai <request>" : "off");
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        int x0 = x0(), c = col(), y = 28;
        g.drawCenteredString(font, page == 1 ? AiScreenText.TITLE_1 : AiScreenText.TITLE_2, width / 2, 12, 0xFFFFFF);
        List<Object[]> text = new ArrayList<>();          // {String, color}
        int top;
        if (page == 1) {
            y += 24;
            g.drawString(font, "Model id", x0, y + 5, 0xCCCCCC);
            y += 22;
            g.drawString(font, "Base URL", x0, y + 5, 0xCCCCCC);
            y += 22;
            g.drawString(font, "API key", x0, y + 5, 0xCCCCCC);
            top = AiScreenText.TEXT_TOP_P1;
            text.add(new Object[]{"key: " + AiRuntime.INSTANCE.keySource(s.provider), 0x999999});
            text.add(new Object[]{"status: " + AiRuntime.INSTANCE.shortStatus(), 0xCCCCCC});
            String t = AiRuntime.INSTANCE.testResult();
            if (t != null) text.add(new Object[]{"test: " + t, t.startsWith("ok") ? 0x55FF55 : t.startsWith("testing") ? 0xCCCCCC : 0xFF5555});
            text.add(new Object[]{AiScreenText.HELP_1, 0xAAAAAA});
        } else {
            g.drawString(font, "USD/day", x0 + c - 60 - 46, y + 5, s.paid ? 0xCCCCCC : 0x777777);
            y += 24;
            g.drawString(font, "Tokens/day", x0, y + 5, 0xCCCCCC);
            g.drawString(font, "Req/hour", x0 + c - 50 - 48, y + 5, 0xCCCCCC);
            y += 24;
            g.drawString(font, "Max output", x0 + c - 50 - 64, y + 5, 0xCCCCCC);
            y += 24;
            g.drawString(font, "Thinks/goal", x0, y + 5, 0xCCCCCC);
            top = AiScreenText.TEXT_TOP_P2;
            text.add(new Object[]{AiScreenText.HELP_2, 0xAAAAAA});
        }
        if (error != null) text.add(0, new Object[]{error, 0xFF5555});
        else if (note != null) text.add(0, new Object[]{note, 0x55FF55});
        int room = AiScreenText.linesFit(top, height), ly = top;
        for (Object[] t : text) {
            for (FormattedCharSequence line : font.split(Component.literal((String) t[0]), c)) {
                if (room-- <= 0) return;                    // never over the buttons
                g.drawString(font, line, x0, ly, (Integer) t[1]);
                ly += AiScreenText.LINE_PX;
            }
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }
}
