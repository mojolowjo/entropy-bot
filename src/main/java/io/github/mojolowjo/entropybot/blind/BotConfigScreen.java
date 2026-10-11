package io.github.mojolowjo.entropybot.blind;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.commands.SelfCheck;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The mod's config screen (0.26.0, DEVNOTES roadmap 9): a blind mode toggle, Start / Stop dashboard, and a status line
 * (does port 8765 answer, the link with the key masked).
 *
 * <p>Loader API: NeoForge 21.1 opens it through {@code IConfigScreenFactory} registered with
 * {@code ModContainer.registerExtensionPoint} in {@code EntropyBot} (the Mods list's Config button); that one line is
 * loader-specific. The screen is vanilla: {@code Screen}, {@code Button.builder}, {@code GuiGraphics.drawCenteredString}.
 * The processes and the port check are plain Java ({@link DashboardControl}), run off the game thread.
 *
 * <p>Errors (the process didn't start, exited non-zero, nothing on 8765 within 10 s after Start) are shown on the
 * screen and become a {@code check} finding through {@link #findings()}. The key is never read or shown.
 */
public final class BotConfigScreen extends Screen {
    private static final Logger LOG = LogUtils.getLogger();

    /** Shared between screen instances and the self-check. */
    private static volatile Boolean up;
    private static volatile String lastError, lastNote;
    private static volatile boolean busy;

    private final Screen parent;
    private DashboardControl.Project project;
    private Button blindButton, startButton, stopButton;

    public BotConfigScreen(Screen parent) {
        super(Component.literal("Entropy Bot"));
        this.parent = parent;
    }

    /** check: the last dashboard button error (null: none). */
    public static List<SelfCheck.Finding> findings() {
        List<SelfCheck.Finding> f = new ArrayList<>();
        String e = lastError;
        if (e != null) f.add(new SelfCheck.Finding("dashbutton", "the config screen's dashboard button failed: " + e,
                "bridge.ps1 dashboard (from the bot project folder), then look at its output"));
        return f;
    }

    private static Path botDir() {
        try {
            File g = Minecraft.getInstance().gameDirectory;
            return g == null ? null : g.toPath().resolve("entropybot");
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    protected void init() {
        project = DashboardControl.find(botDir());
        int cx = width / 2, y = height / 4;
        blindButton = addRenderableWidget(Button.builder(blindLabel(), b -> {
            try {
                boolean on = !BlindRuntime.INSTANCE.mode().enabled();
                lastNote = BlindRuntime.INSTANCE.set(on);
            } catch (Throwable t) {
                lastNote = "blind: " + t;
            }
            b.setMessage(blindLabel());
        }).bounds(cx - 100, y, 200, 20).build());
        startButton = addRenderableWidget(Button.builder(Component.literal("Start dashboard"), b -> run(false)).bounds(cx - 100, y + 28, 98, 20).build());
        stopButton = addRenderableWidget(Button.builder(Component.literal("Stop dashboard"), b -> run(true)).bounds(cx + 2, y + 28, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(cx - 100, height - 30, 200, 20).build());
        refreshButtons();
        checkPort(0);
    }

    private static Component blindLabel() {
        return Component.literal("Blind mode: " + (BlindRuntime.INSTANCE.mode().enabled() ? "ON" : "off"));
    }

    private void refreshButtons() {
        boolean ok = project != null && project.ok() && !busy;
        if (startButton != null) startButton.active = ok;
        if (stopButton != null) stopButton.active = ok;
    }

    /** Checks port 8765 on a background thread (after waitMs, for a stop that needs a moment). */
    private static void checkPort(long waitMs) {
        Thread t = new Thread(() -> {
            try {
                if (waitMs > 0) Thread.sleep(waitMs);
                up = DashboardControl.answers(DashboardControl.DASHBOARD_PORT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "entropybot-dashcheck");
        t.setDaemon(true);
        t.start();
    }

    /** A button: runs bridge.ps1 dashboard [stop] as its own process, waits for it off the game thread. */
    private void run(boolean stop) {
        if (project == null || !project.ok() || busy) return;
        busy = true;
        refreshButtons();
        lastNote = stop ? "stopping the dashboard..." : "starting the dashboard...";
        final Path script = project.script(), dir = project.dir();
        Thread t = new Thread(() -> {
            String err = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(DashboardControl.command(script, stop))
                        .directory(dir.toFile())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)      // the bridge prints the link with the key
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
                Process p = pb.start();
                if (!p.waitFor(60, TimeUnit.SECONDS)) {
                    err = "bridge.ps1 dashboard" + (stop ? " stop" : "") + " did not finish in 60 s";
                } else if (p.exitValue() != 0) {
                    err = "bridge.ps1 dashboard" + (stop ? " stop" : "") + " exited with code " + p.exitValue();
                }
                if (err == null && !stop) {
                    long until = System.currentTimeMillis() + DashboardControl.START_WAIT_MS;
                    boolean ok = false;
                    while (System.currentTimeMillis() < until && !(ok = DashboardControl.answers(DashboardControl.DASHBOARD_PORT))) Thread.sleep(500);
                    up = ok;
                    if (!ok) err = "the dashboard did not answer on port " + DashboardControl.DASHBOARD_PORT + " within 10 s after Start";
                } else {
                    Thread.sleep(1000);
                    up = DashboardControl.answers(DashboardControl.DASHBOARD_PORT);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                err = "interrupted";
            } catch (Exception e) {
                err = "couldn't start powershell.exe: " + e.getMessage();
            }
            if (err != null) LOG.warn("[entropybot] config screen: {}", err);
            lastError = err;
            lastNote = err == null ? (stop ? "dashboard stopped" : "dashboard started") : null;
            busy = false;
        }, "entropybot-dashbutton");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void tick() {
        refreshButtons();
        if (blindButton != null) blindButton.setMessage(blindLabel());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        int cx = width / 2, y = height / 4;
        g.drawCenteredString(font, title, cx, y - 24, 0xFFFFFF);
        int ly = y + 60;
        if (project != null && !project.ok()) {
            g.drawCenteredString(font, "dashboard buttons off: " + project.problem(), cx, ly, 0xFFAA55);
            ly += 12;
        }
        g.drawCenteredString(font, DashboardControl.statusLine(up, "localhost"), cx, ly, up != null && up ? 0x55FF55 : 0xCCCCCC);
        ly += 12;
        String note = lastNote, err = lastError;
        if (busy) g.drawCenteredString(font, note == null ? "working..." : note, cx, ly, 0xCCCCCC);
        else if (err != null) g.drawCenteredString(font, "error: " + err, cx, ly, 0xFF5555);
        else if (note != null) g.drawCenteredString(font, note.length() > 90 ? note.substring(0, 90) + "..." : note, cx, ly, 0xCCCCCC);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }
}
