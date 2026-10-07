package io.github.mojolowjo.entropybot.brain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * B1 {@code brain copy} (docs/BRAIN_LOOP.md "brain copy"): from the companion's reports in owner.json (position,
 * {@code held} item since companion v2, and {@code broke: {id, x, y, z, at}} when a companion sends it) guess what the
 * owner is doing: logs broken or an axe held = chopping, ore broken = mining that ore, crops broken or a hoe held =
 * farming; placing = building (never copied). Stops after {@code copyIdleS} without a change. Pure.
 */
public final class CopyRules {
    private CopyRules() {}

    public static final long FRESH_MS = 10_000;

    public enum Kind { NONE, CHOP, MINE, FARM, BUILD }

    /** What the bot would copy: the chain (null: nothing), and why. */
    public record Activity(Kind kind, String block, String chain, String why) {
        static Activity none(String why) { return new Activity(Kind.NONE, null, null, why); }
    }

    /** One owner.json: block position, when the laptop got it, the held item ("" none), the last broken block (null none). */
    public record Report(String name, int x, int y, int z, String dim, long received, String held, String broke, long brokeAt) {}

    public static Report parse(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String held = o.has("held") && o.get("held").isJsonPrimitive() ? o.get("held").getAsString() : "";
            String broke = null;
            long brokeAt = -1;
            if (o.has("broke") && o.get("broke").isJsonObject()) {
                JsonObject b = o.getAsJsonObject("broke");
                broke = b.get("id").getAsString();
                brokeAt = b.has("at") ? b.get("at").getAsLong() : o.get("received").getAsLong();
            }
            return new Report(o.get("name").getAsString(), (int) Math.floor(o.get("x").getAsDouble()), (int) Math.floor(o.get("y").getAsDouble()),
                    (int) Math.floor(o.get("z").getAsDouble()), o.get("dim").getAsString(), o.get("received").getAsLong(), held, broke, brokeAt);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String path(String id) { return id == null ? "" : id.substring(id.indexOf(':') + 1); }

    public static boolean isLog(String id) { String p = path(id); return p.endsWith("_log") || p.endsWith("_stem") || p.endsWith("_wood"); }

    public static boolean isOre(String id) { String p = path(id); return p.endsWith("_ore") || p.equals("ancient_debris"); }

    public static boolean isCrop(String id) {
        String p = path(id);
        return p.equals("wheat") || p.equals("carrots") || p.equals("potatoes") || p.equals("beetroots") || p.endsWith("_crop") || p.equals("nether_wart");
    }

    /** Remembers the last report and when the owner last did something. */
    public static final class Tracker {
        private Report last;
        private long activeAt = -1;

        public Activity update(Report r, String owner, long now, int idleS) {
            if (r == null || owner == null || !owner.equalsIgnoreCase(r.name()) || now - r.received() > FRESH_MS)
                return Activity.none("no owner data (the companion mod isn't reporting)");
            if (last == null || moved(last, r) || !r.held().equals(last.held()) || r.brokeAt() != last.brokeAt()) activeAt = now;
            last = r;
            if (now - activeAt > idleS * 1000L) return Activity.none("you have been still for " + idleS + " s");
            if (r.broke() != null && now - r.brokeAt() <= idleS * 1000L) {
                String b = r.broke();
                if (isLog(b)) return new Activity(Kind.CHOP, b, "cut 16 " + path(b), "you are chopping " + path(b));
                if (isOre(b)) return new Activity(Kind.MINE, b, "mine " + path(b) + " 8", "you are mining " + path(b));
                if (isCrop(b)) return new Activity(Kind.FARM, b, "farm", "you are harvesting " + path(b));
            }
            String h = path(r.held());
            if (h.endsWith("_axe")) return new Activity(Kind.CHOP, null, "cut 16", "you hold an axe");
            if (h.endsWith("_hoe")) return new Activity(Kind.FARM, null, "farm", "you hold a hoe");
            if (h.endsWith("_pickaxe")) return Activity.none("you hold a pickaxe, but no ore was reported broken");
            if (!h.isEmpty() && r.broke() == null) return new Activity(Kind.BUILD, null, null, "building is not copied");
            return Activity.none("nothing to copy");
        }

        static boolean moved(Report a, Report b) { return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z()) >= 1; }
    }
}
