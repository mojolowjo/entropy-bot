package io.github.mojolowjo.entropybot.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B4 (docs/BRAIN_PLAN.md 7, BRAIN_LOOP "Seeing the tree"): the tree as data. {@link #export} writes the running tree
 * (generated from the Java nodes, never by hand) as {@code entropybot/brain-tree.json}: every node with its uid
 * ({@code r/4/1}, what brain.json's {@code path} names), id, type (selector, sequence, condition, interrupt, leaf, need),
 * label, condition text, the verb it runs and the settings it reads; the pick leaf carries the needs it scores.
 * {@link #parse} reads {@code entropybot/brain-tree.override.json} (the same shape) into a tree of another order:
 * composites are free (new ids and labels), conditions and leaves must be built-in ones (their behaviour is Java), verbs
 * must match the built-in's, keys must be settings, depth at most 8, at most 200 nodes, the root a selector. Anything
 * else refuses the whole file with the reasons (the caller keeps the built-in tree and says so in {@code check}). Pure.
 */
public final class BrainTreeFile {
    private BrainTreeFile() {}

    public static final String EXPORT = "brain-tree.json";
    public static final String OVERRIDE = "brain-tree.override.json";
    public static final int MAX_DEPTH = 8, MAX_NODES = 200;
    static final Set<String> TYPES = Set.of("selector", "sequence", "condition", "interrupt", "leaf", "need");

    /** The needs the pick leaf scores: name, label, the job it starts, the settings it reads. */
    static final String[][] NEEDS = {
            {"safety", "safety: a threat counts or health under half", "", "dangerHealth", "need.safety.weight"},
            {"food", "food: edible items in the bag", "get food", "foodWant", "foodWantEarly", "foodTop", "need.food.weight"},
            {"tools", "tools: a pickaxe for the stage", "craft <tier>_pickaxe", "toolsNone", "toolsWorn", "toolsWornPct", "need.tools.weight"},
            {"bag", "bag: free slots", "deposit", "bagFull", "bagLow", "bagSome", "need.bag.weight"},
            {"need", "your needs (need <item> <n>)", "gather <item> <n>", "ownerNeedBase", "ownerNeedSpan", "ageBonusMax", "deadbandPct"},
            {"goal", "your goals", "the goal's chain", "goalBase", "ageBonusMax", "need.goal.weight"},
            {"copy", "brain copy: do what you do", "the copied job", "copyScore", "copyR", "copyIdleS", "need.copy.weight"},
            {"upkeep", "the idle list, first item that can run", "restock | mine strip | mine cave | farm | explore", "upkeep", "nearbyR", "idle", "need.upkeep.weight"},
    };

    /** The need node's uid-free id for a brain.json need ("need:torch" -> "need.need", "goal:2" -> "need.goal"). */
    public static String needNode(String need) {
        if (need == null) return null;
        String n = need.startsWith("need:") ? "need" : need.startsWith("goal:") ? "goal" : need;
        for (String[] row : NEEDS) if (row[0].equals(n)) return "need." + n;
        return null;
    }

    // ---- export ----

    public static JsonObject export(BrainTree tree, BrainConfig cfg, List<String> idle, String source, String note, long t, String modVersion) {
        JsonObject o = new JsonObject();
        o.addProperty("format", 1);
        o.addProperty("generated", t);
        o.addProperty("mod", modVersion);
        o.addProperty("source", source);
        o.addProperty("note", note);
        o.add("root", node(tree.root(), "r"));
        o.add("keys", cfg.describe());
        JsonArray il = new JsonArray();
        idle.forEach(il::add);
        o.add("idle", il);
        JsonArray items = new JsonArray();
        IdleList.ITEMS.forEach(items::add);
        o.add("idleItems", items);
        return o;
    }

    static JsonObject node(BrainTree.Node n, String uid) {
        JsonObject o = new JsonObject();
        o.addProperty("uid", uid);
        o.addProperty("id", n.id);
        o.addProperty("type", n.exportType());
        o.addProperty("label", n.label);
        if (!n.cond.isEmpty()) o.addProperty("cond", n.cond);
        if (!n.verb.isEmpty()) o.addProperty("verb", n.verb);
        JsonArray keys = new JsonArray();
        n.keys.forEach(keys::add);
        o.add("keys", keys);
        JsonArray kids = new JsonArray();
        for (int i = 0; i < n.children.size(); i++) kids.add(node(n.children.get(i), uid + "/" + i));
        if (n.id.equals("pick") && n.children.isEmpty())
            for (int i = 0; i < NEEDS.length; i++) {
                String[] row = NEEDS[i];
                JsonObject k = new JsonObject();
                k.addProperty("uid", uid + "/n" + i);
                k.addProperty("id", "need." + row[0]);
                k.addProperty("type", "need");
                k.addProperty("label", row[1]);
                if (!row[2].isEmpty()) k.addProperty("verb", row[2]);
                JsonArray ks = new JsonArray();
                for (int j = 3; j < row.length; j++) ks.add(row[j]);
                k.add("keys", ks);
                k.add("children", new JsonArray());
                kids.add(k);
            }
        o.add("children", kids);
        return o;
    }

    // ---- the override ----

    /** A parsed override: the tree, or null with the reasons. */
    public record Result(BrainTree tree, List<String> errors) {
        public boolean ok() { return tree != null; }
    }

    public static Result parse(String text) {
        List<String> errs = new ArrayList<>();
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(text);
            if (!e.isJsonObject()) return new Result(null, List.of("not a JSON object"));
            o = e.getAsJsonObject();
        } catch (RuntimeException ex) {
            return new Result(null, List.of("not valid JSON (" + ex.getMessage() + ")"));
        }
        JsonElement r = o.get("root");
        if (r == null || !r.isJsonObject()) return new Result(null, List.of("no \"root\" node"));
        Map<String, BrainTree.Node> builtin = new LinkedHashMap<>();
        index(BrainTree.build(), builtin);
        int[] count = {0};
        Set<String> used = new HashSet<>();
        BrainTree.Node root = build(r.getAsJsonObject(), "root", 1, builtin, used, count, errs);
        if (root != null && !(root instanceof BrainTree.Selector)) errs.add("the root must be a selector");
        if (!errs.isEmpty() || root == null) return new Result(null, errs.isEmpty() ? List.of("empty tree") : errs);
        return new Result(new BrainTree(root), List.of());
    }

    static void index(BrainTree.Node n, Map<String, BrainTree.Node> out) {
        if (n.children.isEmpty()) out.put(n.exportType() + ":" + n.id, n);
        for (BrainTree.Node k : n.children) index(k, out);
    }

    static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static BrainTree.Node build(JsonObject o, String where, int depth, Map<String, BrainTree.Node> builtin, Set<String> used, int[] count,
                                        List<String> errs) {
        if (++count[0] > MAX_NODES) {
            if (count[0] == MAX_NODES + 1) errs.add("more than " + MAX_NODES + " nodes");
            return null;
        }
        if (depth > MAX_DEPTH) {
            errs.add(where + ": deeper than " + MAX_DEPTH);
            return null;
        }
        String type = str(o, "type"), id = str(o, "id");
        if (type == null || !TYPES.contains(type)) {
            errs.add(where + ": unknown node type " + type + " (known: selector, sequence, condition, interrupt, leaf, need)");
            return null;
        }
        if (id == null || id.isBlank()) {
            errs.add(where + ": a node without an id");
            return null;
        }
        String at = where + " > " + id;
        JsonArray keys = o.has("keys") && o.get("keys").isJsonArray() ? o.getAsJsonArray("keys") : new JsonArray();
        for (JsonElement k : keys) {
            String key = k.isJsonPrimitive() ? k.getAsString() : String.valueOf(k);
            if (!BrainConfig.known(key) && !key.equals("idle")) errs.add(at + ": unknown key " + key);
        }
        JsonArray kids = o.has("children") && o.get("children").isJsonArray() ? o.getAsJsonArray("children") : new JsonArray();
        if (type.equals("need")) {
            if (needNode(id.startsWith("need.") ? id.substring(5) : id) == null) errs.add(at + ": unknown need " + id);
            return null;      // shown only; the pick leaf scores every need
        }
        if (type.equals("selector") || type.equals("sequence")) {
            if (kids.isEmpty()) {
                errs.add(at + ": a " + type + " needs children");
                return null;
            }
            List<BrainTree.Node> built = new ArrayList<>();
            for (JsonElement k : kids) {
                if (!k.isJsonObject()) {
                    errs.add(at + ": a child that is not an object");
                    continue;
                }
                if ("need".equals(str(k.getAsJsonObject(), "type"))) {
                    errs.add(at + ": a need node belongs under the pick leaf");
                    continue;
                }
                BrainTree.Node n = build(k.getAsJsonObject(), at, depth + 1, builtin, used, count, errs);
                if (n != null) built.add(n);
            }
            String label = str(o, "label");
            BrainTree.Node[] arr = built.toArray(new BrainTree.Node[0]);
            BrainTree.Node n = type.equals("selector") ? new BrainTree.Selector(id, label == null ? id : label, arr) : new BrainTree.Sequence(id, label == null ? id : label, arr);
            return n;
        }
        BrainTree.Node b = builtin.get(type + ":" + id);
        if (b == null) {
            errs.add(at + ": no built-in " + type + " " + id + " (a " + type + " must be one of the built-in tree's)");
            return null;
        }
        if (!used.add(type + ":" + id)) errs.add(at + ": " + type + " " + id + " used twice");
        String verb = str(o, "verb");
        if (verb != null && !verb.isEmpty() && !verb.equals(b.verb)) errs.add(at + ": unknown verb " + verb + " (this node runs " + (b.verb.isEmpty() ? "nothing" : b.verb) + ")");
        for (JsonElement k : kids) {
            if (!type.equals("leaf") || !id.equals("pick")) {
                errs.add(at + ": a " + type + " has no children");
                break;
            }
            if (k.isJsonObject()) build(k.getAsJsonObject(), at, depth + 1, builtin, used, count, errs);
        }
        return b;
    }
}
