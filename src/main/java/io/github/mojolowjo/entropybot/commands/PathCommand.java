package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.move.ServerCmds;
import net.minecraft.client.player.LocalPlayer;

/**
 * {@code path maxWalk [blocks]}: the longest straight walk accepted (stored in commands.json's route settings);
 * {@code server commands [on|off|status]}. rm-routes: the movement package's status, path assist and edge penalties
 * went with the long-route process (a walk is one Baritone goal). Never busy.
 */
final class PathCommand {
    private PathCommand() {}

    /** The stored settings onto the runtime (at load and on every change). Never throws; a failure is logged. */
    static void syncRuntime(Commands c) {
        try {
            JsonObject r = c.routeSettings();
            ServerCmds.setOn(r.has("serverCommands") && r.get("serverCommands").getAsBoolean());     // 0.24.1, default off
            ServerCmds.setMaxWalk(r.has("maxWalk") ? r.get("maxWalk").getAsInt() : ServerCmds.maxWalkDefault());
        } catch (RuntimeException t) {
            com.mojang.logging.LogUtils.getLogger().warn("[entropybot] path settings: not applied at the load stage: {}", t.toString());
        }
    }

    static String run(Commands c, LocalPlayer p, String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase(java.util.Locale.ROOT);
        if (r.isEmpty() || r.equals("maxwalk") || r.equals("maxwalk status")) return "ok: path maxWalk " + ServerCmds.maxWalk() + " (a walk further than that, straight, is refused)";
        if (r.startsWith("maxwalk ")) {
            int n;
            try {
                n = Integer.parseInt(r.substring(8).trim());
            } catch (NumberFormatException e) {
                return "error: path maxWalk <blocks> (100-100000)";
            }
            if (n < 100 || n > 100000) return "error: path maxWalk <blocks> (100-100000)";
            JsonObject s = c.routeSettings().deepCopy();
            s.addProperty("maxWalk", n);
            c.setRouteSettings(s);
            ServerCmds.setMaxWalk(n);
            return "ok: path maxWalk " + n + ": walks further than that are refused";
        }
        return "error: path maxWalk [<blocks>]";
    }

    /** 0.24.1: {@code server commands [on|off|status]} (default off; stored in commands.json's route settings). */
    static String server(Commands c, String rest, boolean isOwner) {
        String r = rest == null ? "" : rest.trim().toLowerCase(java.util.Locale.ROOT);
        if (r.equals("commands") || r.equals("commands status") || r.isEmpty()) return ServerCmds.status();
        if (r.equals("commands on") || r.equals("commands off")) {
            if (!isOwner) return "sorry, only " + c.owner() + " can change that";
            boolean on = r.endsWith(" on");
            JsonObject s = c.routeSettings().deepCopy();
            s.addProperty("serverCommands", on);
            c.setRouteSettings(s);
            ServerCmds.setOn(on);
            return ServerCmds.status();
        }
        return "error: server commands [on|off|status]";
    }
}
