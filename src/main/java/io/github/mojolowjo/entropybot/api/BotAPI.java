package io.github.mojolowjo.entropybot.api;

import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import io.github.mojolowjo.entropybot.io.BotFiles;

/**
 * What scripts call: strings and JSON in and out, nothing that can throw into a caller. The optional KubeJS debug
 * script ({@code tools/debug/claude_debug.js} in the bot repo) reaches it with
 * {@code Java.tryLoadClass('io.github.mojolowjo.entropybot.api.BotAPI')}. B7e: read-only views plus the file helpers;
 * the calls only the old KubeJS bridge made are gone.
 */
public final class BotAPI {
    private BotAPI() {}

    private static Core core() { return Core.INSTANCE; }

    /** The mod's version, e.g. "0.1.0". */
    public static String version() {
        try { return core().version(); } catch (Throwable t) { return "unknown"; }
    }

    /** JSON array of what works right now: "files", "events", "guard:strict|log", "guard:click", "guard:place", "guard:astar"... */
    public static String features() {
        try { return core().features().toString(); } catch (Throwable t) { return "[]"; }
    }

    /** Events with seq greater than afterSeq, oldest first, at most max (0 = all), as a JSON array. */
    public static String events(long afterSeq, int max) {
        try { return core().events.since(afterSeq, max); } catch (Throwable t) { return "[]"; }
    }

    /** The newest event's seq (0 when none yet). */
    public static long lastSeq() {
        try { return core().events.lastSeq(); } catch (Throwable t) { return 0; }
    }

    /** The guard's status as JSON: mode, floor size, areas, protect boxes, leases, counts, which hooks apply. */
    public static String guard() {
        try { return core().guardStatus().toString(); } catch (Throwable t) { return "{\"error\":\"" + t + "\"}"; }
    }

    /** A dry run: "ok" or "would refuse: <reason>" for action "break", "place" or "go" at a spot. Never logs. */
    public static String check(String dim, int x, int y, int z, String action) {
        try {
            String a = action == null ? "break" : action.toLowerCase();
            if (!a.equals("break") && !a.equals("place") && !a.equals("go")) return "error: action must be break, place or go";
            GuardCore.BlockInfo info = GuardCore.BlockInfo.PLAIN;
            var mc = net.minecraft.client.Minecraft.getInstance();
            boolean here = mc.level != null && dim.equals(io.github.mojolowjo.entropybot.guard.Guard.dimOf(mc.level));
            if (a.equals("break") && here) info = core().guard.infoFor(mc.level, new net.minecraft.core.BlockPos(x, y, z));
            // a walk that ends next to a portal could step in: part of the floor, so in every mode
            if (a.equals("go") && here) {
                String portal = io.github.mojolowjo.entropybot.guard.Guard.portalNear(mc.level, x, y, z, 2);
                if (portal != null) return "would refuse: next to a " + portal + " (I stay out of the Nether and the End)";
            }
            GuardCore.Verdict v = core().guard.core.checkUnlogged(dim, x, y, z, a, info);
            if (v.allowed() && !v.wouldVeto()) return "ok";
            return (v.allowed() ? "would refuse (log mode): " : "would refuse: ") + v.reason();
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    /** The last vetoes (and would-be vetoes in log mode), newest last, as a JSON array. */
    public static String vetoes(int max) {
        try { return core().guard.core.log().recent(max); } catch (Throwable t) { return "[]"; }
    }

    /** Writes JSON atomically under the bot's folder: "ok: N bytes" or "error: ...". */
    public static String writeJson(String name, String json) {
        try {
            BotFiles f = core().files();
            return f == null ? "error: not in a world yet" : f.writeJson(name, json);
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    /** The file's text, null when it does not exist, "error: ..." when it cannot be read. */
    public static String readJson(String name) {
        try {
            BotFiles f = core().files();
            return f == null ? "error: not in a world yet" : f.readJson(name);
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    /** True while a reflex (eating, fighting, fleeing, retreating) runs and every job should hold still. */
    public static boolean hold() {
        try { return core().reflexes.hold(); } catch (Throwable t) { return false; }
    }

    /**
     * The reflexes as JSON: {reflex: none|eating|fighting|fleeing|retreating, status, on, target?, dist?,
     * urgent (just hurt, or a monster within 5), noFood, deniedDim?, engine: none|hold|override|off}.
     */
    public static String reflex() {
        try { return core().reflexes.status().toString(); } catch (Throwable t) { return "{\"reflex\":\"none\",\"error\":\"" + t + "\"}"; }
    }

    /** The open menu's slots by role, for checking a modded chest in game (the "debug gui" verb says the same). */
    public static String guiDescribe() {
        try {
            net.minecraft.client.player.LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
            return p == null ? "not in a world" : io.github.mojolowjo.entropybot.gui.Gui.describe(p);
        } catch (Throwable t) {
            return "error: " + t;
        }
    }
}
