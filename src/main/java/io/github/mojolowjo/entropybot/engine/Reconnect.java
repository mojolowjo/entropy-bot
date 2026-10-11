package io.github.mojolowjo.entropybot.engine;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.events.EventRing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;

import java.time.ZoneId;
import java.util.ArrayDeque;

/**
 * Reconnecting after a kick or a dropped connection (B6, docs/BOT_PLAN.md 8; the owner's and the server owner's
 * answer: yes, at most 3 times an hour). The KubeJS bridge does not tick outside a world, so this lives in the mod:
 * the server last played on is noted while in a world (T4: or the one the game was launched to join); out of one, it
 * reconnects after 1, 5 and 15 minutes, then every 30 minutes for 24 hours, at most 3 times in any hour
 * ({@link ReconnectRules}). A relaunch after a crash stays the watchdog's (tools/watchdog.ps1).
 *
 * <p>0.26.3 connect timeout ({@code reconnect.connectTimeoutS}, default 60, 15-600; 2026-10-11 a retry sat on
 * "Connecting to ..." from 10:39 past 11:30): an attempt this mod started that is not in a world after that long is
 * cancelled exactly like vanilla's cancel button in {@code ConnectScreen.init()} (1.21.1): the button sets
 * {@code ConnectScreen.aborted}, calls {@code channelFuture.cancel(true)} and {@code Connection.disconnect(ConnectScreen.ABORT_CONNECTION)},
 * then {@code Minecraft.setScreen(parent)}; we press that button ({@code Button.onPress()}, found by its message
 * {@code CommonComponents.GUI_CANCEL} in {@code Screen.children()}), falling back to {@code Minecraft.disconnect(Screen)}
 * (which nulls {@code pendingConnection} in updateScreenAndTick). The try counts as failed: the next wait starts then,
 * with the same backoff. Loader notes: vanilla client classes only (no mixin, no accessor).
 */
public final class Reconnect {
    private static final Logger LOG = LogUtils.getLogger();

    /**
     * T4: the server the game was launched to join (Prism's {@code --quickPlayMultiplayer}), set by
     * ReconnectMixinQuickPlay before the first connect; null without one (or without the Mixin).
     */
    public static volatile String launchServer;

    private final EventRing events;
    private volatile boolean on = true;
    private String server;
    private long outSince = -1;
    /** T4: wall clock when it first found itself out of a world this time (-1: in one); the 24 h count from here. */
    private long firstOutMs = -1;
    private int tries;
    private boolean gaveUpLogged, savedFlagRead;
    private final ArrayDeque<Long> recent = new ArrayDeque<>();
    private volatile String statusText = "in a world";

    public Reconnect(EventRing events) {
        this.events = events;
    }

    public void setOn(boolean on) { this.on = on; }

    public boolean on() { return on; }

    public String server() { return server; }

    /** T4: state.json's "reconnect" while out of a world: when the next try comes, or that it gave up (updated each tick). */
    public String statusText() { return statusText; }

    /** Every client tick (in a world or not). Never throws. */
    public void tick(long tick) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.player != null) {
                ServerData d = mc.getCurrentServer();
                if (d != null && d.ip != null && !d.ip.isEmpty()) server = d.ip;
                outSince = -1;
                firstOutMs = -1;
                attemptAt = -1;
                tries = 0;
                gaveUpLogged = false;           // (recent stays: 3 tries in any hour, kicks in a row included)
                statusText = "in a world";
                return;
            }
            if (server == null && launchServer != null && !savedFlagRead) {
                // the owner's `reconnect off` lives in commands.json, which Commands only reads inside a world
                savedFlagRead = true;
                java.nio.file.Path cj = mc.gameDirectory.toPath().resolve("entropybot").resolve("commands.json");
                if (!ReconnectRules.savedFlag(cj)) on = false;
                connectTimeoutS = ReconnectRules.savedTimeout(cj);      // 0.26.3
            }
            server = ReconnectRules.seed(server, launchServer);
            long now = System.currentTimeMillis();
            if (server == null || !on) {
                statusText = ReconnectRules.statusText(on, server, tries, -1, ZoneId.systemDefault());
                return;
            }
            // 0.26.3: an attempt still not in a world after connectTimeoutS is cancelled (vanilla's cancel button) and
            // counts as a failed try: the next wait starts now, with the same backoff
            if (attemptAt >= 0 && ended(mc.screen)) attemptAt = -1;      // refused or dropped: the normal path
            if (ReconnectRules.connectTimedOut(attemptAt, now, connectTimeoutS, false)) {
                String how = cancel(mc);
                attemptAt = -1;
                outSince = tick;
                lastTimeoutAt = now;
                lastTimeout = ReconnectRules.timeoutText(connectTimeoutS);
                LOG.info("[entropybot] reconnect: try {} to {} still not in a world after {} s - cancelled ({}), counted as a failed try", tries, server, connectTimeoutS, how);
                events.push("job", lastTimeout + " (try " + tries + ")", null);
                return;
            }
            if (mc.screen instanceof ConnectScreen) {
                statusText = "connecting to " + server + (tries > 0 ? " (try " + tries + ")" : "")
                        + (attemptAt >= 0 ? ", " + (now - attemptAt) / 1000 + " s of " + connectTimeoutS : "");
                return;
            }
            if (outSince < 0) outSince = tick;
            if (firstOutMs < 0) firstOutMs = now;
            long waited = tick - outSince;
            if (tick % 20 == 0) {
                long next = ReconnectRules.nextTryMs(waited, tries, recent, now, firstOutMs);
                statusText = ReconnectRules.statusText(true, server, tries, next, ZoneId.systemDefault());
                if (next < 0 && !gaveUpLogged) {
                    gaveUpLogged = true;
                    LOG.info("[entropybot] reconnect: gave up on {} after 24 h ({} tries)", server, tries);
                    events.push("job", "reconnect: gave up on " + server + " after 24 h", null);
                }
            }
            if (!ReconnectRules.due(waited, tries, recent, now, firstOutMs)) return;
            recent.addLast(now);
            tries++;
            outSince = tick;
            LOG.info("[entropybot] reconnecting to {} (try {})", server, tries);
            events.push("job", "reconnecting to " + server + " (try " + tries + ")", null);
            attemptAt = now;
            ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(server),
                    new ServerData("server", server, ServerData.Type.OTHER), false, null);
        } catch (Throwable t) {
            LOG.warn("[entropybot] reconnect: {}", t.toString());
        }
    }

    // ---- 0.26.3: the connect timeout ----

    private volatile int connectTimeoutS = ReconnectRules.TIMEOUT_DEFAULT_S;
    /** Wall clock the current attempt began (-1: none in progress); the last timeout's text and time. */
    private long attemptAt = -1;
    private volatile String lastTimeout;
    private volatile long lastTimeoutAt = -1;

    public int connectTimeoutS() { return connectTimeoutS; }

    public void setConnectTimeoutS(int s) { if (ReconnectRules.timeoutRefusal(s) == null) connectTimeoutS = s; }

    /** check: "reconnect: the last connect attempt timed out after 60 s" within the last 24 h, else null. */
    public String lastTimeout() {
        return lastTimeout != null && System.currentTimeMillis() - lastTimeoutAt < ReconnectRules.GIVE_UP_MS ? lastTimeout : null;
    }

    /** The attempt is over without a world: back at the title, the server list or vanilla's "disconnected" screen. */
    static boolean ended(net.minecraft.client.gui.screens.Screen s) {
        return s == null || s instanceof TitleScreen || s instanceof net.minecraft.client.gui.screens.DisconnectedScreen
                || s instanceof net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
    }

    /**
     * Cancels the pending connection the way vanilla's cancel button does: ConnectScreen's own Cancel button
     * ({@code CommonComponents.GUI_CANCEL}, found among {@code Screen.children()}) is pressed, which sets its
     * {@code aborted} flag, cancels the netty {@code channelFuture} and calls {@code Connection.disconnect(ABORT_CONNECTION)},
     * then {@code setScreen(parent)} (our TitleScreen). Without that button (a screen past the handshake, not yet built):
     * {@code Minecraft.disconnect(new TitleScreen())}, which drops a pending connection and shows the title.
     */
    static String cancel(Minecraft mc) {
        if (mc.screen instanceof ConnectScreen cs) {
            for (var l : cs.children()) {
                if (l instanceof net.minecraft.client.gui.components.Button b && net.minecraft.network.chat.CommonComponents.GUI_CANCEL.equals(b.getMessage())) {
                    b.onPress();
                    return "the connect screen's cancel";
                }
            }
        }
        mc.disconnect(new TitleScreen());
        return "Minecraft.disconnect";
    }
}
