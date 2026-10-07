package io.github.mojolowjo.entropycompanion;

/**
 * What the crosshair points at, turned into one bot command (COMPANION_PLAN 2.2). Plain Java: the Minecraft side
 * ({@link Pointer}) fills a {@link Hit}; this decides the line. The companion never decides what the bot may do:
 * the bot's guard, areas and dispatcher still refuse; this only refuses pointing the bot at players, pets and
 * peaceful animals.
 */
public final class PointRules {
    private PointRules() {}

    public enum Kind { MISS, BLOCK, ENTITY }

    /** One crosshair hit. Block fields for BLOCK, entity fields for ENTITY; x y z = the block (or the entity's block). */
    public record Hit(Kind kind, String id, int x, int y, int z,
                      boolean ore, boolean log, boolean container,
                      int entityId, boolean monster, boolean player, boolean pet, boolean villager, boolean item) {
        public static Hit miss() {
            return new Hit(Kind.MISS, "", 0, 0, 0, false, false, false, -1, false, false, false, false, false);
        }

        public static Hit block(String id, int x, int y, int z, boolean ore, boolean log, boolean container) {
            return new Hit(Kind.BLOCK, id, x, y, z, ore, log, container, -1, false, false, false, false, false);
        }

        public static Hit entity(String id, int entityId, int x, int y, int z, boolean monster, boolean player, boolean pet,
                                 boolean villager, boolean item) {
            return new Hit(Kind.ENTITY, id, x, y, z, false, false, false, entityId, monster, player, pet, villager, item);
        }
    }

    /** Either a command to send or a refusal to show (never both). */
    public record Result(String command, String refusal) {
        static Result cmd(String c) { return new Result(c, null); }
        static Result no(String why) { return new Result(null, why); }
        public boolean ok() { return command != null; }
    }

    /** The command for a hit. mineCount: ores to mine; chopCount: logs to chop. */
    public static Result decide(Hit h, int mineCount, int chopCount) {
        if (h == null || h.kind() == Kind.MISS) return Result.no("nothing under the crosshair");
        String at = h.x() + " " + h.y() + " " + h.z();
        if (h.kind() == Kind.ENTITY) {
            if (h.player() || h.pet()) return Result.no("I won't point the bot at players or pets");
            if (h.villager()) return Result.no("I won't point the bot at villagers");
            if (h.item()) return Result.cmd("goto " + at);                    // C6 adds a real pickup
            // 0.2.2 (V1b): the bot's attack rules decide (a passive mob asks for confirm there); the point key only names it
            return Result.cmd("attack target " + h.entityId());
        }
        if (h.container()) return Result.cmd("open " + at);
        if (h.ore() || h.id().endsWith("_ore")) return Result.cmd("mine " + bare(h.id()) + " " + Math.max(1, mineCount));
        if (h.log()) return Result.cmd("cut " + Math.max(1, chopCount) + " " + bare(h.id()));
        return Result.cmd("goto " + h.x() + " " + (h.y() + 1) + " " + h.z());
    }

    /** "minecraft:iron_ore" -> "iron_ore" (vanilla ids go without the prefix); modded ids stay whole. */
    static String bare(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    static String shortId(String id) {
        int i = id.indexOf(':');
        return i >= 0 ? id.substring(i + 1) : id;
    }
}
