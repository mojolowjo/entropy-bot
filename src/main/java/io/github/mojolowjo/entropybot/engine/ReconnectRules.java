package io.github.mojolowjo.entropybot.engine;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import io.github.mojolowjo.entropybot.Quiet;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;

/**
 * When {@link Reconnect} tries again: after 1, 5 and 15 minutes out of a world, then (T4, 2026-10-04: the night the
 * server restarted and the bot sat at the title screen for hours) every 30 minutes for 24 hours from the first time
 * it found itself out; never more than 3 tries in any hour. Pure.
 */
public final class ReconnectRules {
    private ReconnectRules() {}

    /** The first waits, in ticks: 1, 5, 15 minutes. */
    public static final long[] WAITS = { 1200, 6000, 18000 };
    /** T4: the wait after those, in ticks: 30 minutes. */
    public static long laterWait() { return Tuning.l("reconnectRules.laterWait"); }
    /** T4: it stops trying this long after it first found itself out of a world. */
    public static long giveUpMs() { return Tuning.l("reconnectRules.giveUpMs"); }
    public static int perHour() { return Tuning.i("reconnectRules.perHour"); }
    static final long HOUR_MS = 3_600_000L; // structural: milliseconds in an hour (unit)

    /** The wait (ticks) before try number {@code tries} (0-based). */
    public static long waitFor(int tries) {
        return tries < WAITS.length ? WAITS[tries] : laterWait();
    }

    /** The 24 hours since {@code firstOutMs} (-1: not out) are over. */
    public static boolean gaveUp(long nowMs, long firstOutMs) {
        return firstOutMs >= 0 && nowMs - firstOutMs >= giveUpMs();
    }

    /**
     * The wait for this try is over, the 24 hours are not, and fewer than PER_HOUR tries happened in the last hour
     * (old ones are dropped from {@code recent}).
     */
    public static boolean due(long waited, int tries, ArrayDeque<Long> recent, long nowMs, long firstOutMs) {
        if (gaveUp(nowMs, firstOutMs) || waited < waitFor(tries)) return false;
        while (!recent.isEmpty() && nowMs - recent.peekFirst() > HOUR_MS) recent.pollFirst();
        return recent.size() < perHour();
    }

    /**
     * When the next try comes (epoch ms), or -1 when it gave up. {@code waited} ticks of the current wait are over
     * (counted as 50 ms each); a full hour of tries pushes it to an hour after the oldest of them.
     */
    public static long nextTryMs(long waited, int tries, ArrayDeque<Long> recent, long nowMs, long firstOutMs) {
        if (gaveUp(nowMs, firstOutMs)) return -1;
        long at = nowMs + Math.max(0, waitFor(tries) - waited) * 50L;
        int inHour = 0;
        Long oldest = null;
        for (Long t : recent) {
            if (nowMs - t > HOUR_MS) continue;
            inHour++;
            if (oldest == null) oldest = t;
        }
        if (inHour >= perHour() && oldest != null) at = Math.max(at, oldest + HOUR_MS + 1);
        if (firstOutMs >= 0 && at >= firstOutMs + giveUpMs()) return -1;
        return at;
    }

    /**
     * The status text (state.json "reconnect" while out of a world): "next try to <server> at 07:38 (try 4)",
     * "gave up on <server> after 24 h - relaunch me", "off", or "no server to go back to".
     */
    public static String statusText(boolean on, String server, int tries, long nextMs, ZoneId zone) {
        if (!on) return "off";
        if (server == null) return "no server to go back to";
        if (nextMs < 0) return "gave up on " + server + " after 24 h - relaunch me";
        String hhmm = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(nextMs));
        return "next try to " + server + " at " + hhmm + " (try " + (tries + 1) + ")";
    }

    /**
     * T4: the server to go back to: the one last played on, else the one the game was launched to join (Prism's
     * quick play, {@code --quickPlayMultiplayer host:port}), so a refused first join retries too. Null: none.
     */
    public static String seed(String current, String launched) {
        if (current != null && !current.isBlank()) return current;
        return launched == null || launched.isBlank() ? null : launched.trim();
    }

    // ---- 0.26.3: the connect timeout (a retry whose "Connecting to ..." never got an answer sat there for an hour) ----

    /** commands.json key beside "reconnect". */
    public static final String TIMEOUT_KEY = "reconnect.connectTimeoutS";
    public static int timeoutDefaultS() { return Tuning.i("reconnectRules.timeoutDefaultS"); }
    public static int timeoutMinS() { return Tuning.i("reconnectRules.timeoutMinS"); }
    public static int timeoutMaxS() { return Tuning.i("reconnectRules.timeoutMaxS"); }

    /** Null when s is a valid timeout, else why not. */
    public static String timeoutRefusal(int s) {
        return s < timeoutMinS() || s > timeoutMaxS() ? "reconnect timeout takes " + timeoutMinS() + " to " + timeoutMaxS() + " seconds, not " + s : null;
    }

    /** The saved timeout from commands.json's object (missing, odd or out of range: the default). */
    public static int timeoutOf(com.google.gson.JsonObject o) {
        try {
            if (o == null || !o.has(TIMEOUT_KEY)) return timeoutDefaultS();
            int s = o.get(TIMEOUT_KEY).getAsInt();
            return timeoutRefusal(s) == null ? s : timeoutDefaultS();
        } catch (RuntimeException e) {
            Quiet.warn("ReconnectRules.timeoutOf failed, carrying on with the fallback", e);
            return timeoutDefaultS();
        }
    }

    /** The same, read from the file (outside a world, before Commands loaded it). */
    public static int savedTimeout(java.nio.file.Path commandsJson) {
        try {
            if (!java.nio.file.Files.isRegularFile(commandsJson)) return timeoutDefaultS();
            com.google.gson.JsonElement e = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(commandsJson));
            return e.isJsonObject() ? timeoutOf(e.getAsJsonObject()) : timeoutDefaultS();
        } catch (Exception ex) {
            Quiet.warn("ReconnectRules.savedTimeout failed, carrying on with the fallback", ex);
            return timeoutDefaultS();
        }
    }

    /**
     * The attempt started at attemptStartMs (-1: none in progress) is over its time: not in a world timeoutS seconds or
     * more after it began. In a world never.
     */
    public static boolean connectTimedOut(long attemptStartMs, long nowMs, int timeoutS, boolean inWorld) {
        return attemptStartMs >= 0 && !inWorld && nowMs - attemptStartMs >= timeoutS * 1000L;
    }

    /** check's line for a timed-out attempt. */
    public static String timeoutText(int timeoutS) { return "reconnect: the last connect attempt timed out after " + timeoutS + " s"; }

    /** The saved "reconnect" flag in commands.json: false only when the file says false (missing or unreadable = on). */
    public static boolean savedFlag(java.nio.file.Path commandsJson) {
        try {
            if (!java.nio.file.Files.isRegularFile(commandsJson)) return true;
            com.google.gson.JsonElement e = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(commandsJson));
            if (!e.isJsonObject()) return true;
            com.google.gson.JsonElement r = e.getAsJsonObject().get("reconnect");
            return r == null || !r.isJsonPrimitive() || r.getAsBoolean();
        } catch (Exception ex) {
            Quiet.warn("ReconnectRules.savedFlag failed, carrying on with the fallback", ex);
            return true;
        }
    }
}
