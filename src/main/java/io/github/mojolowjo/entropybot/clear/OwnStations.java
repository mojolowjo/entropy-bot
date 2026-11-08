package io.github.mojolowjo.entropybot.clear;

import io.github.mojolowjo.entropybot.Quiet;

import java.util.Set;

/**
 * 0.33.0 (A3, Haiku run item 6: "own crafting table can't be broken even with force"): a station (crafting table,
 * furnace, chest...) that the bot placed itself (the ledger says so) may be picked up again by a {@code force} dig.
 * Every other block entity stays protected. Pure apart from the {@link #mine} hook, which the game side sets (the
 * restore ledger's "built: station" entries). Loader notes: none.
 */
public final class OwnStations {
    private OwnStations() {}

    public interface Mine {
        boolean at(int x, int y, int z);
    }

    /** Set by the game side: the cell holds a station I placed myself (ledger). Null: none. */
    public static volatile Mine mine;

    /** A workstation or container block of the game (it opens a menu: ItemWorth's station), vanilla ids only. */
    public static boolean station(String id) {
        if (id == null) return false;
        String s = id.toLowerCase();
        if (s.indexOf(':') >= 0 && !s.startsWith("minecraft:")) return false;
        return io.github.mojolowjo.entropybot.aa.brain.ItemWorth.station(s);
    }

    /** 0.33.0 review: the stations that hold items (the game's Container block entities); one is picked up only when it is known to be empty. */
    public static boolean container(String id) {
        return id != null && io.github.mojolowjo.entropybot.aa.brain.ItemWorth.container(id.toLowerCase());
    }

    /**
     * 0.33.0 review: may this station of mine be picked up? null = yes, else the reason. A station counts as mine only
     * when the ledger's record matches the cell, its kind and the placement: the cell has not lost that block since it
     * was placed (removedSince: a player who broke it and put their own of the same kind there makes it theirs). A
     * container is picked up only when its contents were read after the placement and were empty (contents: the item
     * count read then; null = never read since: unknown), since breaking it would throw its items out.
     */
    public static String pickUp(String recordedKind, String nowKind, boolean removedSince, String id, Integer contents, int x, int y, int z) {
        String at = x + " " + y + " " + z;
        if (recordedKind == null || nowKind == null || !recordedKind.replaceFirst("^minecraft:", "").equals(nowKind.replaceFirst("^minecraft:", "")))
            return "the " + nowKind + " at " + at + " is not the one I placed";
        if (removedSince) return "the " + nowKind + " at " + at + " was broken and put back since I placed it: not mine any more";
        if (container(id)) {
            if (contents == null) return "the " + nowKind + " at " + at + " may hold items (I haven't looked inside since I placed it): open it or take its items first";
            if (contents > 0) return "the " + nowKind + " at " + at + " holds " + contents + " items: take them first (take all " + at + ")";
        }
        return null;
    }

    /**
     * The contents count a container's pickup check goes by: the chest note's count when it was read after the placement,
     * else 0 when the bot itself emptied the machine (a furnace it took everything out of; those are not kept as chest
     * notes) at or after the placement, else null (unknown). Pure.
     * The newer of the two reads wins.
     * @param noteCount the note's count when its read is newer than the placement, else null
     * @param noteSeen  when that note was read (ms)
     * @param emptiedAt when the bot emptied it (ms), or null
     * @param placedAt  when it was placed (ms)
     */
    public static Integer contentsKnown(Integer noteCount, long noteSeen, Long emptiedAt, long placedAt) {
        boolean emptied = emptiedAt != null && emptiedAt >= placedAt;
        if (noteCount != null && (!emptied || noteSeen > emptiedAt)) return noteCount;
        return emptied ? 0 : null;
    }

    /** Set by the game side: my recorded station cells inside the box x1 y1 z1 x2 y2 z2 (never the whole box read). */
    public interface Cells {
        java.util.List<int[]> in(int x1, int y1, int z1, int x2, int y2, int z2);
    }

    public static volatile Cells cells;

    /** Set by the game side: why the station of mine at the cell is not picked up (null: it may be). */
    public interface Why {
        String at(int x, int y, int z);
    }

    public static volatile Why why;

    /** My recorded station cells in the box (empty when the hook is not set; never throws). */
    public static java.util.List<int[]> cellsIn(int x1, int y1, int z1, int x2, int y2, int z2) {
        Cells c = cells;
        if (c == null) return java.util.List.of();
        try {
            return c.in(x1, y1, z1, x2, y2, z2);
        } catch (RuntimeException e) {
            Quiet.warn("OwnStations.cellsIn failed, carrying on with the fallback", e);
            return java.util.List.of();
        }
    }

    /** Why a recorded station is refused, or null (never throws; a failure is a refusal saying so). */
    public static String whyNot(int x, int y, int z) {
        Why w = why;
        if (w == null) return "no station records loaded";
        try {
            return w.at(x, y, z);
        } catch (RuntimeException e) {
            return "the station check failed: " + e;
        }
    }

    /** station-memory (0.39.z14): one station I placed, as the ledger recorded it (this dimension). */
    public record Placed(int x, int y, int z, String block) {}

    /** Set by the game side: every station I placed in this dimension (the ledger's "built: station" entries). */
    public interface Placements {
        java.util.List<Placed> all();
    }

    /** Set by the game side: drop the station record at the cell from the ledger (it is gone); the line for the log. */
    public interface Forget {
        String at(int x, int y, int z, String why);
    }

    /** Set by commands.RestoreLive.load (the world's ledger): {@code OwnStations.placed = this::stationsPlaced}. */
    public static volatile Placements placed;
    /** Set by commands.RestoreLive.load: {@code OwnStations.forget = this::forgetStation}. */
    public static volatile Forget forget;

    /** Where an unset hook's warning goes (the game's log; JUnit captures it). */
    public static volatile java.util.function.Consumer<String> warn = Quiet::line;
    private static volatile boolean warnedPlaced, warnedForget;

    /** A world's ledger was loaded: an unset hook warns again (once per world). */
    public static void newWorld() {
        warnedPlaced = false;
        warnedForget = false;
    }

    private static void unset(String which) {
        try { warn.accept("station memory: the " + which + " hook is not set"); } catch (RuntimeException e) { Quiet.warn("OwnStations: the warning sink failed", e); }
    }

    /** Every station I placed in this dimension (the hook unset: logged once per world, then empty; a failure is logged, never silent). */
    public static java.util.List<Placed> placed() {
        Placements m = placed;
        if (m == null) {
            if (!warnedPlaced) {
                warnedPlaced = true;
                unset("placements");
            }
            return java.util.List.of();
        }
        try {
            return m.all();
        } catch (RuntimeException e) {
            Quiet.warn("OwnStations.placed failed, the station memory has no placements of mine this time", e);
            return java.util.List.of();
        }
    }

    /**
     * station-memory: the station at the cell is gone (the bot arrived and the block is not that station any more): its
     * record leaves the memory. Returns the log line (what was dropped, or why nothing was).
     */
    public static String forget(int x, int y, int z, String why) {
        Forget f = forget;
        if (f == null) {
            if (!warnedForget) {
                warnedForget = true;
                unset("forget");
            }
            return "no station records loaded: nothing to forget at " + x + " " + y + " " + z;
        }
        try {
            String line = f.at(x, y, z, why);
            // D14 (rule 7): the station book keeps it as GONE with the reason; the planner prices without it
            String gone = io.github.mojolowjo.entropybot.aa.StationBook.now().gone(x, y, z, why, io.github.mojolowjo.entropybot.aa.StationBook.clock.getAsLong());
            io.github.mojolowjo.entropybot.Quiet.line(gone);
            return line;
        } catch (RuntimeException e) {
            Quiet.warn("OwnStations.forget failed at " + x + " " + y + " " + z, e);
            return "the station record at " + x + " " + y + " " + z + " could not be dropped: " + e;
        }
    }

    /** Notes a placement of mine in the ledger (the game side: RestoreLive.noteBuilt). */
    public interface Noter {
        void note(int[] cell, String blockId, String why);
    }

    /**
     * station-note (0.39.z15): the chain's place-near step ended DONE with the cell the fork chose: a station or container
     * is noted {@code built: station} (as the place verb does), any other block is left alone (null back). A station with
     * no cell from the fork is a warning, never a quiet skip. Returns the log line, or null for a non-station.
     */
    public static String afterPlaceNear(String item, int[] cell, Noter noter) {
        if (item == null) return null;
        String path = item.toLowerCase().replaceFirst("^minecraft:", "");
        if (!station(path) && !container(path)) return null;
        if (cell == null) {
            String line = "stations: placed a " + path + " but the fork gave no cell: not noted as mine";
            warn.accept(line);
            return line;
        }
        noter.note(cell.clone(), "minecraft:" + path, "built: station");
        io.github.mojolowjo.entropybot.aa.StationBook.now().placed("minecraft:" + path, cell[0], cell[1], cell[2], io.github.mojolowjo.entropybot.aa.StationBook.clock.getAsLong());   // D14: the station book's record
        return "stations: noted my " + path + " at " + cell[0] + " " + cell[1] + " " + cell[2];
    }

    /** The cell holds a station of mine (never throws). */
    public static boolean mineAt(int x, int y, int z) {
        Mine m = mine;
        if (m == null) return false;
        try {
            return m.at(x, y, z);
        } catch (RuntimeException e) {
            Quiet.warn("OwnStations.mineAt failed, carrying on with the fallback", e);
            return false;
        }
    }
}
