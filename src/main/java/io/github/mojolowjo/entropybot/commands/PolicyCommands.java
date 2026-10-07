package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.guard.AreaType;
import io.github.mojolowjo.entropybot.memory.Limits;

import java.util.ArrayList;
import java.util.List;

/**
 * "area ..." and "fence ..." (V1a, 0.22.0: areas with a type; the old protect/unprotect/guard words only answer with the
 * new form, {@link OldWords}). The policy lives in {@code areas.json}: {version: 2, areas:[{name,dim,x1,z1,x2,z2,y1?,y2?,
 * type?}], protect:[... type "safe"], strict, near}. The owner sees one list; the file keeps the safe areas apart (the
 * guard's lock-free protect check and every reader of "protect" stay as they were). Plain Java behind {@link Guard}.
 */
public final class PolicyCommands {
    /** V1a: the short form. */
    public static final String AREA_HINT = "area here <r> <name>";
    static final String NAME_RE = "^[a-z0-9_-]{1,24}$";
    static final String DEFAULT_DIM = "minecraft:overworld";
    /** areas.json's format: 2 = typed areas (V1a). */
    public static final int VERSION = 2;

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

    /** The words "area" takes as subcommands (and the old ones, so an old line never makes an area): no area name. */
    static final java.util.Set<String> AREA_WORDS = java.util.Set.of("list", "show", "add", "corner1", "corner2", "grow", "remove",
            "protect", "unprotect", "near", "here", "confirm", "on", "off", "status", "change", "del", "name", "type",
            "neutral", "destroy", "main", "safe", "all", "me", "candidates");

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

    /** The work areas (every type but safe). */
    public JsonArray areas() { return p.getAsJsonArray("areas"); }

    /** The safe areas (the old protect boxes). */
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
            if (!dimOf(a).equals(dim)) continue;
            if (a.has("round")) {                                            // 0.21.2: the near-me circle (Box knows the shape)
                if (io.github.mojolowjo.entropybot.guard.Box.fromJson(a, DEFAULT_DIM).columnIn(x, z)) return true;
            } else if (x >= n(a, "x1") && x <= n(a, "x2") && z >= n(a, "z1") && z <= n(a, "z2")) return true;
        }
        return false;
    }

    /** V1a: an area of any type by name (case-insensitive), or null. */
    public JsonObject findArea(String name) {
        int i = findBox(areas(), name);
        if (i >= 0) return areas().get(i).getAsJsonObject();
        i = findBox(protect(), name);
        return i >= 0 ? protect().get(i).getAsJsonObject() : null;
    }

    static String dimOf(JsonObject b) {
        return b.has("dim") && !b.get("dim").isJsonNull() ? b.get("dim").getAsString() : DEFAULT_DIM;
    }

    static int n(JsonObject o, String k) { return o.get(k).getAsInt(); }

    static boolean hasY(JsonObject b) { return b.has("y1") && b.has("y2") && !b.get("y1").isJsonNull() && !b.get("y2").isJsonNull(); }

    /** V1a: the type of an area entry (absent = neutral). */
    public static AreaType typeOf(JsonObject b) {
        return AreaType.orNeutral(b.has("type") && b.get("type").isJsonPrimitive() ? b.get("type").getAsString() : null);
    }

    /** "-272 -64 to 223 431" (plus ", y 10..60" when the box has a height). */
    static String boxText(JsonObject b) {
        if (b.has("round") && b.get("round").isJsonObject()) {
            JsonObject rd = b.getAsJsonObject("round");
            return "a circle of " + n(rd, "r") + " around " + n(rd, "cx") + " " + n(rd, "cz") + (hasY(b) ? ", y " + n(b, "y1") + ".." + n(b, "y2") : "");
        }
        return n(b, "x1") + " " + n(b, "z1") + " to " + n(b, "x2") + " " + n(b, "z2") + (hasY(b) ? ", y " + n(b, "y1") + ".." + n(b, "y2") : "");
    }

    /** "map (main, blue: -272 -64 to 223 431)". */
    static String areaText(JsonObject b) {
        AreaType t = typeOf(b);
        return nameOf(b) + " (" + t.word() + ", " + t.colour + ": " + boxText(b) + ")";
    }

    static String nameOf(JsonObject b) {
        return b.has("name") && !b.get("name").isJsonNull() ? b.get("name").getAsString() : "box";
    }

    static int findBox(JsonArray list, String name) {
        for (int i = 0; i < list.size(); i++) {
            JsonObject b = list.get(i).getAsJsonObject();
            if (b.has("name") && name.equalsIgnoreCase(b.get("name").getAsString())) return i;
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
        if (b.has("round")) return io.github.mojolowjo.entropybot.guard.Box.fromJson(b, DEFAULT_DIM).gap(x, y, z);     // 0.21.2: the circle
        int g = Math.max(Math.max(n(b, "x1") - x, 0), Math.max(x - n(b, "x2"), Math.max(n(b, "z1") - z, z - n(b, "z2"))));
        if (hasY(b)) g = Math.max(g, Math.max(n(b, "y1") - y, y - n(b, "y2")));
        return g;
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

    private static boolean isInt(String s) { return s.matches("^-?\\d+$"); }

    /** Package H: a new area past its cap is refused (i = the index of the one replaced, or -1). */
    static String areasFull(JsonArray areas, int i) {
        return Limits.full(i >= 0, areas.size(), Limits.AREAS, "areas", "remove one first (area del <name> confirm)");
    }

    /** An area name that is one of "area"'s own words or a type word. */
    static String reservedName(String name) {
        return "error: \"" + name + "\" is one of my area words (list, show, here, change, del, near, a type...) - pick another name, e.g. " + name + "1";
    }

    /** V1a: the name rules ({@code AreaNames}): letters, digits, _ and -, 1-24, not an area word or a type. Null when fine. */
    static String badName(String name) {
        if (name == null || !name.matches(NAME_RE)) return "error: an area name is 1-24 letters, digits, _ and - (e.g. farm, alex_house)";
        if (AREA_WORDS.contains(name)) return reservedName(name);
        return null;
    }

    /** One parsed "area" creation line. error != null: the answer. */
    record NewArea(String name, AreaType type, int[] xz, Integer r, Integer y1, Integer y2, String error) {
        static NewArea err(String e) { return new NewArea(null, null, null, null, null, null, e); }
    }

    static final String USAGE = "usage: area here <r> <name> [type] [down up] | area x z x2 z2 <name> [type] [y1 y2] | area change name <name> <new> "
            + "| area change type <name> <type> | area del <name> confirm | area list | area show <name> | area near [<r>|on|off|status]  (types: neutral, destroy, main, safe)";

    /**
     * Parses "here &lt;r&gt; &lt;name&gt; [type] [down up]" (r != null; y1/y2 = down/up) and "x z x2 z2 &lt;name&gt; [type]
     * [y1 y2]" (xz != null). Pure, for {@code AreaNamesTest}.
     */
    static NewArea parseNew(List<String> w) {
        int i;
        int[] xz = null;
        Integer r = null;
        if (!w.isEmpty() && w.get(0).equalsIgnoreCase("here")) {
            if (w.size() < 3 || !w.get(1).matches("^\\d{1,4}$")) return NewArea.err("usage: area here <r> <name> [type] [down up]  (r = blocks each way, 1..2000)");
            r = Integer.parseInt(w.get(1));
            if (r < 1 || r > 2000) return NewArea.err("error: r is 1..2000 blocks each way");
            i = 2;
        } else if (w.size() >= 5 && allInts(w.subList(0, 4))) {
            xz = new int[4];
            for (int k = 0; k < 4; k++) xz[k] = Integer.parseInt(w.get(k));
            i = 4;
        } else {
            return NewArea.err(USAGE);
        }
        String name = w.get(i).toLowerCase();
        String bad = badName(name);
        if (bad != null) return NewArea.err(bad);
        i++;
        AreaType type = AreaType.NEUTRAL;
        if (i < w.size() && !isInt(w.get(i))) {
            type = AreaType.of(w.get(i));
            if (type == null) return NewArea.err(AreaType.BAD);
            i++;
        }
        Integer y1 = null, y2 = null;
        int left = w.size() - i;
        if (left == 2 && allInts(w.subList(i, w.size()))) {
            y1 = Integer.parseInt(w.get(i));
            y2 = Integer.parseInt(w.get(i + 1));
            if (r != null && (y1 < 0 || y2 < 0)) return NewArea.err("error: down and up are blocks below and above you (0 or more)");
        } else if (left != 0) {
            return NewArea.err(r != null ? "usage: area here <r> <name> [type] [down up]" : "usage: area x z x2 z2 <name> [type] [y1 y2]");
        }
        return new NewArea(name, type, xz, r, y1, y2, null);
    }

    /** The area entry for a parsed line (here = the owner's spot or the bot's; a safe "here" area defaults to 8 down, 16 up). */
    static JsonObject build(NewArea a, Pos here, Pos me) {
        JsonObject b;
        if (a.r() != null) {
            Integer lo = null, hi = null;
            if (a.y1() != null) { lo = here.y() - a.y1(); hi = here.y() + a.y2(); }
            else if (a.type() == AreaType.SAFE) { lo = here.y() - 8; hi = here.y() + 16; }
            b = makeBox(a.name(), here.dim(), here.x() - a.r(), here.z() - a.r(), here.x() + a.r(), here.z() + a.r(), lo, hi);
        } else {
            int[] v = a.xz();
            b = makeBox(a.name(), me.dim(), v[0], v[1], v[2], v[3], a.y1(), a.y2());
        }
        if (a.type() != AreaType.NEUTRAL) b.addProperty("type", a.type().word());
        return b;
    }

    /** Removes an area of any type by name; true when one was there. */
    private boolean removeNamed(String name) {
        int i = findBox(areas(), name);
        if (i >= 0) { areas().remove(i); return true; }
        i = findBox(protect(), name);
        if (i >= 0) { protect().remove(i); return true; }
        return false;
    }

    /** Puts an entry in the list its type belongs to (safe: protect). Null when fine, else the cap's error. */
    private String put(JsonObject b) {
        String name = nameOf(b);
        boolean safe = typeOf(b) == AreaType.SAFE;
        JsonArray list = safe ? protect() : areas();
        boolean replacing = findArea(name) != null;
        int i = findBox(list, name);
        String full = safe ? Limits.full(i >= 0, list.size(), Limits.PROTECT, "safe areas", "delete one first (area del <name> confirm)") : areasFull(list, i);
        if (full != null) return full;
        if (replacing) removeNamed(name);
        list.add(b);
        return null;
    }

    /** V1a: "fence check" names where the spot lies: " (in mine, destroy)" / " (outside every area)". */
    String whereText(String dim, int x, int y, int z) {
        for (JsonArray l : List.of(protect(), areas())) {
            for (JsonElement e : l) {
                JsonObject b = e.getAsJsonObject();
                if (dimOf(b).equals(dim) && boxGap(b, x, y, z) == 0) return " (in " + nameOf(b) + ", " + typeOf(b).word() + ")";
            }
        }
        JsonObject n = guard.nearArea();
        if (n != null && dimOf(n).equals(dim) && boxGap(n, x, y, z) == 0) return " (within " + nearR() + " blocks of you)";
        return " (outside every area)";
    }

    /** "area list": one list, every type, with the colour word. */
    String listText() {
        String nearLine = guard.nearStatus();
        String tail = nearLine == null ? "" : " | " + nearLine;
        if (areas().isEmpty() && protect().isEmpty()) return "no areas set - " + AREA_HINT + " [type]" + tail;
        List<String> out = new ArrayList<>();
        for (JsonElement e : areas()) out.add(areaText(e.getAsJsonObject()));
        for (JsonElement e : protect()) out.add(areaText(e.getAsJsonObject()));
        return "areas: " + String.join(", ", out) + tail;
    }

    /**
     * @param here  the owner's spot when the bot can see them (or the companion's fix), else the bot's
     * @param me    the bot's spot
     */
    public String command(String verb, String rest, boolean isOwner, String owner, Pos here, Pos me) {
        List<String> parts = Texts.words(rest);
        String sub = parts.isEmpty() ? "" : parts.get(0).toLowerCase();
        String notOwner = "only " + owner + " can change where I may go and dig";
        // the cut words answer with the new form and do nothing else
        String old = OldWords.hint(verb, rest);
        if (old != null) return old;
        if (verb.equals("area")) {
            String nearLine = guard.nearStatus();
            if (sub.isEmpty() || sub.equals("list")) return listText();
            // 0.21.2: the near-me zone (status for everyone allowed, changes for the owner)
            if (sub.equals("near")) {
                String arg = parts.size() > 1 ? parts.get(1).toLowerCase() : "status";
                if (arg.equals("status")) return nearLine == null ? "near me: " + (nearOn() ? nearR() + " blocks (on)" : "off") + " (mod not loaded)" : nearLine;
                if (!isOwner) return notOwner;
                if (arg.equals("on") || arg.equals("off")) {
                    p.getAsJsonObject("near").addProperty("on", arg.equals("on"));
                    guard.setNear(nearOn(), nearR());
                    saved.run();
                    return "ok: near me " + arg + (arg.equals("on") ? " - I may work in a circle of " + nearR() + " blocks around you, wherever you are (safe areas and the safety rules still hold)"
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
                return "ok: near me " + r + " blocks (on) - I may work in a circle of " + r + " blocks around you (" + io.github.mojolowjo.entropybot.guard.NearZone.Y_HALF + " up and down)";
            }
            String name = parts.size() > 1 ? parts.get(1).toLowerCase() : "";
            if (sub.equals("show")) {
                JsonObject b = findArea(name);
                if (b == null) return "I have no area called " + (name.isEmpty() ? "?" : name) + " (area list)";
                String r = areaText(b) + ", " + (n(b, "x2") - n(b, "x1") + 1) + "x" + (n(b, "z2") - n(b, "z1") + 1);
                if (!dimOf(b).equals(me.dim()) || boxGap(b, me.x(), me.y(), me.z()) > 0) return r + " - I'm outside it";
                return r + " - edges: west " + (me.x() - n(b, "x1")) + ", east " + (n(b, "x2") - me.x()) + ", north " + (me.z() - n(b, "z1")) + ", south " + (n(b, "z2") - me.z());
            }
            if (!isOwner) return notOwner;
            if (sub.equals("del")) {
                JsonObject b = findArea(name);
                if (b == null) return "I have no area called " + (name.isEmpty() ? "?" : name) + " (area list)";
                if (parts.size() < 3 || !parts.get(2).equalsIgnoreCase("confirm")) return "say \"area del " + name + " confirm\" to delete it";
                removeNamed(name);
                return changed("ok: area " + name + " deleted", false);
            }
            if (sub.equals("change")) {
                String what = name;
                if (parts.size() != 4 || !(what.equals("name") || what.equals("type"))) return "usage: area change name <name> <new name> | area change type <name> <type>";
                String target = parts.get(2).toLowerCase(), val = parts.get(3).toLowerCase();
                JsonObject b = findArea(target);
                if (b == null) return "I have no area called " + target + " (area list)";
                if (what.equals("name")) {
                    String bad = badName(val);
                    if (bad != null) return bad;
                    if (findArea(val) != null && !val.equalsIgnoreCase(target)) return "error: there is an area called " + val + " already";
                    b.addProperty("name", val);
                    return changed("ok: area " + target + " is now called " + val, false);
                }
                AreaType t = AreaType.of(val);
                if (t == null) return AreaType.BAD;
                JsonObject nb = b.deepCopy();
                if (t == AreaType.NEUTRAL) nb.remove("type");
                else nb.addProperty("type", t.word());
                removeNamed(target);
                String full = put(nb);
                if (full != null) {
                    put(b);
                    return full;
                }
                return changed("ok: area " + target + " is now " + t.word() + " (" + t.colour + ")", false);
            }
            NewArea a = parseNew(parts);
            if (a.error() != null) return a.error();
            JsonObject b = build(a, here, me);
            if (io.github.mojolowjo.entropybot.guard.GuardCore.DENIED_DIMS.contains(dimOf(b))) return "error: no areas in " + dimOf(b);
            boolean existed = findArea(a.name()) != null;
            String full = put(b);
            if (full != null) return full;
            return changed("ok: area " + areaText(b) + (existed ? " replaced" : " added"), false);
        }
        if (verb.equals("fence")) {
            if (sub.isEmpty() || sub.equals("status")) {
                JsonObject g = guard.status();
                if (g == null) return "fence: mod not loaded (off) | " + listText() + " | mode wanted: " + (strict() ? "strict" : "log");
                String mode = g.has("mode") ? g.get("mode").getAsString() : "?";
                List<String> tasks = new ArrayList<>();
                if (g.has("leases")) for (JsonElement e : g.getAsJsonArray("leases")) {
                    JsonObject l = e.getAsJsonObject();
                    tasks.add((l.has("task") ? l.get("task").getAsString() : "job") + (l.has("destroy") ? " (destroy " + l.get("destroy").getAsString() + ")" : ""));
                }
                List<String> hooks = new ArrayList<>();
                if (g.has("click") && g.get("click").getAsBoolean()) hooks.add("click");
                if (g.has("astar") && g.get("astar").getAsBoolean()) hooks.add("astar");
                return "fence: " + mode + " mode" + (!mode.equals(strict() ? "strict" : "log") ? " (my notes say " + (strict() ? "strict" : "log") + ")" : "")
                        + " | " + listText().replaceFirst(" \\| near me.*$", "")
                        + " | leases: " + tasks.size() + (tasks.isEmpty() ? "" : " (" + String.join("; ", tasks.subList(0, Math.min(3, tasks.size()))) + ")")
                        + " | vetoes " + num(g, "vetoes") + ", would-be " + num(g, "wouldVetoes") + " | hooks: " + (hooks.isEmpty() ? "none" : String.join(", ", hooks))
                        + (guard.nearStatus() == null ? "" : " | " + guard.nearStatus());
            }
            if (sub.equals("vetoes")) {
                JsonArray list;
                try {
                    list = com.google.gson.JsonParser.parseString(guard.vetoes(5)).getAsJsonArray();
                } catch (RuntimeException e) {
                    return "fence: vetoes unreadable (" + e.getMessage() + ")";
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
                if (parts.size() < 4 || !allInts(parts.subList(1, 4)) || !m.matches("^(break|place|go)$")) return "usage: fence check x y z break|place|go";
                int x = Integer.parseInt(parts.get(1)), y = Integer.parseInt(parts.get(2)), z = Integer.parseInt(parts.get(3));
                String r = guard.check(me.dim(), x, y, z, m);
                return (r == null ? "?" : r) + whereText(me.dim(), x, y, z);
            }
            if (sub.equals("mode")) {
                String m = parts.size() > 1 ? parts.get(1).toLowerCase() : "";
                if (m.equals("strict")) {
                    p.addProperty("strict", true);
                    return changed("ok: strict mode - the fence is on (" + (areas().isEmpty() ? "no work areas yet - " + AREA_HINT + "; walking stays free, digging and building only "
                            + (nearOn() ? "within " + nearR() + " blocks of you" : "nowhere") : "areas: " + names(areas()) + (nearOn() ? ", plus " + nearR() + " blocks around you" : "")) + ")", true);
                }
                if (m.equals("log")) {
                    if (parts.size() < 3 || !parts.get(2).equalsIgnoreCase("confirm")) {
                        return "say \"fence mode log confirm\" to switch the fence off (the guard then only logs what it would refuse; the floor stays)";
                    }
                    p.addProperty("strict", false);
                    return changed("ok: log mode - the fence is off, the guard only logs (the floor stays)", true);
                }
                return "usage: fence mode strict | fence mode log confirm";
            }
            return "usage: fence | fence vetoes | fence check x y z break|place|go | fence mode strict | fence mode log confirm";
        }
        return "unknown command \"" + verb + "\"";
    }

    private static String names(JsonArray l) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : l) out.add(nameOf(e.getAsJsonObject()));
        return out.isEmpty() ? "none" : String.join(", ", out);
    }

    private static long num(JsonObject o, String k) {
        try { return o.has(k) ? (long) o.get(k).getAsDouble() : 0; } catch (RuntimeException e) { return 0; }
    }
}
