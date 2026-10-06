package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.memory.Limits;

import java.util.ArrayList;
import java.util.List;

/**
 * "area ...", "protect ...", "unprotect ...", "guard ..." (B1's PM verbs, moved from the bridge in B7a with the
 * same wording). The policy lives in {@code areas.json} now: {areas:[{name,dim,x1,z1,x2,z2,y1?,y2?}], protect:[...],
 * strict, corner1}; every change saves it and hands it to the guard. Plain Java behind {@link Guard}.
 */
public final class PolicyCommands {
    /** 0.21.2: the short form (the old "area add <name> here <r>" still works). */
    public static final String AREA_HINT = "area <name> <r>";
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

        /** 0.21.2: the near-me zone's box now as an area entry ({name, dim, x1, z1, x2, z2, y1, y2}), or null. */
        default JsonObject nearArea() { return null; }

        /** 0.21.2: hands the near-me settings to the guard (off clears the zone at once). */
        default void setNear(boolean on, int r) {}

        /** 0.21.2: "near me: 16 blocks (on, ...)", or null without a guard. */
        default String nearStatus() { return null; }
    }

    /** The words "area" takes as subcommands: an area can't be called one of these. */
    static final java.util.Set<String> AREA_WORDS = java.util.Set.of("list", "show", "add", "corner1", "corner2", "grow", "remove",
            "protect", "unprotect", "near", "here", "confirm", "on", "off", "status");

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
        if (!p.has("near") || !p.get("near").isJsonObject()) {
            JsonObject n = new JsonObject();
            n.addProperty("on", true);
            n.addProperty("r", io.github.mojolowjo.entropybot.guard.NearZone.DEFAULT_R);
            p.add("near", n);
        }
    }

    /** 0.21.2: the near-me zone is on (areas.json "near.on", default true). */
    public boolean nearOn() {
        try { return p.getAsJsonObject("near").get("on").getAsBoolean(); } catch (RuntimeException e) { return true; }
    }

    /** 0.21.2: the near-me radius (areas.json "near.r", default 16; out of bounds = the default). */
    public int nearR() {
        try {
            int r = p.getAsJsonObject("near").get("r").getAsInt();
            return io.github.mojolowjo.entropybot.guard.NearZone.checkRadius(r) == null ? r : io.github.mojolowjo.entropybot.guard.NearZone.DEFAULT_R;
        } catch (RuntimeException e) {
            return io.github.mojolowjo.entropybot.guard.NearZone.DEFAULT_R;
        }
    }

    /**
     * 0.21.2: the areas plus the near-me zone when it is up: what the commands' own area checks read (inAreas, the
     * position watch's gap, the miner's policy JSON). The guard has the same zone ({@code GuardCore.setNearZone}).
     */
    public JsonArray effectiveAreas() {
        JsonObject n = guard.nearArea();
        if (n == null) return areas();
        JsonArray a = areas().deepCopy();
        a.add(n);
        return a;
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
        guard.setNear(nearOn(), nearR());
        String[] r = guard.apply(guardPolicy(), strict());
        return r == null ? null : r[0] + "; mode " + r[1];
    }

    /** True when (x, z) lies inside an area of that dimension (heights aside), as the bridge's inMapArea. */
    public boolean inAreas(String dim, int x, int z) {
        for (JsonElement e : effectiveAreas()) {
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
    /** Package H: a new area past {@link Limits#AREAS} is refused (i = the index of the one replaced, or -1). */
    static String areasFull(JsonArray areas, int i) {
        return Limits.full(i >= 0, areas.size(), Limits.AREAS, "areas", "remove one first (area remove <name> confirm) or grow one (area grow <name> <n>)");
    }

    /** 0.21.2: an area name that is one of "area"'s own words. */
    static String reservedName(String name) {
        return "error: \"" + name + "\" is one of my area words (list, show, add, near, protect, ...) - pick another name, e.g. area " + name + "1 16";
    }

    public String command(String verb, String rest, boolean isOwner, String owner, Pos here, Pos me) {
        List<String> parts = Texts.words(rest);
        String sub = parts.isEmpty() ? "" : parts.get(0).toLowerCase();
        String notOwner = "only " + owner + " can change where I may go and dig";
        JsonArray areas = areas(), protect = protect();
        if (verb.equals("area")) {
            String usage = "usage: area <name> <r> | area protect <name> <r> [down up] | area list | show <name> | near [<r>|on|off|status] | add <name> x1 z1 x2 z2 [y1 y2] | corner1 | corner2 <name> | grow <name> <n> | remove <name> confirm | unprotect <name> confirm";
            String nearLine = guard.nearStatus();
            // "area list 16" and the like: an area called like one of the words - say so instead of listing
            if (parts.size() == 2 && parts.get(1).matches("^\\d+$") && java.util.Set.of("list", "here", "confirm", "on", "off", "status", "corner1", "add", "grow").contains(sub)) {
                return reservedName(sub);
            }
            if (sub.isEmpty() || sub.equals("list")) {
                String tail = nearLine == null ? "" : " | " + nearLine;
                if (areas.isEmpty()) return "no areas set - " + AREA_HINT + tail;
                List<String> out = new ArrayList<>();
                for (JsonElement e : areas) out.add(e.getAsJsonObject().get("name").getAsString() + " (" + boxText(e.getAsJsonObject()) + ")");
                return "areas: " + String.join(", ", out) + tail;
            }
            // 0.21.2: the near-me zone (status for everyone allowed, changes for the owner)
            if (sub.equals("near")) {
                String arg = parts.size() > 1 ? parts.get(1).toLowerCase() : "status";
                if (arg.equals("status")) return nearLine == null ? "near me: " + (nearOn() ? nearR() + " blocks (on)" : "off") + " (mod not loaded)" : nearLine;
                if (!isOwner) return notOwner;
                if (arg.equals("on") || arg.equals("off")) {
                    p.getAsJsonObject("near").addProperty("on", arg.equals("on"));
                    guard.setNear(nearOn(), nearR());
                    saved.run();
                    return "ok: near me " + arg + (arg.equals("on") ? " - I may work within " + nearR() + " blocks of you, wherever you are (protect boxes and the safety rules still hold)"
                            : " - only my areas count now");
                }
                Integer r = arg.matches("^\\d{1,4}$") ? Integer.valueOf(arg) : null;
                String bad = r == null ? "usage: area near [<r>|on|off|status]" : io.github.mojolowjo.entropybot.guard.NearZone.checkRadius(r);
                if (bad != null) return bad.startsWith("usage") ? bad : "error: " + bad;
                JsonObject n = p.getAsJsonObject("near");
                n.addProperty("r", r);
                n.addProperty("on", true);
                guard.setNear(true, r);
                saved.run();
                return "ok: near me " + r + " blocks (on) - I may work within " + r + " blocks of you (" + io.github.mojolowjo.entropybot.guard.NearZone.Y_HALF + " up and down)";
            }
            // 0.21.2: "area protect ..." = protect, "area unprotect ..." = unprotect (the old words stay)
            if (sub.equals("protect")) {
                List<String> w = new ArrayList<>(parts.subList(1, parts.size()));
                if (w.size() >= 2 && w.size() <= 4 && allInts(w.subList(1, w.size()))) w.add(1, "here");
                return command("protect", String.join(" ", w), isOwner, owner, here, me);
            }
            if (sub.equals("unprotect")) return command("unprotect", String.join(" ", parts.subList(1, parts.size())), isOwner, owner, here, me);
            String name = parts.size() > 1 ? parts.get(1).toLowerCase() : "";
            // 0.21.2: "area <name> <r>" (and "area <name> here <r>"): a work area r blocks each way around you
            if (!AREA_WORDS.contains(sub)) {
                boolean shortForm = (parts.size() == 2 && parts.get(1).matches("^-?\\d+$"))
                        || (parts.size() == 3 && parts.get(1).equalsIgnoreCase("here") && parts.get(2).matches("^-?\\d+$"));
                if (!shortForm) return usage;
                if (!isOwner) return notOwner;
                if (!sub.matches(NAME_RE)) return "usage: area <name> <r>  (names: letters, digits, _ and -)";
                return command("area", "add " + sub + " here " + parts.get(parts.size() - 1), true, owner, here, me);
            }
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
                if (AREA_WORDS.contains(name)) return reservedName(name);
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
                String full = areasFull(areas, i);
                if (full != null) return full;
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
                if (AREA_WORDS.contains(name)) return reservedName(name);
                JsonObject c = p.has("corner1") && p.get("corner1").isJsonObject() ? p.getAsJsonObject("corner1") : null;
                if (c == null) return "set corner 1 first: PM \"area corner1\" standing on one corner";
                if (c.has("dim") && !c.get("dim").getAsString().equals(here.dim())) return "corner 1 is in " + c.get("dim").getAsString() + " - PM \"area corner1\" again here";
                JsonObject b = makeBox(name, here.dim(), n(c, "x"), n(c, "z"), here.x(), here.z(), null, null);
                int i = findBox(areas, name);
                String full = areasFull(areas, i);
                if (full != null) return full;
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
                if (protect.isEmpty()) return "no protect boxes - area protect <name> <r> [down up] | protect <name> x1 y1 z1 x2 y2 z2";
                List<String> out = new ArrayList<>();
                for (JsonElement e : protect) out.add(e.getAsJsonObject().get("name").getAsString() + " (" + boxText(e.getAsJsonObject()) + ")");
                return "protect: " + String.join(", ", out);
            }
            if (!isOwner) return notOwner;
            String name = sub;
            if (!name.matches(NAME_RE)) return "usage: area protect <name> <r> [down up] | protect <name> x1 y1 z1 x2 y2 z2  (names: letters, digits, _ and -)";
            JsonObject b;
            if (parts.size() > 1 && parts.get(1).equalsIgnoreCase("here")) {
                Integer r = intOr(parts, 2, null), down = intOr(parts, 3, 8), up = intOr(parts, 4, 16);
                if (r == null || r < 1 || r > 2000 || down == null || down < 0 || up == null || up < 0) {
                    return "usage: area protect " + name + " <r> [down up]  (r = blocks each way; down/up = blocks below/above, default 8 and 16)";
                }
                b = makeBox(name, here.dim(), here.x() - r, here.z() - r, here.x() + r, here.z() + r, here.y() - down, here.y() + up);
            } else if (allInts(parts.subList(1, parts.size())) && parts.size() == 7) {
                int[] v = ints(parts.subList(1, parts.size()));
                b = makeBox(name, me.dim(), v[0], v[2], v[3], v[5], v[1], v[4]);
            } else {
                return "usage: area protect <name> <r> [down up] | protect <name> x1 y1 z1 x2 y2 z2";
            }
            int i = findBox(protect, name);
            // package H: Baritone asks the guard about every protect box for every path node
            String full = Limits.full(i >= 0, protect.size(), Limits.PROTECT, "protect boxes", "unprotect one first (unprotect <name> confirm)");
            if (full != null) return full;
            if (i >= 0) protect.set(i, b);
            else protect.add(b);
            return changed("ok: protect " + name + (i >= 0 ? " replaced" : " added") + " (" + boxText(b) + ")", false);
        }
        if (verb.equals("unprotect")) {
            if (!isOwner) return notOwner;
            int i = findBox(protect, sub);
            if (i < 0) return "I have no protect box called " + (sub.isEmpty() ? "?" : sub) + " (PM \"protect\")";
            if (parts.size() < 2 || !parts.get(1).equalsIgnoreCase("confirm")) return "say \"area unprotect " + sub + " confirm\" to remove it";
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
                        + " | vetoes " + num(g, "vetoes") + ", would-be " + num(g, "wouldVetoes") + " | hooks: " + (hooks.isEmpty() ? "none" : String.join(", ", hooks))
                        + (guard.nearStatus() == null ? "" : " | " + guard.nearStatus());
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
                    return changed("ok: strict mode - the fence is on (" + (areas.isEmpty() ? "no areas yet - " + AREA_HINT + "; walking stays free, digging and building only "
                            + (nearOn() ? "within " + nearR() + " blocks of you" : "nowhere") : "areas: " + names(areas) + (nearOn() ? ", plus " + nearR() + " blocks around you" : "")) + ")", true);
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
