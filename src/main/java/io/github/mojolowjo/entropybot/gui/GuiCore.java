package io.github.mojolowjo.entropybot.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The GUI toolkit (B7b part 2, ported from the KubeJS bridge's guiRoles / guiMove / guiFill / guiTransfer / transfer
 * with the same rules and wording): works on whatever container menu is open. The container's slots are told apart
 * by what they accept, every count is measured from the slots (never assumed), at most what was asked moves, and
 * upgrade, filter, fuel and output slots and armor are never written to. Plain Java over {@link GuiMenu}: JUnit
 * drives it with a fake menu.
 */
public final class GuiCore {
    private GuiCore() {}

    public static final List<String> TAKE_ROLES = List.of("storage", "input", "output");
    public static final List<String> PUT_ROLES = List.of("storage", "input");
    public static final List<String> ALL_ROLES = List.of("storage", "input", "output", "fuel");
    public static final int ALL = Integer.MAX_VALUE;

    /** The container-side slots by role: input, fuel, output, storage, other (slot indexes in order). */
    public static Map<String, List<Integer>> roles(GuiMenu m) {
        Map<String, List<Integer>> roles = new LinkedHashMap<>();
        for (String r : List.of("input", "fuel", "output", "storage", "other")) roles.put(r, new ArrayList<>());
        List<Integer> plain = new ArrayList<>();
        for (int i = 0; i < m.size(); i++) {
            if (m.mine(i) || m.armor(i)) continue;
            String p = m.probe(i);
            if (p.equals("plain")) plain.add(i);
            else roles.get(p.equals("fuel") || p.equals("output") ? p : "other").add(i);
        }
        // nine or more slots that take dirt are storage, fewer are a machine's inputs
        if (plain.size() >= 9) roles.put("storage", plain);
        else roles.put("input", plain);
        return roles;
    }

    /** The slot indexes of those roles, in slot order. */
    public static List<Integer> pick(Map<String, List<Integer>> roles, List<String> names) {
        List<Integer> out = new ArrayList<>();
        for (String n : names) out.addAll(roles.getOrDefault(n, List.of()));
        Collections.sort(out);
        return out;
    }

    /** The bot's inventory and hotbar slots in the menu. */
    public static List<Integer> mine(GuiMenu m) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < m.size(); i++) if (m.mine(i)) out.add(i);
        return out;
    }

    /** {id: n} in the container slots of those roles. */
    public static Map<String, Integer> contents(GuiMenu m, List<String> names, Map<String, List<Integer>> roles) {
        Map<String, Integer> out = new TreeMap<>();
        for (int i : pick(roles == null ? roles(m) : roles, names)) {
            String id = m.id(i);
            if (id != null) out.merge(id, m.count(i), Integer::sum);
        }
        return out;
    }

    /** {id: n} the bot carries (inventory and hotbar). */
    public static Map<String, Integer> carried(GuiMenu m) {
        Map<String, Integer> out = new TreeMap<>();
        for (int i = 0; i < m.size(); i++) {
            if (!m.mine(i) || m.id(i) == null) continue;
            out.merge(m.id(i), m.count(i), Integer::sum);
        }
        return out;
    }

    /** How many of id fit in the inventory (free slots plus room in partial stacks). */
    public static int room(GuiMenu m, String id) {
        int max = 64;
        for (int i = 0; i < m.size(); i++) {
            if (id.equals(m.id(i))) {
                max = m.stackMax(i);
                break;
            }
        }
        int room = 0;
        for (int i = 0; i < m.size(); i++) {
            if (!m.mine(i)) continue;
            if (m.id(i) == null) room += max;
            else if (m.id(i).equals(id)) room += Math.max(0, m.stackMax(i) - m.count(i));
        }
        return room;
    }

    /** Whatever is on the cursor goes back: where it came from (hint), else any room in the inventory. */
    public static boolean cursorClear(GuiMenu m, int hint) {
        if (!Boolean.TRUE.equals(m.cursorHas())) return true;
        if (hint >= 0 && (m.id(hint) == null || m.cursorSame(hint))) m.click(hint, 0, "PICKUP");
        for (int i = 0; i < m.size() && Boolean.TRUE.equals(m.cursorHas()); i++) {
            if (m.mine(i) && (m.id(i) == null || m.cursorSame(i))) m.click(i, 0, "PICKUP");
        }
        return !Boolean.TRUE.equals(m.cursorHas());
    }

    /**
     * Moves exactly n items (fewer if `to` can't hold that many) from slot `from` to slot `to` with the cursor: pick the
     * stack up, put single items back (or down) until n are left on the cursor, put those down. Returns how many
     * arrived. Never drops anything.
     */
    public static int move(GuiMenu m, int from, int to, int n) {
        if (from == to || m.id(from) == null || Boolean.TRUE.equals(m.cursorHas())) return 0;
        if (m.id(to) != null && !m.same(from, to)) return 0;
        if (!m.mayPlace(to, from)) return 0;
        int total = m.count(from), before = m.id(to) == null ? 0 : m.count(to);
        n = Math.min(Math.min(n, total), m.slotLimit(to, from) - before);
        if (n <= 0) return 0;
        m.click(from, 0, "PICKUP");                                        // the whole stack onto the cursor
        if (Boolean.FALSE.equals(m.cursorHas())) return 0;                 // this slot won't let go of it
        if (n == total) {
            m.click(to, 0, "PICKUP");
        } else if (n <= total / 2.0) {
            for (int i = 0; i < n; i++) m.click(to, 1, "PICKUP");         // one at a time into `to`
            m.click(from, 0, "PICKUP");                                    // the rest back
        } else {
            for (int i = 0; i < total - n; i++) m.click(from, 1, "PICKUP");  // the surplus back, one at a time
            m.click(to, 0, "PICKUP");
        }
        cursorClear(m, from);
        return (m.id(to) == null ? 0 : m.count(to)) - before;
    }

    /** Moves up to n of the stack in slot `from` into the slots dst: the same item with room first, then empty slots. */
    public static int fill(GuiMenu m, int from, int n, List<Integer> dst) {
        int moved = 0;
        for (int pass = 0; pass < 2 && moved < n; pass++) {
            for (int i = 0; i < dst.size() && moved < n; i++) {
                if (m.id(from) == null) return moved;
                int d = dst.get(i);
                if (d == from) continue;
                boolean empty = m.id(d) == null;
                if (pass == 0 ? (empty || !m.same(from, d)) : !empty) continue;
                moved += move(m, from, d, n - moved);
            }
        }
        return moved;
    }

    /** {moved, want (what there was to move), stuck (no room for the rest: 1)}. */
    public record Result(int moved, int want, boolean stuck) {}

    /**
     * Moves up to n of id ("all" = anything) from the slots src into the slots dst. Whole stacks that fit are
     * shift-clicked when quick (else, and for the one partial stack, the cursor does it), so it never moves more than
     * asked and never overflows.
     */
    public static Result transfer(GuiMenu m, String id, int n, List<Integer> src, List<Integer> dst, boolean quick) {
        boolean any = id.equals("all");
        long have = 0;
        for (int s : src) if (m.id(s) != null && (any || m.id(s).equals(id))) have += m.count(s);
        int want = (int) Math.min(have, n);
        int moved = 0;
        boolean stuck = false;
        for (int pass = 0; pass < 2 && !stuck; pass++) {
            for (int i = 0; i < src.size() && moved < want && !stuck; i++) {
                int s = src.get(i);
                if (m.id(s) == null || (!any && !m.id(s).equals(id))) continue;
                int cnt = m.count(s), need = want - moved;
                if ((cnt <= need) != (pass == 0)) continue;              // pass 0: stacks that fit whole; pass 1: the partial one
                int goal = Math.min(cnt, need), r = 0;
                if (pass == 0 && quick) {
                    m.click(s, 0, "QUICK_MOVE");
                    r = cnt - (m.id(s) == null ? 0 : m.count(s));
                }
                if (r < goal) r += fill(m, s, goal - r, dst);
                moved += r;
                if (r < goal) stuck = true;
            }
        }
        return new Result(moved, want, stuck);
    }

    /** container -> inventory; stuck = the inventory had no room for the rest. */
    public static Result take(GuiMenu m, String id, int n, List<String> names, Map<String, List<Integer>> roles) {
        if (roles == null) roles = roles(m);
        return transfer(m, id, n, pick(roles, names), mine(m), true);
    }

    /**
     * inventory -> the container slots of those roles. Shift-click only into a menu that is all plain storage (in one
     * with upgrade slots it could install an upgrade item). Result.want - moved = what the container couldn't take.
     */
    public static Result put(GuiMenu m, String id, int n, List<String> names, Map<String, List<Integer>> roles) {
        if (roles == null) roles = roles(m);
        boolean quick = names.contains("storage") && !roles.get("storage").isEmpty() && roles.get("fuel").isEmpty()
                && roles.get("output").isEmpty() && roles.get("other").isEmpty();
        return transfer(m, id, n, mine(m), pick(roles, names), quick);
    }

    // ---- reports ----

    /** [gained, lost] between two counts. */
    public static List<Map<String, Integer>> diff(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> gained = new TreeMap<>(), lost = new TreeMap<>();
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            int d = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (d > 0) gained.put(e.getKey(), d);
        }
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            int d = e.getValue() - after.getOrDefault(e.getKey(), 0);
            if (d > 0) lost.put(e.getKey(), d);
        }
        return List.of(gained, lost);
    }

    public static int sum(Map<String, Integer> m) {
        int t = 0;
        for (int v : m.values()) t += v;
        return t;
    }

    public static String shortId(String id) { return id == null ? "" : id.replaceFirst("^minecraft:", ""); }

    public static String bareId(String id) { return id == null ? "" : id.replaceFirst("^[^:]*:", ""); }

    public static String normId(String q) {
        String s = q.toLowerCase();
        return s.contains(":") ? s : "minecraft:" + s;
    }

    /** Does an item id answer to name q? "mod:x" exactly; "x" or "xs" in any namespace (copper_pickaxe = leafscopperbackport:copper_pickaxe). */
    public static java.util.function.Predicate<String> nameMatcher(String q) {
        String n = q.toLowerCase();
        if (n.contains(":")) return id -> id.equals(n);
        String one = n.endsWith("s") ? n.substring(0, n.length() - 1) : n, es = n.endsWith("es") ? n.substring(0, n.length() - 2) : n;
        return id -> {
            String p = id.substring(id.indexOf(':') + 1);
            return p.equals(n) || p.equals(one) || p.equals(es);
        };
    }

    /** The id among candidates that name q means (minecraft's first), else normId(q) (for the "no ..." replies). */
    public static String resolve(String q, java.util.Collection<String> candidates) {
        String exact = normId(q);
        if (candidates.contains(exact)) return exact;
        java.util.function.Predicate<String> m = nameMatcher(q);
        String best = null;
        for (String id : candidates) {
            if (!m.test(id)) continue;
            if (best == null || (id.startsWith("minecraft:") && !best.startsWith("minecraft:"))) best = id;
        }
        return best != null ? best : exact;
    }

    /** {id: n} -> "96 rotten_flesh, 40 bone, +3 more" (the n biggest). */
    public static String top(Map<String, Integer> map, int n) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(map.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size() && i < n; i++) out.add(list.get(i).getValue() + " " + shortId(list.get(i).getKey()));
        if (list.size() > n) out.add("+" + (list.size() - n) + " more");
        return String.join(", ", out);
    }

    // ---- take / put with an open container (the PM verbs) ----

    /**
     * "put <item|all> [count|all]" / "take ...": counts are exact; what really moved is measured, never assumed, and a
     * reply that doesn't start with "ok" means the request fell short (chains stop on it). bad: why this menu must be
     * left alone (wrongScreen), or null.
     */
    public static String transferVerb(GuiMenu m, String text, boolean toContainer, String bad) {
        String verb = toContainer ? "put" : "take";
        String[] parts = (text == null ? "" : text.trim()).split("\\s+");
        if (bad != null) return "error: " + bad;
        String q = parts[0].toLowerCase();
        int want = ALL;
        if (parts.length > 1) {
            if (parts[1].equals("all")) want = ALL;
            else {
                try { want = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { want = -1; }
            }
        }
        if (q.isEmpty() || !(want > 0)) return "error: usage " + verb + " <item|all> [count|all]";
        boolean explicit = parts.length > 1 && !parts[1].equals("all");
        Map<String, List<Integer>> roles = roles(m);
        List<String> names = toContainer ? PUT_ROLES : TAKE_ROLES;
        Map<String, Integer> before = carried(m);
        // a name in any mod's namespace: what the bot carries (put) or the container holds (take)
        String id = q.equals("all") ? "all" : resolve(q, toContainer ? before.keySet() : contents(m, names, roles).keySet());
        boolean all = id.equals("all");
        String name = all ? "items" : shortId(id);
        if (toContainer) {
            int have = all ? sum(before) : before.getOrDefault(id, 0);
            if (have == 0) return all ? "error: I carry nothing to put" : "error: I carry no " + name;
            Result res = put(m, id, want, names, roles);
            Map<String, Integer> d = diff(before, carried(m)).get(1);
            int got = all ? sum(d) : d.getOrDefault(id, 0);
            int req = explicit ? want : have;
            String what = all ? top(d, 3) : got + " " + name;
            if (got == 0) return "error: the container is full - I could not put any " + name;
            if (got < req) return "only put " + got + " of " + req + " " + name + " - " + (res.want() - res.moved() > 0 ? "the container is full" : "I only carry " + have);
            Map<String, Integer> held = contents(m, names, roles);
            return "ok: put " + what + " (the container now " + (all ? "holds " + sum(held) + " items" : "has " + held.getOrDefault(id, 0)) + ")";
        }
        Map<String, Integer> held = contents(m, names, roles);
        if (all ? sum(held) == 0 : !held.containsKey(id)) {
            String t = top(held, 4);
            return all ? "error: the container is empty" : "error: no " + name + " in this container (it holds: " + (t.isEmpty() ? "nothing" : t) + ")";
        }
        Result res = take(m, id, want, names, roles);
        Map<String, Integer> d = diff(before, carried(m)).get(0);
        int got = all ? sum(d) : d.getOrDefault(id, 0);
        int req = explicit ? want : (all ? sum(held) : held.get(id));
        String what = all ? top(d, 3) : got + " " + name;
        Map<String, Integer> left = contents(m, names, roles);
        int tailN = all ? sum(left) : left.getOrDefault(id, 0);
        String tail = tailN > 0 ? tailN + (all ? " items" : "") + " left in the container" : "nothing left in the container";
        boolean full = res.stuck() && (all || room(m, id) <= 0);          // really no room (not just a slot that won't give it up)
        if (got == 0) return "error: " + (full ? "my inventory is full - took " + (explicit ? "0 of " + req + " " + name : "nothing") : "the container would not give up any " + name);
        if (got < req) {
            if (full) return explicit ? "error: my inventory is full - took " + got + " of " + req + " " + name : "ok: took " + what + " (my inventory is full)";
            return "only took " + got + " of " + req + " " + name + (res.stuck() ? " - the container would not give up the rest" : " - the container had no more");
        }
        return "ok: took " + what + " (" + tail + ")";
    }

    /** "drop <item|all> [count|all]" from the inventory and hotbar (never armor), with THROW clicks. */
    public static String drop(GuiMenu m, String text) {
        String[] p = (text == null ? "" : text.trim()).split("\\s+");
        String q = p[0].toLowerCase();
        long want = ALL;
        if (p.length > 1) {
            if (p[1].equals("all")) want = ALL;
            else {
                try { want = Long.parseLong(p[1]); } catch (NumberFormatException e) { want = -1; }
            }
        }
        if (q.isEmpty()) return "error: usage drop <item|all> [count|all]";
        String id = q.equals("all") ? "all" : resolve(q, carried(m).keySet());
        long dropped = 0;
        for (int i = 0; i < m.size() && dropped < want; i++) {
            if (!m.mine(i) || m.id(i) == null || !(id.equals("all") || m.id(i).equals(id))) continue;
            int n = m.count(i);
            if (n <= want - dropped) {
                m.click(i, 1, "THROW");
                dropped += n;
            } else {
                while (dropped < want) {
                    m.click(i, 0, "THROW");
                    dropped++;
                }
            }
        }
        return dropped > 0 ? "ok: dropped " + dropped + " " + q : "error: no " + q + " in inventory";
    }

    /** One line about the open menu's slots by role: "storage 0-26 x27 (Slot) | other 63-65 x3 (UpgradeSlot)". */
    public static String describe(Map<String, List<Integer>> roles, java.util.function.IntFunction<String> slotClass) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : roles.entrySet()) {
            List<Integer> list = e.getValue();
            if (list.isEmpty()) continue;
            List<String> cls = new ArrayList<>();
            for (int i : list) if (!cls.contains(slotClass.apply(i))) cls.add(slotClass.apply(i));
            out.add(e.getKey() + " " + list.get(0) + "-" + list.get(list.size() - 1) + " x" + list.size() + " (" + String.join(", ", cls) + ")");
        }
        return String.join(" | ", out);
    }

    /** Why take/put must leave the open menu alone (null = fine): menus that make something, or belong to someone else. */
    public static String wrongScreen(String names) {
        String t = names == null ? "" : names;
        if (t.toLowerCase().contains("corpse")) return "that is a corpse - I only empty my own (\"death\" or \"corpse\")";
        if (t.matches("(?is).*(crafting|workbench).*")) return "that is a crafting table, not a chest";
        java.util.regex.Matcher mm = java.util.regex.Pattern.compile("anvil|smithing|grindstone|enchant|stonecutter|loom|cartography|merchant|beacon|lectern",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(t);
        if (mm.find()) {
            String w = mm.group().toLowerCase();
            return "that is " + (w.matches("^[aeiou].*") ? "an " : "a ") + w + ", not a chest";
        }
        return null;
    }
}
