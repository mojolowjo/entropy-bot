package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.guard.AreaType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V1a (0.22.0): areas.json version 1 -> 2, once. Every area -> neutral; the area named base/home, else the smallest one
 * holding the {@code base} place -> main; every protect box -> a safe area (same box and y range; protect_N when it had
 * no name, name_safe when an area already had its name); the build zone in commands.json -> a destroy area "zone"; old
 * verb forms in saved routines and rules rewritten ({@link OldWords}). The near-me settings, "strict" and everything
 * else in both files stay as they are. Pure: the caller backs the files up first and saves them after. Idempotent: a
 * version 2 file is left alone.
 */
public final class AreaMigration {
    private AreaMigration() {}

    public static final String BACKUP_AREAS = "areas.bak-0.22.0.json", BACKUP_COMMANDS = "commands.bak-0.22.0.json";

    /** ran: something was migrated; lines: one per change (for the log); summary: the whisper. */
    public record Result(boolean ran, List<String> lines, String summary) {}

    /**
     * The whole step: the backups of both files first ({@code write.apply(name, json)} answers "ok..." or an error; a
     * failed backup refuses the migration: an IllegalStateException with why), then {@link #migrate}. Not needed: ran false.
     */
    public static Result withBackup(JsonObject areas, Map<String, JsonObject> places, JsonObject brain, java.util.function.BiFunction<String, String, String> write) {
        if (!needed(areas)) return new Result(false, List.of(), null);
        String b1 = write.apply(BACKUP_AREAS, areas.toString());
        if (b1 == null || !b1.startsWith("ok")) throw new IllegalStateException("the backup " + BACKUP_AREAS + " failed (" + b1 + ")");
        if (brain != null) {
            String b2 = write.apply(BACKUP_COMMANDS, brain.toString());
            if (b2 == null || !b2.startsWith("ok")) throw new IllegalStateException("the backup " + BACKUP_COMMANDS + " failed (" + b2 + ")");
        }
        return migrate(areas, places, brain);
    }

    public static boolean needed(JsonObject areas) {
        return areas == null || !areas.has("version") || !areas.get("version").isJsonPrimitive() || areas.get("version").getAsInt() < PolicyCommands.VERSION;
    }

    /**
     * @param areas  areas.json's object (changed in place)
     * @param places the place notes (name -> {x, y, z, dim}); may be null
     * @param brain  commands.json's object (changed in place: zone, routines, rules); may be null
     */
    public static Result migrate(JsonObject areas, Map<String, JsonObject> places, JsonObject brain) {
        List<String> lines = new ArrayList<>();
        if (!needed(areas)) return new Result(false, lines, null);
        if (!areas.has("areas") || !areas.get("areas").isJsonArray()) areas.add("areas", new JsonArray());
        if (!areas.has("protect") || !areas.get("protect").isJsonArray()) areas.add("protect", new JsonArray());
        JsonArray as = areas.getAsJsonArray("areas"), ps = areas.getAsJsonArray("protect");
        Set<String> names = new LinkedHashSet<>();
        // areas: a "safe" entry that sat in areas moves to protect; the rest neutral unless typed
        JsonArray keep = new JsonArray();
        for (JsonElement e : as) {
            JsonObject b = e.getAsJsonObject();
            if (PolicyCommands.typeOf(b) == AreaType.SAFE) { ps.add(b); continue; }
            keep.add(b);
        }
        areas.add("areas", keep);
        as = keep;
        for (JsonElement e : as) names.add(PolicyCommands.nameOf(e.getAsJsonObject()).toLowerCase());
        // main: by name first, else the smallest area holding the base place
        JsonObject main = null;
        for (JsonElement e : as) {
            String n = PolicyCommands.nameOf(e.getAsJsonObject()).toLowerCase();
            if (n.equals("base") || n.equals("home")) { main = e.getAsJsonObject(); break; }
        }
        JsonObject base = places == null ? null : places.get("base");
        if (main == null && base != null && base.has("x") && base.has("z")) {
            int bx = base.get("x").getAsInt(), by = base.has("y") ? base.get("y").getAsInt() : 64, bz = base.get("z").getAsInt();
            String bd = base.has("dim") ? base.get("dim").getAsString() : PolicyCommands.DEFAULT_DIM;
            long best = Long.MAX_VALUE;
            for (JsonElement e : as) {
                JsonObject b = e.getAsJsonObject();
                if (b.has("round") || !PolicyCommands.dimOf(b).equals(bd) || PolicyCommands.boxGap(b, bx, by, bz) > 0) continue;
                long size = (long) (PolicyCommands.n(b, "x2") - PolicyCommands.n(b, "x1") + 1) * (PolicyCommands.n(b, "z2") - PolicyCommands.n(b, "z1") + 1);
                if (size < best) { best = size; main = b; }
            }
        }
        int nNeutral = 0, nMain = 0, nSafe = 0, nDestroy = 0;
        for (JsonElement e : as) {
            JsonObject b = e.getAsJsonObject();
            AreaType t = PolicyCommands.typeOf(b);
            if (b == main && !b.has("type")) {
                b.addProperty("type", AreaType.MAIN.word());
                t = AreaType.MAIN;
                lines.add("area " + PolicyCommands.nameOf(b) + " -> main (it holds the base)");
            } else if (!b.has("type")) {
                lines.add("area " + PolicyCommands.nameOf(b) + " -> neutral");
            }
            switch (t) {
                case MAIN -> nMain++;
                case DESTROY -> nDestroy++;
                default -> nNeutral++;
            }
        }
        int k = 1;
        for (JsonElement e : ps) {
            JsonObject b = e.getAsJsonObject();
            String old = b.has("name") && !b.get("name").isJsonNull() ? b.get("name").getAsString() : null;
            String name = old;
            if (name == null) {
                while (names.contains("protect_" + k)) k++;
                name = "protect_" + k++;
            }
            if (names.contains(name.toLowerCase())) name = uniq(names, name + "_safe");
            names.add(name.toLowerCase());
            b.addProperty("name", name);
            b.addProperty("type", AreaType.SAFE.word());
            nSafe++;
            lines.add("protect box " + (old == null ? "(no name)" : old) + " -> safe area " + name + " (" + PolicyCommands.boxText(b) + ")");
        }
        // the build zone (commands.json "zone", both corners) -> a destroy area
        if (brain != null && brain.has("zone") && brain.get("zone").isJsonObject() && zoneComplete(brain.getAsJsonObject("zone"))) {
            JsonObject z = brain.getAsJsonObject("zone");
            try {
                String name = uniq(names, "zone");
                names.add(name);
                JsonObject b = PolicyCommands.makeBox(name, (z.has("dim") && !z.get("dim").isJsonNull() ? z.get("dim").getAsString() : PolicyCommands.DEFAULT_DIM), z.get("x1").getAsInt(), z.get("z1").getAsInt(), z.get("x2").getAsInt(), z.get("z2").getAsInt(),
                        z.get("y1").getAsInt(), z.get("y2").getAsInt());
                b.addProperty("type", AreaType.DESTROY.word());
                as.add(b);
                brain.remove("zone");
                nDestroy++;
                lines.add("build zone -> destroy area " + name + " (" + PolicyCommands.boxText(b) + ")");
            } catch (RuntimeException ex) {
                lines.add("build zone not moved (" + ex.getMessage() + ")");
            }
        }
        // saved routines and rules: old verb forms -> the new ones
        if (brain != null && brain.has("routines") && brain.get("routines").isJsonObject()) {
            JsonObject r = brain.getAsJsonObject("routines");
            for (String key : new ArrayList<>(r.keySet())) {
                if (!r.get(key).isJsonPrimitive()) continue;
                String nw = OldWords.rewriteChain(r.get(key).getAsString());
                if (nw == null) continue;
                lines.add("routine " + key + ": \"" + r.get(key).getAsString() + "\" -> \"" + nw + "\"");
                r.addProperty(key, nw);
            }
        }
        if (brain != null && brain.has("rules") && brain.get("rules").isJsonArray()) {
            int i = 0;
            for (JsonElement e : brain.getAsJsonArray("rules")) {
                i++;
                if (!e.isJsonObject() || !e.getAsJsonObject().has("text")) continue;
                JsonObject o = e.getAsJsonObject();
                String nw = OldWords.rewriteChain(o.get("text").getAsString());
                if (nw == null) continue;
                lines.add("rule #" + i + ": \"" + o.get("text").getAsString() + "\" -> \"" + nw + "\"");
                o.addProperty("text", nw);
            }
        }
        areas.addProperty("version", PolicyCommands.VERSION);
        String summary = "areas: moved to types: " + nNeutral + " neutral, " + nMain + " main, " + nDestroy + " destroy, " + nSafe + " safe";
        return new Result(true, lines, summary);
    }

    static boolean zoneComplete(JsonObject z) {
        for (String k : new String[]{"x1", "y1", "z1", "x2", "y2", "z2"}) if (!z.has(k)) return false;
        return true;
    }

    private static String uniq(Set<String> names, String want) {
        String n = want;
        int i = 2;
        while (names.contains(n.toLowerCase())) n = want + i++;
        return n;
    }
}
