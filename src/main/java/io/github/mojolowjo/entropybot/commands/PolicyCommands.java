package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * "area ...", "protect ...", "unprotect ...", "guard ..." (B1's PM verbs, moved from the bridge in B7a with the
 * same wording). The policy lives in {@code areas.json} now: {areas:[{name,dim,x1,z1,x2,z2,y1?,y2?}], protect:[...],
 * strict, corner1}; every change saves it and hands it to the guard. Plain Java behind {@link Guard}.
 */
public final class PolicyCommands {
    public static final String AREA_HINT = "area add <name> here <r>";
    static final String NAME_RE = "^[a-z0-9_-]{1,24}$";
    static final String DEFAULT_DIM = "minecraft:overworld";

    /** A spot: block coordinates and dimension. */
    public record Pos(int x, int y, int z, String dim) {}

    /** What the commands need from the guard. */
    public interface Guard {
        /** Pushes {areas, protect} and the mode; [setPolicy reply, mode reply], or null without a guard. */
        String[] apply(JsonObject policy, boolean strict);

        /** BotAPI.guard() as JSON, or null. */
        JsonObject status();

        /** The last vetoes as a JSON array text. */
        String vetoes(int max);

        String check(String dim, int x, int y, int z, String action);
    }

    private final JsonObject p;
    private final Guard guard;
    private final Runnable saved;

    public PolicyCommands(JsonObject policy, Guard guard, Runnable saved) {
        this.p = policy;
        this.guard = guard;
        this.saved = saved;
        if (!p.has("areas") || !p.get("areas").isJsonArray()) p.add("areas", new JsonArray());
        if (!p.has("protect") || !p.get("protect").isJsonArray()) p.add("protect", new JsonArray());
        if (!p.has("strict")) p.addProperty("strict", false);
    }

    public JsonArray areas() { return p.getAsJsonArray("areas"); }

    public JsonArray protect() { return p.getAsJsonArray("protect"); }

    public boolean strict() { return p.has("strict") && p.get("strict").getAsBoolean(); }

    /** {areas, protect} for the guard. */
    public JsonObject guardPolicy() {
        JsonObject o = new JsonObject();
        o.add("areas", areas().deepCopy());
        o.add("protect", protect().deepCopy());
        return o;
    }

    /** Pushes the policy to the guard (at load). The two replies joined, or null. */
    public String apply() {
        String[] r = guard.apply(guardPolicy(), strict());
        return r == null ? null : r[0] + "; mode " + r[1];
    }

    /** True when (x, z) lies inside an area of that dimension (heights aside), as the bridge's inMapArea. */
    public boolean inAreas(String dim, int x, int z) {
        for (JsonElement e : areas()) {
            JsonObject a = e.getAsJsonObject();
            if (dimOf(a).equals(dim) && x >= n(a, "x1") && x <= n(a, "x2") && z >= n(a, "z1") && z <= n(a, "z2")) return true;
        }
        return false;
    }

    static String dimOf(JsonObject b) {
        return b.has("dim") && !b.get("dim").isJsonNull() ? b.get("dim").getAsString() : DEFAULT_DIM;
    }

    static int n(JsonObject o, String k) { return o.get(k).getAsInt(); }

    static boolean hasY(JsonObject b) { return b.has("y1") && b.has("y2") && !b.get("y1").isJsonNull() && !b.get("y2").isJsonNull(); }

    /** "-272 -64 to 223 431" (plus ", y 10..60" when the box has a height). */
    static String boxText(JsonObject b) {
        return n(b, "x1") + " " + n(b, "z1") + " to " + n(b, "x2") + " " + n(b, "z2") + (hasY(b) ? ", y " + n(b, "y1") + ".." + n(b, "y2") : "");
    }

    static int findBox(JsonArray list, String name) {
        for (int i = 0; i < list.size(); i++) {
            JsonObject b = list.get(i).getAsJsonObject();
            if (b.has("name") && name.equals(b.get("name").getAsString())) return i;
        }
        return -1;
    }

    static JsonObject makeBox(String name, String dim, int x1, int z1, int x2, int z2, Integer y1, Integer y2) {
        JsonObject b = new JsonObject();
        b.addProperty("name", name);
        b.addProperty("dim", dim);
        b.addProperty("x1", Math.min(x1, x2));
        b.addProperty("z1", Math.min(z1, z2));
        b.addProperty("x2", Math.max(x1, x2));
        b.addProperty("z2", Math.max(z1, z2));
        if (y1 != null && y2 != null) {
            b.addProperty("y1", Math.min(y1, y2));
            b.addProperty("y2", Math.max(y1, y2));
        }
        return b;
    }

    static int boxGap(JsonObject b, int x, int y, int z) {
        int g = Math.max(Math.max(n(b, "x1") - x, 0), Math.max(x - n(b, "x2"), Math.max(n(b, "z1") - z, z - n(b, "z2"))));
        if (hasY(b)) g = Math.max(g, Math.max(n(b, "y1") - y, y - n(b, "y2")));
        return g;
    }

    private String names(JsonArray l) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : l) out.add(e.getAsJsonObject().has("name") ? e.getAsJsonObject().get("name").getAsString() : "box");
        return out.isEmpty() ? "none" : String.join(", ", out);
    }

    private String changed(String msg, boolean modeReply) {
        saved.run();
        String[] r = guard.apply(guardPolicy(), strict());
        if (r == null) return msg + " (mod not loaded: the fence is off)";
        return msg + " | mod: " + (modeReply ? r[1] : r[0]);
    }

    private static boolean allInts(List<String> l) {
        if (l.isEmpty()) return false;
        for (String s : l) if (!s.matches("^-?\\d+$")) return false;
        return true;
    }

    private static int[] ints(List<String> l) {
        int[] out = new int[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = Integer.parseInt(l.get(i));
        return out;
    }

    private static Integer intOr(List<String> parts, int i, Integer def) {
        if (parts.size() <= i) return def;
        try { return Integer.parseInt(parts.get(i)); } catch (NumberFormatException e) { return null; }
    }

    /**
     * @param here  the owner's spot when the bot can see them, else the bot's ("here", as "mark" does)
     * @param me    the bot's spot
     */
    public String command(String verb, String rest, boolean isOwner, String owner, Pos here, Pos me) {
        List<String> parts = Texts.words(rest);
        String sub = parts.isEmpty() ? "" : parts.get(0).toLowerCase();
        String notOwner = "only " + owner + " can change where I may go and dig";
        JsonArray areas = areas(), protect = protect();
        if (verb.equals("area")) {
            String usage = "usage: area list | show <name> | add <name> here <r> | add <name> x1 z1 x2 z2 [y1 y2] | corner1 | corner2 <name> | grow <name> <n> | remove <name> confirm";
            if (sub.isEmpty() || sub.equals("list")) {
                if (areas.isEmpty()) return "no areas set - " + AREA_HINT;
                List<String> out = new ArrayList<>();
                for (JsonElement e : areas) out.add(e.getAsJsonObject().get("name").getAsString() + " (" + boxText(e.getAsJsonObject()) + ")");
                return "areas: " + String.join(", ", out);
            }
            String name = parts.size() > 1 ? parts.get(1).toLowerCase() : "";
            if (sub.equals("show")) {
                int i = findBox(areas, name);
                if (i < 0) return "I have no area called " + (name.isEmpty() ? "?" : name) + " (PM \"area list\")";
                JsonObject b = areas.get(i).getAsJsonObject();
                String r = name + ": " + boxText(b) + ", " + (n(b, "x2") - n(b, "x1") + 1) + "x" + (n(b, "z2") - n(b, "z1") + 1);
                if (!dimOf(b).equals(me.dim()) || boxGap(b, me.x(), me.y(), me.z()) > 0) return r + " - I'm outside it";
                return r + " - edges: west " + (me.x() - n(b, "x1")) + ", east " + (n(b, "x2") - me.x()) + ", north " + (me.z() - n(b, "z1")) + ", south " + (n(b, "z2") - me.z());
            }
            if (!isOwner) return notOwner;
            if (sub.equals("add")) {
                if (!name.matches(NAME_RE)) return usage + " (names: letters, digits, _ and -)";
                JsonObject b;
                if (parts.size() > 2 && parts.get(2).equalsIgnoreCase("here")) {
                    Integer r = intOr(parts, 3, null);
                    if (r == null || r < 1 || r > 2000) return "usage: area add " + name + " here <r>  (r = blocks each way, 1..2000)";
                    b = makeBox(name, here.dim(), here.x() - r, here.z() - r, here.x() + r, here.z() + r, null, null);
                } else if (allInts(parts.subList(Math.min(2, parts.size()), parts.size())) && (parts.size() == 6 || parts.size() == 8)) {
                    int[] v = ints(parts.subList(2, parts.size()));
                    b = makeBox(name, me.dim(), v[0], v[1], v[2], v[3], parts.size() == 8 ? v[4] : null, parts.size() == 8 ? v[5] : null);
                } else {
                    return usage;
                }
                int i = findBox(areas, name);
                if (i >= 0) areas.set(i, b);
                else areas.add(b);
                return changed("ok: area " + name + (i >= 0 ? " replaced" : " added") + " (" + boxText(b) + ")", false);
            }
            if (sub.equals("corner1")) {
                JsonObject c = new JsonObject();
                c.addProperty("x", here.x());
                c.addProperty("y", here.y());
                c.addProperty("z", here.z());
                c.addProperty("dim", here.dim());
                p.add("corner1", c);
                saved.run();
                return "corner 1 = " + here.x() + " " + here.y() + " " + here.z() + " - stand on the opposite corner and PM \"area corner2 <name>\"";
            }
            if (sub.equals("corner2")) {
                if (!name.matches(NAME_RE)) return "usage: area corner2 <name>  (names: letters, digits, _ and -)";
                JsonObject c = p.has("corner1") && p.get("corner1").isJsonObject() ? p.getAsJsonObject("corner1") : null;
                if (c == null) return "set corner 1 first: PM \"area corner1\" standing on one corner";
                if (c.has("dim") && !c.get("dim").getAsString().equals(here.dim())) return "corner 1 is in " + c.get("dim").getAsString() + " - PM \"area corner1\" again here";
                JsonObject b = makeBox(name, here.dim(), n(c, "x"), n(c, "z"), here.x(), here.z(), null, null);
                int i = findBox(areas, name);
                if (i >= 0) areas.set(i, b);
                else areas.add(b);
                p.add("corner1", JsonNull.INSTANCE);
                return changed("ok: area " + name + (i >= 0 ? " replaced" : " added") + " (" + boxText(b) + ")", false);
            }
            if (sub.equals("grow")) {
                int i = findBox(areas, name);
                if (i < 0) return "I have no area called " + (name.isEmpty() ? "?" : name) + " (PM \"area list\")";
                Integer g = intOr(parts, 2, null);
                if (g == null || g < 1 || g > 2000) return "usage: area grow " + name + " <blocks>  (1..2000)";
                JsonObject b = areas.get(i).getAsJsonObject();
                b.addProperty("x1", n(b, "x1") - g);
                b.addProperty("z1", n(b, "z1") - g);
                b.addProperty("x2", n(b, "x2") + g);
                b.addProperty("z2", n(b, "z2") + g);
                return changed("ok: area " + name + " grown by " + g + " (" + boxText(b) + ")", false);
            }
            if (sub.equals("remove")) {
                int i = findBox(areas, name);
                if (i < 0) return "I have no area called " + (name.isEmpty() ? "?" : name) + " (PM \"area list\")";
                if (parts.size() < 3 || !parts.get(2).equalsIgnoreCase("confirm")) return "say \"area remove " + name + " confirm\" to remove it";
                areas.remove(i);
                return changed("ok: area " + name + " removed", false);
            }
            return usage;
        }
        if (verb.equals("protect")) {
            if (sub.isEmpty() || sub.equals("list")) {
                if (protect.isEmpty()) return "no protect boxes - protect <name> here <r> [down up] | protect <name> x1 y1 z1 x2 y2 z2";
                List<String> out = new ArrayList<>();
                for (JsonElement e : protect) out.add(e.getAsJsonObject().get("name").getAsString() + " (" + boxText(e.getAsJsonObject()) + ")");
                return "protect: " + String.join(", ", out);
            }
            if (!isOwner) return notOwner;
            String name = sub;
            if (!name.matches(NAME_RE)) return "usage: protect <name> here <r> [down up] | protect <name> x1 y1 z1 x2 y2 z2  (names: letters, digits, _ and -)";
            JsonObject b;
            if (parts.size() > 1 && parts.get(1).equalsIgnoreCase("here")) {
                Integer r = intOr(parts, 2, null), down = intOr(parts, 3, 8), up = intOr(parts, 4, 16);
                if (r == null || r < 1 || r > 2000 || down == null || down < 0 || up == null || up < 0) {
                    return "usage: protect " + name + " here <r> [down up]  (r = blocks each way; down/up = blocks below/above, default 8 and 16)";
                }
                b = makeBox(name, here.dim(), here.x() - r, here.z() - r, here.x() + r, here.z() + r, here.y() - down, here.y() + up);
            } else if (allInts(parts.subList(1, parts.size())) && parts.size() == 7) {
                int[] v = ints(parts.subList(1, parts.size()));
                b = makeBox(name, me.dim(), v[0], v[2], v[3], v[5], v[1], v[4]);
            } else {
                return "usage: protect <name> here <r> [down up] | protect <name> x1 y1 z1 x2 y2 z2";
            }
            int i = findBox(protect, name);
            if (i >= 0) protect.set(i, b);
            else protect.add(b);
            return changed("ok: protect " + name + (i >= 0 ? " replaced" : " added") + " (" + boxText(b) + ")", false);
        }
        if (verb.equals("unprotect")) {
            if (!isOwner) return notOwner;
            int i = findBox(protect, sub);
            if (i < 0) return "I have no protect box called " + (sub.isEmpty() ? "?" : sub) + " (PM \"protect\")";
            if (parts.size() < 2 || !parts.get(1).equalsIgnoreCase("confirm")) return "say \"unprotect " + sub + " confirm\" to remove it";
            protect.remove(i);
            return changed("ok: protect box " + sub + " removed", false);
        }
        if (verb.equals("guard")) {
            if (sub.isEmpty()) {
                JsonObject g = guard.status();
                if (g == null) return "guard: mod not loaded (the fence is off) | areas: " + names(areas) + " | protect: " + names(protect) + " | mode wanted: " + (strict() ? "strict" : "log");
                String mode = g.has("mode") ? g.get("mode").getAsString() : "?";
                List<String> tasks = new ArrayList<>();
                if (g.has("leases")) for (JsonElement e : g.getAsJsonArray("leases")) {
                    JsonObject l = e.getAsJsonObject();
                    tasks.add(l.has("task") ? l.get("task").getAsString() : "job");
                }
                List<String> hooks = new ArrayList<>();
                if (g.has("click") && g.get("click").getAsBoolean()) hooks.add("click");
                if (g.has("astar") && g.get("astar").getAsBoolean()) hooks.add("astar");
                return "guard: " + mode + " mode" + (!mode.equals(strict() ? "strict" : "log") ? " (my notes say " + (strict() ? "strict" : "log") + ")" : "")
                        + " | areas: " + names(g.has("areas") ? g.getAsJsonArray("areas") : new JsonArray()) + " | protect: " + names(g.has("protect") ? g.getAsJsonArray("protect") : new JsonArray())
                        + " | leases: " + tasks.size() + (tasks.isEmpty() ? "" : " (" + String.join("; ", tasks.subList(0, Math.min(3, tasks.size()))) + ")")
                        + " | vetoes " + num(g, "vetoes") + ", would-be " + num(g, "wouldVetoes") + " | hooks: " + (hooks.isEmpty() ? "none" : String.join(", ", hooks));
            }
            if (sub.equals("vetoes")) {
                JsonArray list;
                try {
                    list = com.google.gson.JsonParser.parseString(guard.vetoes(5)).getAsJsonArray();
                } catch (RuntimeException e) {
                    return "guard: vetoes unreadable (" + e.getMessage() + ")";
                }
                if (list.isEmpty()) return "no vetoes";
                List<String> out = new ArrayList<>();
                for (JsonElement e : list) {
                    JsonObject v = e.getAsJsonObject();
                    out.add(num(v, "tick") + " " + v.get("action").getAsString() + " " + v.get("pos").getAsString() + ": " + v.get("reason").getAsString());
                }
                return "vetoes: " + String.join(" | ", out);
            }
            if (!isOwner) return notOwner;
            if (sub.equals("check")) {
                String m = parts.size() > 4 ? parts.get(4).toLowerCase() : "";
                if (parts.size() < 4 || !allInts(parts.subList(1, 4)) || !m.matches("^(break|place|go)$")) return "usage: guard check x y z break|place|go";
                int[] v = ints(parts.subList(1, 4));
                return guard.check(me.dim(), v[0], v[1], v[2], m);
            }
            if (sub.equals("mode")) {
                String m = parts.size() > 1 ? parts.get(1).toLowerCase() : "";
                if (m.equals("strict")) {
                    p.addProperty("strict", true);
                    return changed("ok: strict mode - the fence is on (" + (areas.isEmpty() ? "no areas yet - " + AREA_HINT : "areas: " + names(areas)) + ")", true);
                }
                if (m.equals("log")) {
                    if (parts.size() < 3 || !parts.get(2).equalsIgnoreCase("confirm")) {
                        return "say \"guard mode log confirm\" to switch the fence off (the guard then only logs what it would refuse; the floor stays)";
                    }
                    p.addProperty("strict", false);
                    return changed("ok: log mode - the fence is off, the guard only logs (the floor stays)", true);
                }
                return "usage: guard mode strict | guard mode log confirm";
            }
            return "usage: guard | guard vetoes | guard check x y z break|place|go | guard mode strict | guard mode log confirm";
        }
        return "unknown command \"" + verb + "\"";
    }

    private static long num(JsonObject o, String k) {
        try { return o.has(k) ? (long) o.get(k).getAsDouble() : 0; } catch (RuntimeException e) { return 0; }
    }
}
