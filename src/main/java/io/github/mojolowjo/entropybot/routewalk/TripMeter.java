package io.github.mojolowjo.entropybot.routewalk;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Measures one trip of {@code route test} from the event ring (plan section 1, as changed by the review): Baritone's
 * path events (kind "path") and its debug lines (kind "log", printed only with {@code chatDebug} on). Pure: the game
 * side feeds it {@code (tick, kind, text)} in order.
 *
 * <ul>
 * <li>searches: {@code CALC_STARTED} + {@code NEXT_SEGMENT_CALC_STARTED}.</li>
 * <li>segments: path segments that started to run ({@code CALC_FINISHED_NOW_EXECUTING},
 * {@code CONTINUING_ONTO_PLANNED_NEXT}, {@code SPLICING_ONTO_NEXT_EARLY}).</li>
 * <li>stopped ticks between segments: from a segment's end ({@code PATH_FINISHED_NEXT_STILL_CALCULATING},
 * {@code AT_GOAL} of a leg, {@code CANCELED}, a failed calculation) to the next segment that runs. The wait before
 * the first segment is {@code firstStepTicks}, not counted as stopped; a stop still open when the trip ends is not
 * counted either (the trip is over).</li>
 * <li>nodes: the sum of "PathNode map size: N"; movements: the sum of "Took Xms, N movements considered"
 * (both from {@code AStarPathFinder}, verified in Baritone 1.11.3).</li>
 * </ul>
 */
public final class TripMeter {
    static final Pattern NODES = Pattern.compile("PathNode map size: (\\d+)");
    static final Pattern TOOK = Pattern.compile("Took (\\d+)ms, (\\d+) movements considered");

    private final long startTick;
    private int searches, segments, failures, debugLines;
    private long nodes, movements, searchMs;
    private long stoppedTicks;
    private long firstStepTicks = -1;
    private boolean running;
    private long stopSince = -1;
    private boolean lost;

    public TripMeter(long startTick) {
        this.startTick = startTick;
    }

    /** One event from the ring, in order. */
    public void event(long tick, String kind, String text) {
        if (text == null) return;
        if ("log".equals(kind)) {
            Matcher m = NODES.matcher(text);
            if (m.find()) {
                nodes += Long.parseLong(m.group(1));
                debugLines++;
            }
            m = TOOK.matcher(text);
            if (m.find()) {
                searchMs += Long.parseLong(m.group(1));
                movements += Long.parseLong(m.group(2));
                debugLines++;
            }
            return;
        }
        if (!"path".equals(kind)) return;
        switch (text) {
            case "CALC_STARTED", "NEXT_SEGMENT_CALC_STARTED" -> searches++;
            case "CALC_FINISHED_NOW_EXECUTING", "CONTINUING_ONTO_PLANNED_NEXT", "SPLICING_ONTO_NEXT_EARLY" -> {
                segments++;
                if (firstStepTicks < 0) firstStepTicks = Math.max(0, tick - startTick);
                else if (!running && stopSince >= 0) stoppedTicks += Math.max(0, tick - stopSince);
                running = true;
                stopSince = -1;
            }
            case "PATH_FINISHED_NEXT_STILL_CALCULATING", "AT_GOAL", "CANCELED" -> stop(tick);
            case "CALC_FAILED", "NEXT_CALC_FAILED" -> {
                failures++;
                stop(tick);
            }
            default -> {
            }
        }
    }

    private void stop(long tick) {
        if (running) {
            running = false;
            stopSince = tick;
        }
    }

    /** The ring dropped events between two reads (the numbers may be low). */
    public void markLost() { lost = true; }

    public int searches() { return searches; }

    public int segments() { return segments; }

    public int failures() { return failures; }

    public long nodes() { return nodes; }

    public long movements() { return movements; }

    public long searchMs() { return searchMs; }

    /** Debug lines seen (0 = chatDebug was off or the logger isn't hooked: nodes unknown). */
    public int debugLines() { return debugLines; }

    public double stoppedSeconds() { return stoppedTicks / 20.0; }

    /** Seconds to the first segment that ran, -1 when none ran. */
    public double firstStepSeconds() { return firstStepTicks < 0 ? -1 : firstStepTicks / 20.0; }

    public boolean lost() { return lost; }
}
