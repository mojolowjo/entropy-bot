package io.github.mojolowjo.entropybot.recorder;

import java.util.List;

/**
 * B7e contract: the flight recorder (E5) as the rest of the mod sees it.
 * <ul>
 *   <li>Core holds one in {@code Core.recorder} ({@link #NONE} until E5 installs its own in {@code Core}'s ready block)
 *       and calls {@link #tick} every client tick inside a world; {@code Jobs.finish} calls {@link #jobEnded}.</li>
 *   <li>E1's {@code debug} verbs read it: {@code debug changes}, {@code debug trail}, {@code debug incident},
 *       {@code debug blocks ... at <time>}. E5's own {@code recorder} verb changes its settings.</li>
 *   <li>The read methods may be called from the fast-channel thread: implementations must be thread-safe and must
 *       never touch the level (they read the recorder's own copies and files).</li>
 *   <li>Times are epoch milliseconds (System.currentTimeMillis()); dims are "overworld", "the_nether", "the_end" or a
 *       modded id, as {@code Guard.dimOf} gives them. Block ids are full ids ("minecraft:stone").</li>
 * </ul>
 */
public interface Recorder {

    /** A block change the client heard. {@code from}/{@code to} are block ids (with state only when states are on). */
    record Change(long atMs, String dim, int x, int y, int z, String from, String to, boolean byBot) {}

    /** One trail point: where the bot was, and a note when a job started or ended there (else null). */
    record TrailPoint(long atMs, String dim, int x, int y, int z, String note) {}

    /** An incident on disk: its number (1 = the oldest kept), when, why (the job's end message) and the file name. */
    record Incident(int n, long atMs, String reason, String file) {}

    /**
     * A box as the recorder knows it at a time: {@code ids[((y - y1) * dz + (z - z1)) * dx + (x - x1)]} with
     * dx = x2 - x1 + 1, dz = z2 - z1 + 1; null where the recorder knows nothing. {@code note} says how good it is
     * ("exact" or "from the baseline of <time>; changes after that unknown").
     */
    record Slice(int x1, int y1, int z1, int x2, int y2, int z2, String[] ids, String note) {}

    // ---- hooks (game thread) ----

    /** Every client tick while in a world. */
    default void tick(long tick) {}

    /** A job ended (type as in Jobs: "travel", "seq", ...; label = what it was doing; msg = its end message). */
    default void jobEnded(String type, String label, String msg) {}

    // ---- read side (any thread) ----

    /** False for {@link #NONE} and while the recorder is off. */
    default boolean enabled() { return false; }

    /** The {@code recorder} verb's status text: settings, disk use, the last incidents. */
    default String summary() { return "the recorder is not installed"; }

    /** Changes within r blocks (cube) of x y z since sinceMs, newest first, at most max. */
    default List<Change> changes(String dim, int x, int y, int z, int r, long sinceMs, int max) { return List.of(); }

    /** The trail since sinceMs, oldest first, at most max (the newest max when there are more). */
    default List<TrailPoint> trail(long sinceMs, int max) { return List.of(); }

    /** The kept incidents, newest first, at most max. */
    default List<Incident> incidents(int max) { return List.of(); }

    /** One incident as text (the box summary, trail and changes), or null when there is no incident n. */
    default String incident(int n) { return null; }

    /** The box as it was at atMs; null when the recorder has nothing for it. At most 4096 cells (else null). */
    default Slice blocksAt(String dim, int x1, int y1, int z1, int x2, int y2, int z2, long atMs) { return null; }

    Recorder NONE = new Recorder() {};
}
