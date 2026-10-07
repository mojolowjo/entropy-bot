package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.plan.ActionTable;
import io.github.mojolowjo.entropybot.plan.GoalGrammar;
import io.github.mojolowjo.entropybot.plan.PlanFacts;
import io.github.mojolowjo.entropybot.plan.Planner;
import io.github.mojolowjo.entropybot.plan.RoutineRule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * B3, the game side of the planner (docs/BRAIN_PLAN.md 6): {@code goal <text>}, {@code goal show}, {@code plan <text>},
 * {@code actions [verb]}, the brain's hook for a goal with no chain, and {@code entropybot/actions.json} (the action table
 * plus a state summary, rewritten when the summary changes, checked every 5 s). The facts come from the bag, the stock
 * view, the places and a block scan around the bot (logs, stone, ores within 16); the free spots and the quarry's
 * direction as bootstrap finds them. Plans are saved as routines {@code goal_<name>} under the routine rules
 * ({@link RoutineRule}); the texts the planner saved are kept in commands.json "plans" so an owner-edited routine is
 * never overwritten.
 *
 * <p>Loader notes: vanilla client getters only (ClientLevel.getBlockState, ItemStack DataComponents.FOOD); the search
 * runs on the client thread within its 200 ms budget (a worker is not needed at ~2000 nodes). Errors: every entry point
 * catches and answers "error: plan: ..." and logs once; a failed actions.json write is counted.
 */
final class GoalPlanning {
    private static final Logger LOG = LogUtils.getLogger();
    private final Commands c;
    private String lastActions;
    private long actionsErrors;
    int plans, failures;
    long lastMs, lastNodes;
    private final Map<String, Object[]> hookCache = new HashMap<>();

    GoalPlanning(Commands c) {
        this.c = c;
        List<String> bad = ActionTable.selfTest();
        if (!bad.isEmpty()) LOG.warn("[entropybot] plan: the action table's self-test failed: {}", bad);
    }

    // ---- the facts ----

    PlanFacts facts(LocalPlayer p) {
        PlanFacts f = new PlanFacts();
        Map<String, Integer> inv = Gui.inventory(p);
        Set<String> food = new HashSet<>();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && st.has(DataComponents.FOOD)) food.add(Commands.itemId(st));
        }
        f.addBag(inv, food);
        try { f.addStock(minus(c.storage.stock(p).totals(), inv), food); } catch (RuntimeException e) { LOG.warn("[entropybot] plan: stock: {}", e.toString()); }
        ClientLevel lv = Minecraft.getInstance().level;
        int[] feet = Jobs.here(p);
        f.feet = feet;
        if (CampCommands.near(lv, feet, "crafting_table")) f.flag("table");
        if (CampCommands.near(lv, feet, "furnace")) f.flag("furnace");
        f.dir = CampCommands.quarryDir(lv, feet);
        List<int[]> free = CampCommands.freeRing(lv, feet, f.dir);
        if (free.size() > 0) { f.table = free.get(0); f.flag("spot:table"); }
        if (free.size() > 1) { f.furnace = free.get(1); f.flag("spot:furnace"); }
        f.flag("quarry");
        scan(lv, feet, f);
        JsonObject m = c.minePlace();
        if (m != null && m.has("dir")) f.flag("mine");
        return f;
    }

    /** The stock view counts the bag too: only the rest is "in storage". */
    static Map<String, Integer> minus(Map<String, Integer> totals, Map<String, Integer> bag) {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Integer> e : totals.entrySet()) {
            int n = e.getValue() - bag.getOrDefault(e.getKey(), 0);
            if (n > 0) out.put(e.getKey(), n);
        }
        return out;
    }

    /** Logs, stone and ores within 16 blocks (8 up and down): the trees, stone and ore:<family> facts. */
    static void scan(ClientLevel lv, int[] c, PlanFacts f) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        boolean stoneBelow = false;
        for (int dx = -16; dx <= 16; dx++) for (int dz = -16; dz <= 16; dz++) for (int dy = -8; dy <= 8; dy++) {
            m.set(c[0] + dx, c[1] + dy, c[2] + dz);
            if (!lv.isLoaded(m)) continue;
            String id = lv.getBlockState(m).getBlock().getDescriptionId();
            if (id.endsWith("_log") || id.endsWith("_stem")) f.flag("trees");
            else if (id.endsWith("_ore")) {
                String fam = id.substring(id.lastIndexOf('.') + 1).replaceFirst("^deepslate_", "").replaceFirst("_ore$", "");
                f.flag("ore:" + fam);
            } else if (dy < 0 && Math.abs(dx) <= 10 && Math.abs(dz) <= 10 && (id.endsWith(".stone") || id.endsWith(".deepslate") || id.endsWith(".andesite")
                    || id.endsWith(".diorite") || id.endsWith(".granite") || id.endsWith(".tuff"))) stoneBelow = true;
        }
        if (stoneBelow) f.flag("stone");
    }

    // ---- the verbs ----

    JsonObject plansStore() {
        JsonObject b = c.brainData();
        if (!b.has("plans") || !b.get("plans").isJsonObject()) b.add("plans", new JsonObject());
        return b.getAsJsonObject("plans");
    }

    /** "plan <goal>": the dry run. */
    String plan(LocalPlayer p, String rest) {
        try {
            GoalGrammar.Goal g = GoalGrammar.parse(rest, n -> c.policyArea(n) != null);
            if (g.error() != null) return g.error().replace("goal ", "plan ").replace("usage: plan camp", "usage: plan camp");
            if (g.chain() != null) return "plan " + g.text() + ": a ready chain, no search: " + g.chain();
            String existing = routineText(g.routine());
            if (RoutineRule.decide(existing, plannedText(g.routine())) == RoutineRule.Decision.KEEP)
                return "plan " + g.text() + ": your routine " + g.routine() + " is the ready chain: " + String.join(" > ", Texts.splitChain(existing)) + " (routine delete " + g.routine() + " to plan afresh)";
            Planner.Result r = search(p, g);
            if (!r.ok()) return failText(g, r);
            if (r.steps().isEmpty()) return "plan " + g.text() + ": nothing to do - I already have it";
            return "plan " + g.text() + " (dry run, " + r.steps().size() + " steps, cost " + r.cost() + ", " + r.nodes() + " nodes, " + r.ms() + " ms): " + r.costed();
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] plan: {}", e.toString());
            return "error: plan: " + e;
        }
    }

    Planner.Result search(LocalPlayer p, GoalGrammar.Goal g) {
        PlanFacts f = facts(p);
        Planner.Result r = Planner.plan(f, g.needs());
        plans++;
        lastMs = r.ms();
        lastNodes = r.nodes();
        if (!r.ok()) failures++;
        LOG.info("[entropybot] plan: {} -> {} ({} nodes, {} ms; {})", g.text(), r.ok() ? r.chain() : r.missing() != null ? "missing " + r.missing() : r.error(), r.nodes(), r.ms(), f.summary());
        return r;
    }

    static String failText(GoalGrammar.Goal g, Planner.Result r) {
        if (r.error() != null) return "error: no plan for " + g.text() + ": " + r.error() + " - try a smaller goal";
        return "error: no plan for " + g.text() + ": missing " + r.missing();
    }

    String routineText(String name) {
        JsonObject r = c.chainsRef().routines();
        return r.has(name) ? r.get(name).getAsString() : null;
    }

    String plannedText(String name) {
        JsonObject pl = plansStore();
        return pl.has(name) && pl.get(name).isJsonObject() && pl.getAsJsonObject(name).has("chain") ? pl.getAsJsonObject(name).get("chain").getAsString() : null;
    }

    /** "goal <text>" | "goal show [name]". */
    String goal(String from, LocalPlayer p, String rest) {
        try {
            String t = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
            if (t.equals("show") || t.startsWith("show ")) return show(t.substring(4).trim());
            GoalGrammar.Goal g = GoalGrammar.parse(t, n -> c.policyArea(n) != null);
            if (g.error() != null) return g.error();
            if (g.chain() != null) return c.vocab.noteGoal(g.text(), g.chain());
            String name = g.routine();
            String existing = routineText(name);
            RoutineRule.Decision d = RoutineRule.decide(existing, plannedText(name));
            String head;
            if (d == RoutineRule.Decision.KEEP) {
                head = "goal " + g.text() + ": your routine " + name + " is the ready chain (" + String.join(" > ", Texts.splitChain(existing)) + ")";
            } else {
                Planner.Result r = search(p, g);
                if (!r.ok()) return failText(g, r);
                if (r.steps().isEmpty()) return "ok: goal " + g.text() + ": I already have it";
                String body = r.chain();
                String saved = c.chainsRef().routineCommand("save " + name + " " + body);
                if (!saved.startsWith("saved")) return "error: goal " + g.text() + ": found a plan but couldn't save it (" + saved + "): " + r.costed();
                JsonObject rec = new JsonObject();
                rec.addProperty("goal", g.text());
                rec.addProperty("chain", body);
                rec.addProperty("cost", r.cost());
                rec.addProperty("at", System.currentTimeMillis());
                plansStore().add(name, rec);
                c.brainData().addProperty("lastPlan", name);
                c.saved();
                head = "goal " + g.text() + ": planned " + r.steps().size() + " steps (cost " + r.cost() + ")" + (d == RoutineRule.Decision.OVERWRITE ? ", the old plan replaced" : "")
                        + ", saved as routine " + name + ": " + String.join(" > ", Texts.splitChain(body));
            }
            // run it now when nothing runs, else the brain takes it up later
            if (!c.chainsRef().running() && !c.jobs.running()) {
                String s = c.chainsRef().startChain(from, name, name, 1);
                return head + (s.startsWith("started") ? " - running it now" : " - " + s);
            }
            return head + " - " + c.vocab.noteGoal(g.text(), name).replaceFirst("^ok: ", "");
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] goal: {}", e.toString());
            return "error: goal: " + e;
        }
    }

    String show(String name) {
        String n = name.isEmpty() ? (c.brainData().has("lastPlan") ? c.brainData().get("lastPlan").getAsString() : null) : name;
        if (n == null) return "no plan yet - goal <form> (" + GoalGrammar.FORMS + ")";
        if (!n.startsWith("goal_")) n = GoalGrammar.routineName(n.replace(' ', '_'));
        String text = routineText(n);
        if (text == null) return "I have no routine " + n + " (goal <form> plans one; routines lists them)";
        JsonObject pl = plansStore();
        String meta = pl.has(n) && pl.get(n).isJsonObject() ? " (planned for goal " + pl.getAsJsonObject(n).get("goal").getAsString() + ", cost " + pl.getAsJsonObject(n).get("cost").getAsInt()
                + (text.trim().equals(plannedText(n)) ? "" : "; edited since: yours now") + ")" : " (yours)";
        return n + meta + ": " + String.join(" > ", Texts.splitChain(text));
    }

    /**
     * The brain's hook (BRAIN_PLAN 6.1 "needs with no ready chain go to the planner"): a chain for a goal text, the
     * routine when one is ready, else a fresh plan saved as the routine. Cached 60 s per text; null when no plan.
     */
    String chainFor(String goalText) {
        try {
            Object[] hit = hookCache.get(goalText);
            long now = System.currentTimeMillis();
            if (hit != null && now - (Long) hit[0] < 60_000) return (String) hit[1];
            String chain = null;
            LocalPlayer p = Minecraft.getInstance().player;
            GoalGrammar.Goal g = GoalGrammar.parse(goalText, n -> c.policyArea(n) != null);
            if (p != null && g.error() == null) {
                if (g.chain() != null) chain = g.chain();
                else if (routineText(g.routine()) != null && RoutineRule.decide(routineText(g.routine()), plannedText(g.routine())) == RoutineRule.Decision.KEEP) chain = g.routine();
                else {
                    Planner.Result r = search(p, g);
                    if (r.ok() && !r.steps().isEmpty() && c.chainsRef().routineCommand("save " + g.routine() + " " + r.chain()).startsWith("saved")) {
                        JsonObject rec = new JsonObject();
                        rec.addProperty("goal", g.text());
                        rec.addProperty("chain", r.chain());
                        rec.addProperty("cost", r.cost());
                        rec.addProperty("at", now);
                        plansStore().add(g.routine(), rec);
                        c.saved();
                        chain = g.routine();
                    }
                }
            }
            hookCache.put(goalText, new Object[]{now, chain});
            return chain;
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] plan: brain hook: {}", e.toString());
            return null;
        }
    }

    String actions(String rest) { return ActionTable.shortText(rest); }

    /** "plan: N plans, M failed, last N nodes in M ms" for status/debug. */
    String counters() { return "plans " + plans + ", failed " + failures + ", last " + lastNodes + " nodes in " + lastMs + " ms"; }

    // ---- actions.json ----

    /** The state summary a model needs with the table: stage, bag, needs, goals, areas, places, the brain's last why, the job. */
    JsonObject stateSummary(LocalPlayer p) {
        JsonObject s = new JsonObject();
        s.addProperty("stage", c.brainRuntime != null ? c.brainRuntime.stage() : "?");
        JsonObject bag = new JsonObject();
        Map<String, Integer> inv = Gui.inventory(p);
        int k = 0;
        for (Map.Entry<String, Integer> e : inv.entrySet()) {
            if (k++ >= 40) break;
            bag.addProperty(Texts.shortId(e.getKey()), e.getValue());
        }
        s.add("bag", bag);
        s.addProperty("freeSlots", c.freeSlots());
        int[] h = Jobs.here(p);
        s.addProperty("at", h[0] + " " + h[1] + " " + h[2]);
        JsonObject b = c.brainData();
        if (b.has("needs")) s.add("needs", b.get("needs").deepCopy());
        JsonArray goals = new JsonArray();
        if (b.has("goals") && b.get("goals").isJsonArray()) for (JsonElement e : b.getAsJsonArray("goals")) {
            try { goals.add(e.getAsJsonObject().get("text").getAsString()); } catch (RuntimeException ignored) {}
        }
        s.add("goals", goals);
        JsonArray areas = new JsonArray();
        try {
            for (JsonElement e : c.effectiveAreas()) {
                JsonObject a = e.getAsJsonObject();
                areas.add((a.has("name") ? a.get("name").getAsString() : "box") + (a.has("type") ? " " + a.get("type").getAsString() : ""));
            }
        } catch (RuntimeException ignored) {}
        s.add("areas", areas);
        JsonObject places = new JsonObject();
        for (Map.Entry<String, JsonObject> e : Core.INSTANCE.knowledge.places().entrySet()) {
            int[] xyz = BrainRuntime.xyz(e.getValue());
            if (xyz != null) places.addProperty(e.getKey(), xyz[0] + " " + xyz[1] + " " + xyz[2]);
        }
        s.add("places", places);
        JsonArray routines = new JsonArray();
        for (String r : c.chainsRef().routines().keySet()) routines.add(r);
        s.add("routines", routines);
        String job = c.chainsRef().running() ? c.chainsRef().chainStatus() : c.jobs.running() ? c.jobs.job.status : null;
        s.addProperty("job", job == null ? "idle" : job);
        if (c.brainRuntime != null) {
            String why = c.brainRuntime.brain.why();
            s.addProperty("brain", c.brainRuntime.brain.on() ? "on" : "off");
            s.addProperty("why", why.length() > 600 ? why.substring(0, 600) + "..." : why);
        }
        return s;
    }

    /** Every 100 ticks: actions.json again when the summary changed. Never throws. */
    void tick(long tick) {
        if (tick % 100 != 0) return;
        try {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p == null || Minecraft.getInstance().level == null || Core.INSTANCE.files() == null) return;
            JsonObject state = stateSummary(p);
            String key = state.toString();
            if (key.equals(lastActions)) return;
            lastActions = key;
            JsonObject doc = ActionTable.document(state);
            doc.addProperty("t", System.currentTimeMillis());
            String r = Core.INSTANCE.files().writeJson("actions.json", doc.toString());
            if (r != null && r.startsWith("error") && actionsErrors++ < 5) LOG.warn("[entropybot] plan: actions.json: {}", r);
        } catch (RuntimeException e) {
            if (actionsErrors++ < 5) LOG.warn("[entropybot] plan: actions.json: {}", e.toString());
        }
    }

    /** The whole document now (the fast channel's GET /actions). */
    JsonObject document() {
        LocalPlayer p = Minecraft.getInstance().player;
        JsonObject doc = ActionTable.document(p == null ? null : stateSummary(p));
        doc.addProperty("t", System.currentTimeMillis());
        return doc;
    }
}
