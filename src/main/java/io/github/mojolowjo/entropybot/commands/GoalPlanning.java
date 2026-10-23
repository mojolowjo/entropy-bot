package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.Quiet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.plan.ActionTable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * one-planner: the old goal planner is gone (AA's chain, {@code NeedChain}, is the one planner). What stays here: the
 * {@code goal <item> [n]} verb (the owner's order through the chain, as {@code aa want}), the {@code plan <item> [n]} dry
 * run (the chain line, nothing runs), {@code actions [verb]} and {@code entropybot/actions.json} (the action table plus a
 * state summary, rewritten when the summary changes, checked every 5 s), and the "a tree seen" fact. Loader notes: vanilla
 * client getters only. Errors: every entry point catches and answers "error: ..." and logs once; a failed actions.json
 * write is counted.
 */
final class GoalPlanning {
    private static final Logger LOG = LogUtils.getLogger();
    private final Commands c;
    private String lastActions;
    private long actionsErrors;
    GoalPlanning(Commands c) {
        this.c = c;
        List<String> bad = ActionTable.selfTest();
        if (!bad.isEmpty()) LOG.warn("[entropybot] plan: the action table's self-test failed: {}", bad);
    }

    /** simple-tools: "a tree seen" is the one Nearby fact (Baritone's scan, then the sightings), not a block scan of its own. */
    boolean treeSeen() {
        return (c.aaRuntime != null ? c.aaRuntime.nearbyLog() : c.nearby.log(null)) != null;
    }

    String actions(String rest) { return ActionTable.shortText(rest); }

    // ---- the verbs: the chain ----

    // ---- actions.json ----

    /** The state summary a model needs with the table: stage, bag, needs, goals, areas, places, the job. */
    JsonObject stateSummary(LocalPlayer p) {
        JsonObject s = new JsonObject();
        s.addProperty("stage", c.senses != null ? c.senses.stage() : "?");
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
            try { goals.add(e.getAsJsonObject().get("text").getAsString()); } catch (RuntimeException caught) { Quiet.warn("GoalPlanning.stateSummary failed, carrying on with the fallback", caught); }
        }
        s.add("goals", goals);
        JsonArray areas = new JsonArray();
        try {
            for (JsonElement e : c.places()) {
                JsonObject a = e.getAsJsonObject();
                areas.add((a.has("name") ? a.get("name").getAsString() : "box") + (a.has("type") ? " " + a.get("type").getAsString() : ""));
            }
        } catch (RuntimeException caught) { Quiet.warn("GoalPlanning.stateSummary failed, carrying on with the fallback", caught); }
        s.add("areas", areas);
        JsonObject places = new JsonObject();
        for (Map.Entry<String, JsonObject> e : Core.INSTANCE.knowledge.places().entrySet()) {
            int[] xyz = SenseRuntime.xyz(e.getValue());
            if (xyz != null) places.addProperty(e.getKey(), xyz[0] + " " + xyz[1] + " " + xyz[2]);
        }
        s.add("places", places);
        JsonArray routines = new JsonArray();
        for (String r : c.chainsRef().routines().keySet()) routines.add(r);
        s.add("routines", routines);
        String job = c.chainsRef().running() ? c.chainsRef().chainStatus() : c.jobs.running() ? c.jobs.job.status : null;
        s.addProperty("job", job == null ? "idle" : job);
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
