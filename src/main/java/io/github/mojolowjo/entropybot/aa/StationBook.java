package io.github.mojolowjo.entropybot.aa;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * D14 (stations placed once, remembered, reused): the station memory's facts beyond the ledger's placements - every
 * station the bot placed or saw (a crafting table, a furnace, a chest: by block id), its cell, who placed it, when it was
 * last seen there, and whether it was still there when last looked at. A station found gone when reached is kept as
 * GONE with its reason (rule 7: the line says why; the planner re-prices without it, since the lookups drop a gone one).
 * One book per world (rule 23: the lookups, the place step's note and the forget path all write here; the restore ledger
 * keeps only which placements are mine). Saved beside the mains ({@code commands.MainStationsLive}: stations-book.json).
 * Pure (JUnit: D14StationsTest). Loader notes: none (Gson only).
 */
public final class StationBook {
    /** One station: its block, cell, mine (the bot placed it), last seen (ms), there when last looked at, the reason it is gone. */
    public record Rec(String block, int x, int y, int z, boolean mine, long lastSeen, boolean present, String goneWhy) {
        public String at() { return x + " " + y + " " + z; }
    }

    private final Map<String, Rec> recs = new LinkedHashMap<>();

    /** This world's book (set by the world's load; never null: an empty book until then). */
    public static volatile StationBook current = new StationBook();
    /** The clock the hooks stamp with (JUnit sets it). */
    public static volatile LongSupplier clock = System::currentTimeMillis;
    /** Saves the book after a change (set by the world's load; null: not saved, said by the loader). */
    public static volatile Runnable saver;

    public static StationBook now() { return current; }

    private static String key(int x, int y, int z) { return x + "," + y + "," + z; }

    private static String full(String id) { return id == null ? null : id.contains(":") ? id : "minecraft:" + id; }

    /** The bot placed {@code block} at the cell: mine, present, seen now. */
    public synchronized Rec placed(String block, int x, int y, int z, long now) {
        Rec r = new Rec(full(block), x, y, z, true, now, true, null);
        recs.put(key(x, y, z), r);
        changed();
        return r;
    }

    /** The station {@code block} was seen at the cell now (a lookup read it there); a gone record of it comes back. */
    public synchronized Rec seen(String block, int x, int y, int z, boolean mine, long now) {
        Rec old = recs.get(key(x, y, z));
        boolean wasMine = old != null && full(block).equals(old.block()) && old.mine();
        Rec r = new Rec(full(block), x, y, z, mine || wasMine, now, true, null);
        recs.put(key(x, y, z), r);
        if (old == null || !old.present() || !old.block().equals(r.block())) changed();
        return r;
    }

    /** The station at the cell is gone ({@code why}); the line for the log (null record: nothing known there, said). */
    public synchronized String gone(int x, int y, int z, String why, long now) {
        Rec old = recs.get(key(x, y, z));
        if (old == null) return "stations: nothing in the station book at " + x + " " + y + " " + z + " to mark gone (" + why + ")";
        recs.put(key(x, y, z), new Rec(old.block(), x, y, z, old.mine(), now, false, why));
        changed();
        return "stations: the " + old.block().replaceFirst("^minecraft:", "") + " at " + old.at() + " is gone: " + why + " (marked gone; the planner prices without it)";
    }

    /** The record at the cell, or null. */
    public synchronized Rec at(int x, int y, int z) { return recs.get(key(x, y, z)); }

    /** The cell holds a station the book knows is gone. */
    public synchronized boolean goneAt(int x, int y, int z) {
        Rec r = recs.get(key(x, y, z));
        return r != null && !r.present();
    }

    /** Every record, oldest first. */
    public synchronized List<Rec> all() { return new ArrayList<>(recs.values()); }

    private void changed() {
        Runnable s = saver;
        if (s == null) return;
        try { s.run(); } catch (RuntimeException e) { io.github.mojolowjo.entropybot.Quiet.warn("stations: the station book was not saved", e); }
    }

    public synchronized JsonObject toJson() {
        JsonArray a = new JsonArray();
        for (Rec r : recs.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("block", r.block());
            o.addProperty("x", r.x());
            o.addProperty("y", r.y());
            o.addProperty("z", r.z());
            o.addProperty("mine", r.mine());
            o.addProperty("lastSeen", r.lastSeen());
            o.addProperty("present", r.present());
            if (r.goneWhy() != null) o.addProperty("goneWhy", r.goneWhy());
            a.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("stations", a);
        return root;
    }

    /** Reads what {@link #toJson} wrote; a broken entry is skipped and counted in {@code note}. */
    public static StationBook fromJson(JsonObject root, StringBuilder note) {
        StationBook b = new StationBook();
        if (root == null || !root.has("stations") || !root.get("stations").isJsonArray()) return b;
        int bad = 0;
        for (JsonElement e : root.getAsJsonArray("stations")) {
            try {
                JsonObject o = e.getAsJsonObject();
                Rec r = new Rec(o.get("block").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                        o.get("mine").getAsBoolean(), o.get("lastSeen").getAsLong(), o.get("present").getAsBoolean(),
                        o.has("goneWhy") ? o.get("goneWhy").getAsString() : null);
                b.recs.put(key(r.x(), r.y(), r.z()), r);
            } catch (RuntimeException ex) {
                bad++;
            }
        }
        if (note != null && bad > 0) note.append(bad).append(" broken entr").append(bad == 1 ? "y" : "ies").append(" skipped");
        return b;
    }
}
