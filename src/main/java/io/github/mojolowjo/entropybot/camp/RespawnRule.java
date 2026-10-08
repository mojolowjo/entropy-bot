package io.github.mojolowjo.entropybot.camp;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.24.3: after a join or a respawn the bot may stand outside every area (an old spawn point far from the base). Fed
 * every 20 ticks; once the bot has been alive 5 s after a join or respawn it checks once: outside every area -> one
 * whisper and a check line until it is inside again. Respawn spots are remembered as homes (a build hint never fires
 * near them). Pure Java, no loader API.
 */
public final class RespawnRule {
    public static final int SETTLE_TICKS = 100, MAX_HOMES = 8;

    private boolean pending = true, respawned;
    private long aliveSince = -1;
    private String line;
    private final List<int[]> homes = new ArrayList<>();

    public static String text(int[] p) {
        return "I'm at " + p[0] + " " + p[1] + " " + p[2] + ", outside my areas - say: area here 48 camp, then bootstrap, or come get me";
    }

    /** A new world join: check again once settled. */
    public synchronized void joined() {
        pending = true;
        aliveSince = -1;
    }

    /** One step. Returns the whisper to send, or null. inAreas: does an area hold pos. */
    public synchronized String tick(boolean dead, long tick, int[] pos, boolean inAreas) {
        if (dead) {
            pending = true;
            respawned = true;
            aliveSince = -1;
            return null;
        }
        if (pos == null) return null;
        if (line != null && inAreas) line = null;
        if (aliveSince < 0) aliveSince = tick;
        if (!pending || tick - aliveSince < SETTLE_TICKS) return null;
        pending = false;
        if (respawned) {
            respawned = false;
            homes.add(pos.clone());
            while (homes.size() > MAX_HOMES) homes.remove(0);
        }
        if (inAreas) return null;
        line = text(pos);
        return line;
    }

    /** The check line while the bot is still outside, or null. */
    public synchronized String checkLine() { return line; }

    /** Where it respawned (the spawn point or its bed), newest last. */
    public synchronized List<int[]> homes() {
        List<int[]> out = new ArrayList<>();
        for (int[] h : homes) out.add(h.clone());
        return out;
    }
}
