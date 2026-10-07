package io.github.mojolowjo.entropybot.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.commands.VerbTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B3 (docs/BRAIN_PLAN.md 6.1, BRAIN_LOOP "owner's direction"): the action table. Every primitive with its inputs,
 * preconditions, effects, cost, whether it finishes by itself and who may use it; and every verb of {@link VerbTable}
 * mapped to the action it is (or marked "not planable" with why). The planner chooses among the planable actions; the
 * same table, plus a state summary, is the one compact document a model reads in one turn ({@link #document}:
 * {@code actions.json}, {@code GET /actions}, the {@code actions} verb). Pure; {@link #selfTest} runs at load.
 */
public final class ActionTable {
    private ActionTable() {}

    public static final int VERSION = 1;
    /** The document's size budget without the state (BRAIN_PLAN 9: /api/actions under 30 KB). */
    public static final int BUDGET = 30_000;

    /** One primitive. planner: the goal search may choose it. */
    public record ActionSpec(String id, String verb, String inputs, String pre, String eff, String cost, boolean finishes, String who, boolean planner) {}

    static final Map<String, ActionSpec> ACTIONS = new LinkedHashMap<>();

    private static void a(String id, String verb, String inputs, String pre, String eff, String cost, boolean finishes, boolean planner) {
        ACTIONS.put(id, new ActionSpec(id, verb, inputs, pre, eff, cost, finishes, "owner", planner));
    }

    static {
        a("walk", "goto x y z | go <place> [marker] | come | home", "a spot, a place or the owner", "the spot inside my areas or the near-me zone (the fence)", "at(spot)", "1 per block", true, false);
        a("fetch", "get <item|kind> [n]", "item, n", "the item in the chests or the RS network (stock)", "have(item, n)", "3", true, true);
        a("cut", "cut <n> logs", "n", "trees in my areas", "have(log, n)", "3 + 2 per log", true, true);
        a("craft", "craft <item> [n]", "item, n", "the recipe's inputs (the craft planner fetches and makes parts); a crafting table within 12 for 3x3 recipes", "have(item, n)", "2", true, true);
        a("place", "place <block> x y z", "block, a free spot in reach", "have(block, 1); the spot inside my areas", "the block stands there (table/furnace near)", "3", true, true);
        a("smelt", "smelt <item> [n] then smelt collect all", "item, n", "a furnace near; the raw item; fuel (1 per 8)", "have(item, n)", "6 + 1 per item", true, true);
        a("mine", "mine strip <ore> [n] | mine <ore>_ore [n] | mine cave <ore> [n]", "ore family, n", "a pickaxe of the ore's tier; a marked mine, an ore in view, or a known cave", "have(ore drop, n)", "10 + 3 per item", true, true);
        a("quarry", "dig x1 y1 z1 x2 y2 z2 (8 staircase columns, as bootstrap)", "the bot's spot", "a pickaxe; stone under me; inside my areas", "have(cobblestone, ~40)", "25", true, true);
        a("gather", "gather <item|kind> [n]", "item, n", "the gather verb's sources (storage, craft, smelt, mine, cut, farm)", "have(item, n)", "20 + 2 per item", true, true);
        a("build", "area here 1 shelter neutral 1 2 then build shell <block> shelter", "a block kind", "have(block, 32)", "shelter around me", "10", true, true);
        a("light", "light here <r> | light x1 z1 x2 z2", "an area", "torches (it fetches or crafts them)", "torches every 6 blocks", "10 + per torch", true, false);
        a("sleep", "sleep", "", "night; a bed near or in my bag", "slept (morning)", "5", true, false);
        a("deposit", "deposit [item ...]", "", "base chests scanned (scan base)", "free bag slots", "10", true, false);
        a("attack", "attack <mob kind|entity id>", "a mob", "the mob in sight; never players unless defence players on; never pets", "the mob dead or gone", "10", true, false);
        a("eat", "eat", "", "food in the bag", "food bar up", "1", true, false);
        a("chest", "open x y z | take <item> [n] | put <item> [n] | close", "a chest", "the chest trusted", "items moved", "3", true, false);
        a("area", "area here <r> <name> [type] [down up]", "radius, name", "owner", "an area where I may work", "1", true, false);
        a("camp", "camp here", "", "inside my areas", "a camp place with a bed marker, torches", "20", true, false);
    }

    /** Every VerbTable verb: the action it is ("a:<id>") or why the planner never uses it ("n:<why>"). */
    static final Map<String, String> VERBS = new LinkedHashMap<>();

    private static void v(String why, String... verbs) {
        for (String s : verbs) VERBS.put(s, why);
    }

    static {
        v("a:walk", "goto", "go", "come", "home");
        v("a:fetch", "get", "fetch");
        v("a:cut", "cut");
        v("a:craft", "craft", "kit");
        v("a:place", "place");
        v("a:smelt", "smelt", "cook");
        v("a:mine", "mine");
        v("a:quarry", "dig");
        v("a:gather", "gather");
        v("a:build", "build");
        v("a:light", "light");
        v("a:sleep", "sleep");
        v("a:deposit", "deposit", "unload");
        v("a:attack", "attack");
        v("a:eat", "eat");
        v("a:chest", "open", "take", "put", "close");
        v("a:area", "area");
        v("a:camp", "camp");
        v("n:answers only (no change in the world)", "places", "fence", "needs", "goals", "have", "stock", "status", "inv", "queue", "why", "check", "summary", "deaths",
                "kinds", "recipe", "help", "plan", "actions");
        v("n:a setting or a note, not a step toward a thing", "marker", "need", "supplies", "junk", "defence", "tools", "hotbar", "brain", "idle", "done", "routine",
                "rule", "trust", "untrust", "allow", "deny", "restart", "surface", "path", "confirm", "goal");
        v("n:runs until stopped or another order (no end state)", "follow", "defend", "guard", "escort", "dismiss", "carry", "repeat", "explore", "scout", "hold");
        v("n:a whole job of its own (it plans itself)", "restock", "bootstrap", "farm", "compact", "infuse", "upgrade", "pots", "rs", "scan", "restore", "find");
        v("n:moves items away or to a player (never a step toward having them)", "give", "drop", "wear");
        v("n:recovery after a death", "death", "corpse", "resume");
        v("n:chat, waiting or debugging", "say", "twerk", "spawn", "wait", "stop", "debug");
    }

    public static Map<String, ActionSpec> actions() { return java.util.Collections.unmodifiableMap(ACTIONS); }

    /** The note of a verb ("a:craft", "n:..."), or null when the table lacks it (the self-test's failure). */
    public static String verbNote(String verb) { return VERBS.get(verb); }

    /**
     * The load check (BRAIN_PLAN 6.3): every VerbTable verb has a note, every "a:" note names an action, every planner
     * action of {@link Planner#ACTIONS} is in the table. Returns the problems (empty = fine).
     */
    public static List<String> selfTest() {
        List<String> bad = new ArrayList<>();
        for (VerbTable.Verb v : VerbTable.all()) {
            String n = VERBS.get(v.name());
            if (n == null) bad.add("verb " + v.name() + " has no entry");
            else if (n.startsWith("a:") && !ACTIONS.containsKey(n.substring(2))) bad.add("verb " + v.name() + " names no action " + n);
        }
        for (String a : Planner.ACTIONS) if (!ACTIONS.containsKey(a) || !ACTIONS.get(a).planner()) bad.add("planner action " + a + " not in the table");
        return bad;
    }

    /** The table part of the document (no state): version, actions, verbs, goal forms. */
    public static JsonObject table() {
        JsonObject o = new JsonObject();
        o.addProperty("version", VERSION);
        o.addProperty("how", "send any verb line as a command (PM, /cmd); chains: a then b; goal <form> plans and saves goal_<name>; plan <form> is a dry run");
        JsonArray acts = new JsonArray();
        for (ActionSpec s : ACTIONS.values()) {
            JsonObject a = new JsonObject();
            a.addProperty("id", s.id());
            a.addProperty("verb", s.verb());
            if (!s.inputs().isEmpty()) a.addProperty("in", s.inputs());
            a.addProperty("pre", s.pre());
            a.addProperty("eff", s.eff());
            a.addProperty("cost", s.cost());
            a.addProperty("plan", s.planner());
            acts.add(a);
        }
        o.add("actions", acts);
        JsonArray verbs = new JsonArray();
        for (VerbTable.Verb v : VerbTable.all()) {
            JsonObject a = new JsonObject();
            a.addProperty("v", v.name());
            a.addProperty("use", v.usage());
            a.addProperty("who", v.who().name().toLowerCase());
            String n = VERBS.getOrDefault(v.name(), "n:?");
            a.addProperty(n.startsWith("a:") ? "act" : "not", n.substring(2));
            verbs.add(a);
        }
        o.add("verbs", verbs);
        o.addProperty("goals", GoalGrammar.FORMS);
        return o;
    }

    /** The whole document: the table plus the state summary (built by the game side; may be null). */
    public static JsonObject document(JsonObject state) {
        JsonObject o = table();
        o.add("state", state == null ? new JsonObject() : state);
        return o;
    }

    /** The {@code actions [verb]} answer: the short form, or one verb's line. */
    public static String shortText(String verb) {
        if (verb != null && !verb.isBlank()) {
            String w = verb.trim().toLowerCase();
            VerbTable.Verb v = VerbTable.of(w);
            ActionSpec a = ACTIONS.get(w);
            if (a == null && v != null && VERBS.getOrDefault(v.name(), "").startsWith("a:")) a = ACTIONS.get(VERBS.get(v.name()).substring(2));
            if (a != null) return a.id() + ": " + a.verb() + " | needs: " + a.pre() + " | gives: " + a.eff() + " | cost " + a.cost() + (a.planner() ? " | the planner uses it" : "");
            if (v != null) return v.name() + ": not planable (" + VERBS.getOrDefault(v.name(), "n:?").substring(2) + ")";
            return "no action or verb " + w + " - actions lists them";
        }
        List<String> p = new ArrayList<>();
        for (ActionSpec a : ACTIONS.values()) if (a.planner()) p.add(a.id());
        List<String> o = new ArrayList<>();
        for (ActionSpec a : ACTIONS.values()) if (!a.planner()) o.add(a.id());
        return "actions v" + VERSION + ": the planner chains " + String.join(", ", p) + "; also " + String.join(", ", o)
                + " - actions <verb> for one; the full table with the state is entropybot/actions.json (dashboard /api/actions)";
    }
}
