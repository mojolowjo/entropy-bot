package io.github.mojolowjo.entropybot.move;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 0.24.1: {@code server commands on|off} (default off) and {@code path maxWalk} (default 2000). With server commands
 * off the bot never sends a slash command of its own (/home, /sethome, raw bridge commands): it walks instead, and
 * {@code check} lists the jobs that would have used one. Whispers (/msg) are its voice and stay. A walk whose straight
 * distance exceeds maxWalk is refused before any planning. Both are stored in commands.json's route settings. Pure Java.
 */
public final class ServerCmds {
    private ServerCmds() {}

    public static final int MAX_WALK_DEFAULT = 2000;
    private static volatile boolean on = false;
    private static volatile int maxWalk = MAX_WALK_DEFAULT;
    private static final Set<String> wouldHave = new LinkedHashSet<>();

    public static boolean on() { return on; }

    public static void setOn(boolean v) { on = v; }

    public static int maxWalk() { return maxWalk; }

    public static void setMaxWalk(int v) { maxWalk = Math.max(16, v); }

    /** True when a slash command may be sent; false notes "what" for check (the first time) and the caller walks instead. */
    public static boolean allowed(String what) {
        if (on) return true;
        synchronized (wouldHave) { wouldHave.add(what); }
        return false;
    }

    /** check's lines: each job that would have sent a server command while they were off. */
    public static List<String> checkLines() {
        List<String> out = new ArrayList<>();
        synchronized (wouldHave) {
            for (String w : wouldHave) out.add("server commands are off: " + w + " walked instead (server commands on to allow /home)");
        }
        return out;
    }

    /** The retreat's /home: only under 6 health, and only with server commands on. */
    public static boolean homeTp(double health, boolean serverCommandsOn) {
        return serverCommandsOn && health < 6;
    }

    /** Null when a walk of this straight distance may be planned, else the refusal. */
    public static String tooFar(double straight, int max) {
        if (!(straight > max)) return null;
        return "error: that is " + Math.round(straight) + " blocks away - too far to walk (path maxWalk " + max + ")";
    }

    public static String tooFar(int[] me, int[] dest, int max) {
        if (me == null || dest == null) return null;
        double dx = dest[0] - me[0], dz = dest[2] - me[2], dy = dest.length > 1 && me.length > 1 ? dest[1] - me[1] : 0;
        return tooFar(Math.sqrt(dx * dx + dy * dy + dz * dz), max);
    }

    public static String status() {
        return "ok: server commands " + (on ? "on: /home and /sethome are used (far trips teleport, the retreat under 6 health sends /home)"
                : "off: I never send /home or /sethome - I walk instead") + "; path maxWalk " + maxWalk;
    }
}
