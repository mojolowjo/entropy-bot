package io.github.mojolowjo.entropybot.camp;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the bot respawned (a home fact: a build hint never fires near them). Fed every 20 ticks; once the bot has
 * been alive 5 s after a respawn the spot is remembered. Pure Java, no loader API. rm-areas: the old "outside my
 * areas" whisper after a join or respawn is gone - the world is open.
 */
public final class RespawnHomes {
    public static int settleTicks() { return Tuning.i("respawnHomes.settleTicks"); }
    public static int maxHomes() { return Tuning.i("respawnHomes.maxHomes"); }

    private boolean respawned;
    private long aliveSince = -1;
    private final List<int[]> homes = new ArrayList<>();

    /** One step. */
    public synchronized void tick(boolean dead, long tick, int[] pos) {
        if (dead) {
            respawned = true;
            aliveSince = -1;
            return;
        }
        if (pos == null || !respawned) return;
        if (aliveSince < 0) aliveSince = tick;
        if (tick - aliveSince < settleTicks()) return;
        respawned = false;
        homes.add(pos.clone());
        while (homes.size() > maxHomes()) homes.remove(0);
    }

    /** Where it respawned (the spawn point or its bed), newest last. */
    public synchronized List<int[]> homes() {
        List<int[]> out = new ArrayList<>();
        for (int[] h : homes) out.add(h.clone());
        return out;
    }
}
