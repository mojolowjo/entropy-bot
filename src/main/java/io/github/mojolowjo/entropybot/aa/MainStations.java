package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * main-stations (0.39.z35, owner 2026-10-23): the owner's MAIN station of each kind in this world (aa station main, aa
 * deposit x y z). One main per kind. The kinds are the chain's own: the recipe kinds whose station NeedChain uses
 * ({@link ItemWorth#CRAFT}, {@link ItemWorth#SMELT}) and the deposit leg's container fact ({@link #CONTAINER}); a word names a
 * kind when it is the path of a block of that kind's set (the game's data: "crafting_table", "furnace", "chest") or the
 * last part of the station item's path ("table"). Rule 1: a main is a price, not a rule: {@code station.mainBonusS}
 * seconds off its walk in the station lookup ({@link Nearby#lookup}) and in the deposit's chest choice. Saved per world in
 * its own file beside the station memory (commands.MainStationsLive: stations-main.json). Pure (JUnit: MainStationsTest).
 */
public final class MainStations {
    /** The deposit leg's kind: the known containers (NearbyLive.containers). */
    public static final String CONTAINER = "container";

    /** The chain's station kinds, in the help's order. */
    public static final List<String> KINDS = List.of(ItemWorth.CRAFT, ItemWorth.SMELT, CONTAINER);

    /** One main: its kind, its cell and the block it was marked on. */
    public record Main(String kind, int x, int y, int z, String block) {
        public String at() { return x + " " + y + " " + z; }
        public boolean is(int px, int py, int pz) { return x == px && y == py && z == pz; }
    }

    private final Map<String, Main> mains = new LinkedHashMap<>();

    /** This world's mains (set by commands.MainStationsLive.load; null: none loaded, every main lookup says so). */
    public static volatile MainStations current;

    /** The mains of the current world, or none (not loaded is no main: logged once by the loader, never a quiet default). */
    public static MainStations now() {
        MainStations m = current;
        return m == null ? new MainStations() : m;
    }

    /** The bonus of a main (Tuning station.mainBonusS). */
    public static double bonusS() { return Tuning.get("station.mainBonusS"); }

    /** The short word of a kind (the help, the lines): its station item's path, or "chest" for the container kind. */
    public static String word(String kind) {
        if (CONTAINER.equals(kind)) return "chest";
        String item = ItemWorth.stationOf(kind);
        return item == null ? kind : item.replaceFirst("^.*:", "");
    }

    /**
     * The kind a word names, from each kind's block set ({@code blocksOf}: kind -> its blocks, null: not loaded): the word is a
     * block path of the set, or the last "_" part of one (crafting_table -> table). Null: no kind.
     */
    public static String kindOf(String word, Function<String, Set<String>> blocksOf) {
        if (word == null || word.isBlank()) return null;
        String w = word.trim().toLowerCase().replaceFirst("^minecraft:", "");
        for (String k : KINDS) {
            Set<String> ids = blocksOf.apply(k);
            if (ids == null) continue;
            for (String id : ids) {
                String path = id.replaceFirst("^.*:", "");
                if (path.equals(w) || path.substring(path.lastIndexOf('_') + 1).equals(w)) return k;
            }
        }
        return null;
    }

    /** The kind of a block (the one the crosshair is on): the first kind whose set holds it. Null: no station. */
    public static String kindOfBlock(String block, Function<String, Set<String>> blocksOf) {
        if (block == null) return null;
        for (String k : KINDS) {
            Set<String> ids = blocksOf.apply(k);
            if (ids != null && ids.contains(block)) return k;
        }
        return null;
    }

    /** Marks the main of its kind; the line (and what it replaced). */
    public synchronized String mark(Main m) {
        Main was = mains.put(m.kind(), m);
        return "main " + word(m.kind()) + ": " + m.block() + " at " + m.at() + (was == null || was.equals(m) ? "" : " (was " + was.block() + " at " + was.at() + ")");
    }

    /** Drops the main of a kind; the line. */
    public synchronized String clear(String kind) {
        Main was = mains.remove(kind);
        return was == null ? "no main " + word(kind) + " to clear" : "main " + word(kind) + " cleared (was " + was.block() + " at " + was.at() + ")";
    }

    public synchronized Main of(String kind) { return mains.get(kind); }

    /** The main at the cell whose block is in the set (a lookup's spot), or null. */
    public synchronized Main at(int x, int y, int z, Set<String> blocks) {
        for (Main m : mains.values()) if (m.is(x, y, z) && (blocks == null || blocks.contains(m.block()))) return m;
        return null;
    }

    public synchronized List<Main> all() { return List.copyOf(mains.values()); }

    /** "mains: crafting_table 1 64 2 | furnace none | chest none". */
    public synchronized String line() {
        List<String> out = new ArrayList<>();
        for (String k : KINDS) {
            Main m = mains.get(k);
            out.add(word(k) + " " + (m == null ? "none" : m.block().replaceFirst("^minecraft:", "") + " " + m.at()));
        }
        return "mains: " + String.join(" | ", out);
    }

    // ---- the file (stations-main.json: {"mains":[{"kind":..,"x":..,"y":..,"z":..,"block":..}]})

    public synchronized com.google.gson.JsonObject toJson() {
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (Main m : mains.values()) {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("kind", m.kind());
            o.addProperty("x", m.x());
            o.addProperty("y", m.y());
            o.addProperty("z", m.z());
            o.addProperty("block", m.block());
            a.add(o);
        }
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.add("mains", a);
        return root;
    }

    /** Reads the file; an entry that cannot be read goes into {@code note} (never dropped quietly). */
    public static MainStations fromJson(com.google.gson.JsonObject root, StringBuilder note) {
        MainStations s = new MainStations();
        if (root == null || !root.has("mains") || !root.get("mains").isJsonArray()) {
            if (note != null) note.append("no mains list");
            return s;
        }
        for (var e : root.getAsJsonArray("mains")) {
            try {
                var o = e.getAsJsonObject();
                String kind = o.get("kind").getAsString();
                if (!KINDS.contains(kind)) throw new IllegalArgumentException("kind " + kind + " is not a station kind");
                s.mains.put(kind, new Main(kind, o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(), o.get("block").getAsString()));
            } catch (RuntimeException ex) {
                if (note != null) note.append(note.length() > 0 ? "; " : "").append("skipped ").append(e).append(": ").append(ex.getMessage());
            }
        }
        return s;
    }
}
