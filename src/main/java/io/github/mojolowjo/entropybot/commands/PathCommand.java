package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.move.EdgePenalties;
import io.github.mojolowjo.entropybot.move.MovePackage;
import net.minecraft.client.player.LocalPlayer;

/**
 * 0.23.3: {@code path} / {@code path status}: the movement package's counters (command-to-first-move, chain gaps, legs,
 * fails, penalised edges, ready paths, the active profile); {@code path assist on|off|status}: the long-route process
 * for far walks (default on; off = plain Baritone goto, stored in commands.json's route settings);
 * {@code path penalties clear}: forget the edge penalties. Never busy.
 */
final class PathCommand {
    private PathCommand() {}

    static String run(Commands c, LocalPlayer p, String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase(java.util.Locale.ROOT);
        MovePackage mp = MovePackage.INSTANCE;
        if (r.isEmpty() || r.equals("status")) return "ok: " + mp.status(p);
        if (r.equals("assist") || r.equals("assist status")) return "ok: path assist " + (mp.assist() ? "on" : "off")
                + " (walks over 48 blocks or 10 levels go through the long-route process)";
        if (r.equals("assist on") || r.equals("assist off")) {
            boolean on = r.endsWith("on");
            JsonObject s = c.routeSettings().deepCopy();
            s.addProperty("assist", on);
            c.setRouteSettings(s);
            mp.setAssist(on);
            return "ok: path assist " + (on ? "on: far walks go leg by leg through the long-route process" : "off: far walks are plain Baritone gotos");
        }
        if (r.equals("penalties clear")) {
            EdgePenalties.GLOBAL.clear();
            mp.savePenalties();
            return "ok: forgot the route edge penalties";
        }
        return "error: path [status] | path assist on|off|status | path penalties clear";
    }
}
