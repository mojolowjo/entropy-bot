package io.github.mojolowjo.entropybot.blind;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.commands.SelfCheck;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Blind mode on the game side (0.26.0, docs/BLIND_PLAN.md section 2): the bot's client stops drawing frames while the
 * simulation (ticks, chunks, Baritone, screens, PMs) runs on.
 *
 * <p>Loader API: the only loader-specific part is how {@link #preTick()} and {@link #postTick()} are called: NeoForge
 * {@code ClientTickEvent.Pre} / {@code ClientTickEvent.Post} (registered in {@code EntropyBot}, Post at
 * {@code EventPriority.LOWEST} so the re-assert runs after every other listener). Everything else is vanilla:
 * {@code Minecraft.noRender} (public field; {@code setScreen} resets it, hence the re-assert after every tick),
 * {@code Minecraft.getFps()}, {@code Window.setTitle(String)}, {@code Minecraft.updateTitle()} (vanilla's title back),
 * {@code SoundManager.pause()/resume()}, {@code Options.framerateLimit()}. No mixin.
 *
 * <p>Nothing fails silently: a broken blind.json, a title or sound call that threw, and noRender found reset 3 ticks
 * in a row become {@code check} findings (and one log line each).
 */
public final class BlindRuntime {
    private static final Logger LOG = LogUtils.getLogger();
    public static final BlindRuntime INSTANCE = new BlindRuntime();

    private final BlindMode mode = new BlindMode();
    private Path dir;
    private boolean loaded, asserted, soundPaused, titleSet;
    private String titleError, soundError;
    private long frameCounter, secondStart, ticksThisSecond;
    private int tps;
    /** The job text for the title (set by Commands; null = idle). */
    private volatile Supplier<String> jobText = () -> null;
    /** Whispers to the owner (set by Commands). */
    private volatile Consumer<String> whisper = s -> {};

    private BlindRuntime() {}

    public BlindMode mode() { return mode; }

    public void hooks(Supplier<String> job, Consumer<String> ownerWhisper) {
        if (job != null) jobText = job;
        if (ownerWhisper != null) whisper = ownerWhisper;
    }

    /** Reads blind.json (client setup; again lazily at the first tick when setup had no folder). Never throws. */
    public synchronized void load(Path entropybotDir) {
        try {
            dir = entropybotDir;
            mode.load(dir, System.currentTimeMillis());
            loaded = true;
            if (mode.configProblem() != null) LOG.warn("[entropybot] blind: {}", mode.configProblem());
            else if (mode.enabled()) LOG.info("[entropybot] blind mode is on from blind.json: no frames are drawn once in a world");
        } catch (Throwable t) {
            LOG.warn("[entropybot] blind: couldn't read blind.json: {}", t.toString());
        }
    }

    private void ensureLoaded(Minecraft mc) {
        if (!loaded && mc.gameDirectory != null) load(mc.gameDirectory.toPath().resolve("entropybot"));
    }

    /** ClientTickEvent.Pre: what the frames since the last tick saw (the self-check). Never throws. */
    public void preTick() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!asserted || mc.level == null) return;
            if (mode.noRenderFound(mc.noRender))
                LOG.warn("[entropybot] blind: rendering was turned back on {} ticks in a row by something else", BlindMode.RESET_TICKS);
        } catch (Throwable t) {
            LOG.warn("[entropybot] blind pre-tick: {}", t.toString());
        }
    }

    /** ClientTickEvent.Post (lowest priority): the re-assert, the sound, once a second the frames and the title. Never throws. */
    public void postTick() {
        Minecraft mc = Minecraft.getInstance();
        try {
            ensureLoaded(mc);
            ticksThisSecond++;
            boolean world = mc.level != null && mc.player != null;
            // the mod's own config screen stays visible (someone opened it to switch blind off)
            if (mode.enabled() && world && !(mc.screen instanceof BotConfigScreen)) {
                mc.noRender = true;
                asserted = true;
                if (!soundPaused && mode.config().pauseSound()) pauseSound(mc);
            } else {
                if (asserted) mc.noRender = false;
                asserted = false;
            }
            long now = System.currentTimeMillis();
            if (secondStart == 0) secondStart = now;
            if (now - secondStart >= 1000) {
                tps = (int) Math.round(ticksThisSecond * 1000.0 / (now - secondStart));
                ticksThisSecond = 0;
                secondStart = now;
                frameCounter += Math.max(0, mc.getFps());
                mode.frames(frameCounter);
                if (BlindMode.titleAction(mode.enabled(), world, mode.config().title(), restoreLeft) == BlindMode.TitleAction.SET) setTitle(mc);
            }
            // blind off: vanilla's title back, on the client thread after the title step, and once more each of the
            // next 2 ticks so nothing queued before the switch can leave the blind title behind
            if (BlindMode.titleAction(mode.enabled(), world, mode.config().title(), restoreLeft) == BlindMode.TitleAction.RESTORE) {
                restoreLeft--;
                restoreTitle(mc);
            }
        } catch (Throwable t) {
            LOG.warn("[entropybot] blind tick: {}", t.toString());
        }
    }

    private void pauseSound(Minecraft mc) {
        soundPaused = true;
        try {
            mc.getSoundManager().pause();
        } catch (Throwable t) {
            soundError = "pausing the sound engine threw " + t;
            LOG.warn("[entropybot] blind: {}", soundError);
        }
    }

    private void resumeSound(Minecraft mc) {
        if (!soundPaused) return;
        soundPaused = false;
        try {
            mc.getSoundManager().resume();
        } catch (Throwable t) {
            soundError = "resuming the sound engine threw " + t;
            LOG.warn("[entropybot] blind: {}", soundError);
        }
    }

    private void setTitle(Minecraft mc) {
        try {
            var p = mc.player;
            String t = BlindMode.title(p.getGameProfile().getName(), p.getBlockX(), p.getBlockY(), p.getBlockZ(), jobText.get(), tps, mc.getFps());
            if (!t.equals(mode.lastTitle())) {
                mc.getWindow().setTitle(t);
                mode.lastTitle(t);
            }
            titleSet = true;
        } catch (Throwable t) {
            if (titleError == null) LOG.warn("[entropybot] blind: setting the window title threw {}", t.toString());
            titleError = "setting the window title threw " + t;
        }
    }

    /** Ticks left in which vanilla's title is put back (set on blind off). */
    private int restoreLeft;

    private void restoreTitle(Minecraft mc) {
        titleSet = false;
        mode.lastTitle(null);
        try {
            // Not mc.updateTitle(): a pack mod owns that call (FancyMenu's custom window title; live 2026-10-10 the vanilla
            // title never came back). Window.setTitle works (the blind title appears at once), so write vanilla's text
            // ourselves, the way Minecraft.createTitle builds it (singleplayer / 3rd-party server).
            String title = "Minecraft " + net.minecraft.SharedConstants.getCurrentVersion().getName();
            var conn = mc.getConnection();
            if (conn != null && conn.getConnection().isConnected()) {
                var sp = mc.getSingleplayerServer();
                title += " - " + net.minecraft.client.resources.language.I18n.get(sp != null && !sp.isPublished() ? "title.singleplayer" : "title.multiplayer.other");
            }
            mc.getWindow().setTitle(title);
        } catch (Throwable t) {
            if (titleError == null) LOG.warn("[entropybot] blind: restoring the window title threw {}", t.toString());
            titleError = "restoring the window title threw " + t;
        }
    }

    /** Switches blind mode (the verb and the config screen); the answer. Game thread. */
    public synchronized String set(boolean on) {
        Minecraft mc = Minecraft.getInstance();
        ensureLoaded(mc);
        StringBuilder r = new StringBuilder();
        if (on && !mode.enabled()) {
            try {
                var w = io.github.mojolowjo.entropybot.engine.WatchCamera.INSTANCE;
                if (w.anyOn()) {
                    w.turnAllOff();
                    whisper.accept(BlindMode.WATCH_TURNED_OFF);
                    r.append(" ").append(BlindMode.WATCH_TURNED_OFF).append(".");
                }
            } catch (Throwable t) {
                r.append(" (couldn't turn the watch view off: ").append(t).append(")");
            }
        }
        boolean changed = mode.set(on, System.currentTimeMillis());
        String saved = dir == null ? " (no entropybot folder yet: not saved)" : mode.save(dir);
        if (changed && !on) {
            asserted = false;
            try { mc.noRender = false; } catch (Throwable ignored) {}
            resumeSound(mc);
            restoreLeft = 3;          // the restore runs in postTick (not here): never gated on a flag the title step may not have set
        }
        String head = on ? (changed ? "ok: blind mode on - no frames are drawn; the bot works on (blind off to see it again)"
                : "blind mode is already on")
                : (changed ? "ok: blind mode off - the window draws again" : "blind mode is already off");
        return head + saved + r;
    }

    /** "blind on|off|status" (owner only). */
    public String command(String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase();
        switch (t) {
            case "on" -> { return set(true); }
            case "off" -> { return set(false); }
            case "", "status" -> { return status(); }
            default -> { return "usage: " + BlindMode.USAGE; }
        }
    }

    public String status() {
        int maxFps = -1;
        try { maxFps = Minecraft.getInstance().options.framerateLimit().get(); } catch (Throwable ignored) {}
        return mode.status(maxFps, System.currentTimeMillis());
    }

    /** Self-check findings (never throws). */
    public List<SelfCheck.Finding> findings() {
        List<SelfCheck.Finding> f = new ArrayList<>();
        if (mode.configProblem() != null) f.add(new SelfCheck.Finding("blindconfig", "blind mode: " + mode.configProblem(), "blind on|off (writes a fresh blind.json)"));
        if (mode.resetSeen()) f.add(new SelfCheck.Finding("blindreset",
                "blind mode is on, but rendering was found turned back on at the end of " + BlindMode.RESET_TICKS + " ticks in a row (another mod resets Minecraft.noRender)",
                "blind status; note the mods that touch rendering; blind off"));
        if (titleError != null) f.add(new SelfCheck.Finding("blindtitle", "blind mode: " + titleError, "set \"title\": false in blind.json"));
        if (soundError != null) f.add(new SelfCheck.Finding("blindsound", "blind mode: " + soundError, "set \"sound\": \"off\" in blind.json"));
        return f;
    }
}
