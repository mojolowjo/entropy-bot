package io.github.mojolowjo.entropycompanion;

import java.io.IOException;
import java.net.URI;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The decision part of the companion, with no Minecraft or network types so it can be tested: when a post is due,
 * what it says, what a result means. {@link #due} is called from the client tick (a comparison, nothing more);
 * {@link #attempt} runs on the worker thread and does the blocking send.
 */
public final class PostLoop {
    /** The network, behind an interface for the tests. Returns the HTTP status; throws on no connection or a timeout. */
    public interface Sender {
        int post(URI endpoint, String key, String body, int timeoutMs) throws IOException;
    }

    public static final int TIMEOUT_MS = 2000;
    /** How often the config file is looked at for changes. */
    static final long CONFIG_CHECK_MS = 5000;

    private final Supplier<CompanionConfig> config;
    private final Sender sender;
    private final Consumer<String> log;
    private final LongSupplier clock;
    private final Backoff backoff = new Backoff();
    private volatile long nextDueMs;
    private boolean announcedIdle;
    // 0.2.0: counters for /bot companion, and the link shared with the commands
    private volatile CmdClient.Link link;
    private volatile Consumer<String> notice = s -> {};
    private volatile int ok, failed, refused503;
    private volatile long lastOkAt;
    private volatile String lastError = "none";

    public PostLoop(Supplier<CompanionConfig> config, Sender sender, Consumer<String> log, LongSupplier clock) {
        this.config = config;
        this.sender = sender;
        this.log = log;
        this.clock = clock;
    }

    /** The shared up/down link and where its changes are announced (the overlay and chat). */
    public void link(CmdClient.Link link, Consumer<String> notice) {
        this.link = link;
        this.notice = notice;
    }

    /** True when it is time for the next post. */
    public boolean due() {
        return clock.getAsLong() >= nextDueMs;
    }

    public Backoff backoff() { return backoff; }
    public int okCount() { return ok; }
    public int failedCount() { return failed; }
    public long lastOkAt() { return lastOkAt; }
    public String lastError() { return lastError; }

    /** One post attempt with the current config. Never throws. */
    public void attempt(OwnerPayload.Snapshot snap) {
        attemptBody(at -> OwnerPayload.json(snap, at));
    }

    /** One post attempt; bodyFor gives the JSON for the time now (null = nothing usable). Never throws. */
    public void attemptBody(LongFunction<String> bodyFor) {
        long now = clock.getAsLong();
        CompanionConfig c;
        try {
            c = config.get();
        } catch (RuntimeException e) {
            nextDueMs = now + CONFIG_CHECK_MS;
            return;
        }
        if (!c.active()) {
            // switched off, or no url / key yet: send nothing, look again soon (the file may have been edited)
            nextDueMs = now + CONFIG_CHECK_MS;
            if (!announcedIdle) {
                announcedIdle = true;
                log.accept("not sending: " + (c.enabled ? "fill in url and key in config/" + CompanionConfig.FILE_NAME : "disabled in config"));
            }
            return;
        }
        announcedIdle = false;
        String body;
        try {
            body = bodyFor.apply(now);
        } catch (RuntimeException e) {
            body = null;
        }
        long interval = c.intervalSeconds * 1000L;
        if (body == null) {
            nextDueMs = now + interval;
            return;
        }
        try {
            int status = sender.post(c.endpoint(), c.key, body, TIMEOUT_MS);
            if (status >= 200 && status < 300) {
                ok++;
                refused503 = 0;
                lastOkAt = clock.getAsLong();
                if (backoff.success()) log.accept("connected to the dashboard");
                CmdClient.Link l = link;
                if (l != null && l.worked()) notice.accept("connected");
            } else if (status == 503 && ++refused503 < 3) {
                // the dashboard was busy writing owner.json: ignored, the next post replaces it (logged on the third)
                failed++;
            } else {
                fail(c, status == 403
                        ? "the dashboard refused the post (403): check the key, and that this account is the configured owner"
                        : "the dashboard answered HTTP " + status, false);
            }
        } catch (IOException | RuntimeException e) {
            fail(c, "the dashboard is unreachable (" + e.getClass().getSimpleName() + ")", true);
        }
        nextDueMs = clock.getAsLong() + backoff.delayMs(interval);
    }

    private void fail(CompanionConfig c, String why, boolean unreachable) {
        failed++;
        String w = c.key.isEmpty() ? why : why.replace(c.key, "***");
        lastError = w;
        boolean tipped = backoff.failure();      // the third failure in a row: one log line, one notice
        if (tipped) log.accept(w + "; trying again every " + Backoff.FAILING_INTERVAL_MS / 1000 + " s");
        CmdClient.Link l = link;
        if (tipped && unreachable && l != null && l.failed(clock.getAsLong())) notice.accept(CmdClient.UNREACHABLE);
    }
}
