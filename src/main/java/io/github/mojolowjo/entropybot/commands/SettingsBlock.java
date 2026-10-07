package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * The {@code settings} block of state.json (package C, 2026-10-03): the current values the dashboard's settings page
 * shows next to its buttons. Read-only: every control on the page sends the command the owner could PM. Pure (no
 * game classes): commands.json and areas.json come in as they are, the live values as {@link Live}.
 *
 * <pre>
 * {guard: {strict, mode}, areas: [box], protect: [box], supplies: {id: n}, torches: n,
 *  autominer: {on, pausedUntil, orePrefer}, deathPolicy, parked: {at, why}|null, reconnect, defend,
 *  routines: [{name, text, running}], chain: "status"|null, hotbar: {"1": kind}, toolOres: "iron"|"cheapest"}
 * </pre>
 * The hotbar and the tool-ore setting are package B's: read raw from commands.json ("hotbar", "toolOres"), absent =
 * an empty layout and "iron".
 */
public final class SettingsBlock {
    private SettingsBlock() {}

    /** What the game knows right now (not in the files). chainName/chainStatus null when no chain runs. */
    public record Live(boolean strict, String guardMode, boolean defend, boolean reconnect, String orePrefer,
                       String chainName, String chainStatus) {}

    public static JsonObject build(JsonObject brain, JsonObject areas, Live live) {
        JsonObject b = brain == null ? new JsonObject() : brain, a = areas == null ? new JsonObject() : areas;
        JsonObject out = new JsonObject();

        JsonObject g = new JsonObject();
        g.addProperty("strict", live.strict());
        g.addProperty("mode", live.guardMode() == null ? (live.strict() ? "strict" : "log") : live.guardMode());
        out.add("guard", g);
        out.add("areas", boxes(a, "areas"));
        out.add("protect", boxes(a, "protect"));

        JsonObject sup = new JsonObject();
        int torches = 0;
        if (b.has("supplies") && b.get("supplies").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : b.getAsJsonObject("supplies").entrySet()) {
                int n = intOf(e.getValue());
                sup.addProperty(e.getKey(), n);
                if (e.getKey().equals("minecraft:torch") || e.getKey().equals("torch")) torches = n;
            }
        }
        out.add("supplies", sup);
        out.addProperty("torches", torches);

        JsonObject am = new JsonObject(), ab = obj(b, "autominer");
        am.addProperty("on", ab != null && bool(ab.get("on"), false));
        am.addProperty("pausedUntil", ab == null ? 0 : longOf(ab.get("pausedUntil")));
        if (live.orePrefer() == null || live.orePrefer().isEmpty()) am.add("orePrefer", JsonNull.INSTANCE);
        else am.addProperty("orePrefer", live.orePrefer());
        out.add("autominer", am);

        out.addProperty("deathPolicy", bool(b.get("deathPolicy"), true));
        JsonObject parked = obj(b, "parked");
        out.add("parked", parked == null ? JsonNull.INSTANCE : parked.deepCopy());
        out.addProperty("reconnect", live.reconnect());
        out.addProperty("defend", live.defend());
        out.addProperty("creepers", io.github.mojolowjo.entropybot.engine.CreeperSetting.load(b).word());   // B7e C: flee|melee|bow

        JsonArray rs = new JsonArray();
        JsonObject routines = obj(b, "routines");
        if (routines != null) {
            for (Map.Entry<String, JsonElement> e : routines.entrySet()) {
                JsonObject r = new JsonObject();
                r.addProperty("name", e.getKey());
                r.addProperty("text", e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue().toString());
                r.addProperty("running", live.chainName() != null && live.chainName().equalsIgnoreCase(e.getKey()));
                rs.add(r);
            }
        }
        out.add("routines", rs);
        out.add("chain", live.chainStatus() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(live.chainStatus()));

        // package B's keys, raw: {"1": "pickaxe", ...} (slots 1-9 only) and "iron" | "cheapest"
        JsonObject hb = new JsonObject(), hraw = obj(b, "hotbar");
        if (hraw != null) {
            for (int i = 1; i <= 9; i++) {
                JsonElement k = hraw.get(String.valueOf(i));
                if (k != null && k.isJsonPrimitive() && !k.getAsString().isEmpty()) hb.addProperty(String.valueOf(i), k.getAsString());
            }
        }
        out.add("hotbar", hb);
        JsonElement to = b.get("toolOres");
        out.addProperty("toolOres", to != null && to.isJsonPrimitive() && to.getAsString().equals("cheapest") ? "cheapest" : "iron");
        JsonElement tm = b.get("toolMode");                     // V1b-4: tools mode best|cheapest|stone (the old toolOres maps on)
        out.addProperty("toolMode", tm != null && tm.isJsonPrimitive() && tm.getAsString().matches("best|cheapest|stone") ? tm.getAsString()
                : "cheapest".equals(out.get("toolOres").getAsString()) ? "cheapest" : "best");
        out.add("rules", RulesBlock.build(b, System.currentTimeMillis(), java.time.ZoneId.systemDefault()));     // B7e N: the rules card
        out.add("recorder",io.github.mojolowjo.entropybot.recorder.RecorderStatus.json());      // B7e E5
        return out;
    }

    /** The boxes of areas.json as they are (name, dim, x1 z1 x2 z2, y1 y2 when set); anything not an object is skipped. */
    private static JsonArray boxes(JsonObject a, String key) {
        JsonArray out = new JsonArray();
        if (a.has(key) && a.get(key).isJsonArray()) for (JsonElement e : a.getAsJsonArray(key)) if (e.isJsonObject()) out.add(e.deepCopy());
        return out;
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }

    private static boolean bool(JsonElement e, boolean dflt) {
        try {
            return e == null || e.isJsonNull() ? dflt : e.getAsBoolean();
        } catch (RuntimeException x) {
            return dflt;
        }
    }

    private static int intOf(JsonElement e) {
        try {
            return e == null || e.isJsonNull() ? 0 : e.getAsInt();
        } catch (RuntimeException x) {
            return 0;
        }
    }

    private static long longOf(JsonElement e) {
        try {
            return e == null || e.isJsonNull() ? 0 : e.getAsLong();
        } catch (RuntimeException x) {
            return 0;
        }
    }
}
