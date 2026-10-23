package io.github.mojolowjo.entropybot.assist;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import io.github.mojolowjo.entropybot.Quiet;

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
 * read by AA (OwnerReport, the help desire and the owner inputs). The old assist verb and its game side are
 * gone (AA is the only driver).
 * Loader notes: no Minecraft or loader types here.
 */
public final class AssistRules {
    private AssistRules() {}

    public static long freshMs() { return Tuning.l("assistRules.freshMs"); }  // an older report is no report
    public static long recentMs() { return Tuning.l("assistRules.recentMs"); }  // a broke/placed counts this long

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
            Quiet.warn("AssistRules.parse failed, carrying on with the fallback", e);
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
            Quiet.warn("AssistRules.ev failed, carrying on with the fallback", x);
            return null;
        }
    }

    // ==== kinds of blocks ====

    public static String path(String id) { return id == null ? "" : id.substring(id.indexOf(':') + 1); }

    public static boolean isLog(String id) { return io.github.mojolowjo.entropybot.aa.brain.ItemWorth.hasTag(id, "logs"); }

    public static boolean isLeaves(String id) { return io.github.mojolowjo.entropybot.aa.brain.ItemWorth.hasTag(id, "leaves"); }

    public static boolean isOre(String id) { return io.github.mojolowjo.entropybot.aa.brain.ItemWorth.hasTag(id, "c:ores"); }

    /** What mining mirrors: ores and natural stone (cobblestone is a built block for the guard, never mirrored). */
    public static boolean mineable(String id) { return isOre(id) || io.github.mojolowjo.entropybot.aa.brain.ItemWorth.stoneLike(id); }

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
        Ev b = r.broke() != null && now - r.broke().at() <= recentMs() ? r.broke() : null;
        Ev p = r.placed() != null && now - r.placed().at() <= recentMs() ? r.placed() : null;
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

    // ==== the world ====

    // ==== give ====

    // ==== words ====

    public static final String USAGE = "usage: assist | assist off | assist status";

}
