package io.github.mojolowjo.entropybot.assist;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 0.24.2 {@code assist}: the bot helps the owner with what they are doing. The rules, pure: the companion 0.3.1 report
 * (owner.json: position, held, broke, placed, action), the activity guess (the report's own action when it sends one),
 * the switch rule (a new activity counts after 5 s; 60 s idle stops the mirroring, the follow stays), the half-cut tree
 * finder and the mining targets over a {@link World}, the give rule, the command words and the whispers. The game
 * side is {@code commands.Assist}. Loader notes: no Minecraft or loader types here.
 */
public final class AssistRules {
    private AssistRules() {}

    public static final long FRESH_MS = 10_000;        // an older report is no report
    public static final long RECENT_MS = 10_000;       // a broke/placed counts this long
    public static final long SWITCH_MS = 5_000;        // a new activity must hold this long
    public static final long IDLE_STOP_MS = 60_000;    // idle this long: no mirroring, just follow
    public static final long GIVE_GAP_MS = 30_000;     // at most one hand-over in this time
    public static final long RETRY_MS = 60_000;        // the same chain is not started again within this
    public static final int CHOP_R = 16, MINE_R = 12, TREE_R = 4, TREE_UP = 12;
    public static final int GIVE_LOW = 16, GIVE_MAX = 32, MINE_MAX = 8, TREE_MAX = 4, FARM_R = 16;

    public enum Act {
        MINING, CHOPPING, FARMING, BUILDING, FIGHTING, IDLE;

        public String word() { return name().toLowerCase(Locale.ROOT); }

        public static Act of(String w) {
            if (w == null) return null;
            for (Act a : values()) if (a.word().equals(w.trim().toLowerCase(Locale.ROOT))) return a;
            return null;
        }
    }

    /** A block event of the report: id, where, when (laptop clock: received - age), left (placed: the owner's count, -1 unknown). */
    public record Ev(String id, int x, int y, int z, long at, int left) {}

    /** One owner.json. action: the companion's guess (null: an older companion). */
    public record Report(String name, int x, int y, int z, String dim, long received, String held, String action, Ev broke, Ev placed) {}

    public static Report parse(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            long received = o.get("received").getAsLong();
            String held = o.has("held") && o.get("held").isJsonPrimitive() ? o.get("held").getAsString() : "";
            String action = o.has("action") && o.get("action").isJsonPrimitive() ? o.get("action").getAsString() : null;
            return new Report(o.get("name").getAsString(), (int) Math.floor(o.get("x").getAsDouble()), (int) Math.floor(o.get("y").getAsDouble()),
                    (int) Math.floor(o.get("z").getAsDouble()), o.get("dim").getAsString(), received, held, action,
                    ev(o.get("broke"), received), ev(o.get("placed"), received));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Ev ev(JsonElement e, long received) {
        if (e == null || !e.isJsonObject()) return null;
        try {
            JsonObject b = e.getAsJsonObject();
            long at = b.has("age") ? received - b.get("age").getAsLong() : b.has("at") ? b.get("at").getAsLong() : received;
            return new Ev(b.get("id").getAsString(), b.get("x").getAsInt(), b.get("y").getAsInt(), b.get("z").getAsInt(), at,
                    b.has("left") ? b.get("left").getAsInt() : -1);
        } catch (RuntimeException x) {
            return null;
        }
    }

    // ==== kinds of blocks ====

    public static String path(String id) { return id == null ? "" : id.substring(id.indexOf(':') + 1); }

    public static boolean isLog(String id) { String p = path(id); return p.endsWith("_log") || p.endsWith("_stem"); }

    public static boolean isLeaves(String id) { return path(id).endsWith("_leaves"); }

    public static boolean isOre(String id) { String p = path(id); return p.endsWith("_ore") || p.equals("ancient_debris"); }

    static final Set<String> STONE = Set.of("stone", "deepslate", "andesite", "diorite", "granite", "tuff", "calcite", "netherrack",
            "blackstone", "basalt", "sandstone", "dripstone_block", "end_stone");

    /** What mining mirrors: ores and natural stone (cobblestone is a built block for the guard, never mirrored). */
    public static boolean mineable(String id) { return isOre(id) || STONE.contains(path(id)); }

    public static boolean isCrop(String id) {
        String p = path(id);
        return p.equals("wheat") || p.equals("carrots") || p.equals("potatoes") || p.equals("beetroots") || p.endsWith("_crop") || p.equals("nether_wart");
    }

    static boolean isAir(String id) { return id != null && (id.endsWith(":air") || id.endsWith(":cave_air")); }

    // ==== the activity ====

    /** The report's own action when it is a known word, else the same guess the companion makes from held + broke/placed. */
    public static Act guess(Report r, long now) {
        Act a = Act.of(r.action());
        if (a != null) return a;
        Ev b = r.broke() != null && now - r.broke().at() <= RECENT_MS ? r.broke() : null;
        Ev p = r.placed() != null && now - r.placed().at() <= RECENT_MS ? r.placed() : null;
        String h = path(r.held());
        if (p != null && (b == null || p.at() >= b.at())) {
            String pp = path(p.id());
            return pp.endsWith("_seeds") || pp.endsWith("_sapling") || pp.equals("carrot") || pp.equals("potato") ? Act.FARMING : Act.BUILDING;
        }
        if (b != null) {
            if (isLog(b.id()) || isLeaves(b.id())) return Act.CHOPPING;
            if (mineable(b.id())) return Act.MINING;
            if (isCrop(b.id())) return Act.FARMING;
            return h.endsWith("_pickaxe") || h.endsWith("_shovel") ? Act.MINING : Act.BUILDING;
        }
        if (h.endsWith("_sword") || h.equals("bow") || h.equals("crossbow") || h.equals("trident")) return Act.FIGHTING;
        return Act.IDLE;
    }

    /**
     * The switch rule: the first activity counts at once; a different one only after it was seen for {@link #SWITCH_MS};
     * idle for {@link #IDLE_STOP_MS} ends the mirroring (the bot only follows) until another activity counts.
     */
    public static final class Switcher {
        private Act current, pending;
        private long pendingSince, idleSince = -1;
        private boolean mirroring = true;

        /** act: what counts now; changed: the activity (or the mirroring) changed with this report. */
        public record Phase(Act act, boolean changed, boolean mirroring) {}

        public Phase update(Act seen, long now) {
            boolean changed = false;
            if (current == null) {
                current = seen;
                changed = true;
            } else if (seen != current) {
                if (pending != seen) {
                    pending = seen;
                    pendingSince = now;
                }
                if (now - pendingSince >= SWITCH_MS) {
                    current = seen;
                    pending = null;
                    changed = true;
                }
            } else {
                pending = null;
            }
            if (current == Act.IDLE) {
                if (idleSince < 0 || changed) idleSince = now;
            } else {
                idleSince = -1;
            }
            boolean m = !(current == Act.IDLE && now - idleSince >= IDLE_STOP_MS);
            if (m != mirroring) changed = true;
            mirroring = m;
            return new Phase(current, changed, mirroring);
        }

        public Act current() { return current; }
    }

    // ==== the world ====

    /** The block id at a spot; null when not loaded. */
    public interface World {
        String id(int x, int y, int z);
    }

    /** A dig box (inclusive). */
    public record Box(int x1, int y1, int z1, int x2, int y2, int z2) {
        public String dig(boolean ores) {
            return "dig " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + (ores ? " ores" : "");
        }
    }

    /**
     * The half-cut trees near the owner's last broken log: columns of that log kind within {@link #TREE_R} of it (from
     * 3 below to {@link #TREE_UP} above) whose top log has leaves next to or above it (a tree, not a log wall), nearest
     * first, at most {@link #TREE_MAX}; each as the trunk's box.
     */
    public static List<Box> halfCutTrees(World w, int bx, int by, int bz, String logId) {
        Map<Long, int[]> cols = new TreeMap<>();         // (x,z) -> {minY, maxY}
        for (int x = bx - TREE_R; x <= bx + TREE_R; x++)
            for (int z = bz - TREE_R; z <= bz + TREE_R; z++)
                for (int y = by - 3; y <= by + TREE_UP; y++) {
                    if (!logId.equals(w.id(x, y, z))) continue;
                    long k = ((long) x << 32) ^ (z & 0xffffffffL);
                    int[] c = cols.computeIfAbsent(k, q -> new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE});
                    c[0] = Math.min(c[0], y);
                    c[1] = Math.max(c[1], y);
                }
        List<int[]> found = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : cols.entrySet()) {
            int x = (int) (e.getKey() >> 32), z = e.getKey().intValue();
            int top = e.getValue()[1];
            if (!leavesNear(w, x, top, z)) continue;
            found.add(new int[]{x, e.getValue()[0], z, top});
        }
        found.sort(Comparator.comparingInt(c -> (c[0] - bx) * (c[0] - bx) + (c[2] - bz) * (c[2] - bz)));
        List<Box> out = new ArrayList<>();
        for (int[] c : found) {
            if (out.size() >= TREE_MAX) break;
            out.add(new Box(c[0], c[1], c[2], c[0], c[3], c[2]));
        }
        return out;
    }

    static boolean leavesNear(World w, int x, int top, int z) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = 0; dy <= 1; dy++)
                    if (isLeaves(w.id(x + dx, top + dy, z + dz))) return true;
        return false;
    }

    /**
     * The blocks of the owner's kind to mine: that id within {@link #MINE_R} of their last broken block, each with air
     * on a side (reachable from the hole they dug), nearest first, at most {@link #MINE_MAX}. Only ores and natural
     * stone ({@link #mineable}); the dig itself still refuses built blocks and checks the areas.
     */
    public static List<Box> mineTargets(World w, int cx, int cy, int cz, String id) {
        if (!mineable(id)) return List.of();
        List<int[]> hits = new ArrayList<>();
        int r = MINE_R;
        for (int x = cx - r; x <= cx + r; x++)
            for (int y = cy - r; y <= cy + r; y++)
                for (int z = cz - r; z <= cz + r; z++) {
                    long d = (long) (x - cx) * (x - cx) + (long) (y - cy) * (y - cy) + (long) (z - cz) * (z - cz);
                    if (d > (long) r * r || !id.equals(w.id(x, y, z))) continue;
                    if (isAir(w.id(x + 1, y, z)) || isAir(w.id(x - 1, y, z)) || isAir(w.id(x, y + 1, z)) || isAir(w.id(x, y - 1, z))
                            || isAir(w.id(x, y, z + 1)) || isAir(w.id(x, y, z - 1)))
                        hits.add(new int[]{x, y, z, (int) d});
                }
        hits.sort(Comparator.comparingInt((int[] h) -> h[3]).thenComparingInt(h -> -h[1]));
        List<Box> out = new ArrayList<>();
        for (int[] h : hits) {
            if (out.size() >= MINE_MAX) break;
            out.add(new Box(h[0], h[1], h[2], h[0], h[1], h[2]));
        }
        return out;
    }

    /** The chain for a list of boxes ("dig ... then dig ..."), or null for none. */
    public static String chain(List<Box> boxes, boolean ores) {
        if (boxes.isEmpty()) return null;
        List<String> s = new ArrayList<>();
        for (Box b : boxes) s.add(b.dig(ores));
        return String.join(" then ", s);
    }

    // ==== give ====

    /**
     * Building: how many of the placed block to hand the owner (0: none). Only for a block placed in the last
     * {@link #RECENT_MS}, when the owner has fewer than {@link #GIVE_LOW} left (unknown counts as low), the bot may give
     * some (giveable: what the C6 keep rules leave, MuleRules.giveable) and the last hand-over is {@link #GIVE_GAP_MS} old.
     */
    public static int give(Ev placed, int giveable, long lastGive, long now) {
        if (placed == null || now - placed.at() > RECENT_MS) return 0;
        if (placed.left() >= GIVE_LOW) return 0;
        if (giveable <= 0 || now - lastGive < GIVE_GAP_MS) return 0;
        return Math.min(GIVE_MAX, giveable);
    }

    // ==== words ====

    public enum Word { ON, OFF, STATUS, ERROR }

    public static final String USAGE = "usage: assist | assist off | assist status";

    public static Word word(String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "", "on", "me" -> Word.ON;
            case "off", "stop" -> Word.OFF;
            case "status" -> Word.STATUS;
            default -> Word.ERROR;
        };
    }

    /** "brain copy on|off|status" (the B1 words) as assist's rest; null when it is not a copy form. */
    public static String fromBrainCopy(String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return switch (t) {
            case "copy on" -> "";
            case "copy off" -> "off";
            case "copy", "copy status" -> "status";
            default -> null;
        };
    }

    /** The whisper on an activity change. what: the block it works on (null: none). */
    public static String text(Act a, boolean mirroring, String what) {
        if (!mirroring) return "assist: you look idle - I just follow you";
        String w = what == null ? null : path(what).replaceFirst("_log$|_stem$", "");
        return switch (a) {
            case CHOPPING -> "assist: cutting " + (w == null ? "trees" : w) + " with you";
            case MINING -> "assist: mining " + (w == null ? "with you" : w + " with you");
            case FARMING -> "assist: farming with you";
            case BUILDING -> "assist: staying close while you build" + (w == null ? "" : " - I hand you " + w + " when you run low");
            case FIGHTING -> "assist: guarding you";
            case IDLE -> "assist: following you and picking up drops";
        };
    }
}
