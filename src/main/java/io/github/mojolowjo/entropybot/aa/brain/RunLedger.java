package io.github.mojolowjo.entropybot.aa.brain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AA round 2, piece C: one row per AA run (turned on to turned off or world unload) for entropybot/runs.csv, and the
 * milestone clock that fills three of its columns. Pure: the adapter ({@code AaRuntime.appendRun}) writes the file.
 * A milestone column is minutes since AA turned on when the band was first met, or -1 when it never was.
 */
public final class RunLedger {
    private RunLedger() {}

    public static final String HEADER = "version,world,difficulty,min_stone_pick,min_table_furnace,min_iron_pick,picks,switches,"
            + "pointless,interrupts,stuck_halts,deaths,food_events,total_min";

    /** One run. Minutes are doubles (-1 = band never met); the rest are counts. */
    public record Row(String version, String world, String difficulty, double stonePick, double tableFurnace, double ironPick,
                      long picks, long switches, long pointless, long interrupts, long stuckHalts, long deaths,
                      long foodEvents, double totalMin) {}

    /** First time each band was met, plus the food-bar edge counter. Fed once per tick with the run's elapsed ms. */
    public static final class Marks {
        private long stoneMs = -1, tfMs = -1, ironMs = -1, foodEvents;
        private boolean foodLow;

        /** pickTier 2 = stone pickaxe or better, 3 = iron or better. */
        public void tick(long elapsedMs, int pickTier, boolean table, boolean furnace, int food) {
            if (stoneMs < 0 && pickTier >= 2) stoneMs = elapsedMs;
            if (tfMs < 0 && table && furnace) tfMs = elapsedMs;
            if (ironMs < 0 && pickTier >= 3) ironMs = elapsedMs;
            boolean low = food < 6;
            if (low && !foodLow) foodEvents++;
            foodLow = low;
        }

        public double stoneMin() { return min(stoneMs); }
        public double tableFurnaceMin() { return min(tfMs); }
        public double ironMin() { return min(ironMs); }
        public long foodEvents() { return foodEvents; }

        private static double min(long ms) { return ms < 0 ? -1 : ms / 60000.0; }
    }

    /** The CSV line (no newline). Text fields lose commas and line breaks so a row always has the header's columns. */
    public static String line(Row r) {
        return String.join(",", clean(r.version()), clean(r.world()), clean(r.difficulty()), m(r.stonePick()), m(r.tableFurnace()),
                m(r.ironPick()), Long.toString(r.picks()), Long.toString(r.switches()), Long.toString(r.pointless()),
                Long.toString(r.interrupts()), Long.toString(r.stuckHalts()), Long.toString(r.deaths()),
                Long.toString(r.foodEvents()), m(r.totalMin()));
    }

    /** What to append: the header first when the file is new or empty, then the row, each ending in a newline. */
    public static String append(boolean fileHasContent, Row r) {
        return (fileHasContent ? "" : HEADER + "\n") + line(r) + "\n";
    }

    /** The last n data rows of a file's text (the header and blank lines skipped), oldest first. */
    public static List<String> last(String fileText, int n) {
        List<String> rows = new ArrayList<>();
        if (fileText == null) return rows;
        for (String l : fileText.split("\\R")) if (!l.isBlank() && !l.equals(HEADER)) rows.add(l.trim());
        return rows.size() <= n ? rows : new ArrayList<>(rows.subList(rows.size() - n, rows.size()));
    }

    /** The aa stats block: the header, then the last n rows; says so when there are none. */
    public static String statsText(String fileText, int n) {
        List<String> rows = last(fileText, n);
        if (rows.isEmpty()) return "runs: none yet (a row is written when aa turns off or the world unloads)";
        return "runs (last " + rows.size() + "):\n" + HEADER + "\n" + String.join("\n", rows);
    }

    private static String m(double v) { return v < 0 ? "-1" : String.format(Locale.ROOT, "%.1f", v); }

    private static String clean(String s) { return s == null ? "" : s.replace(',', ';').replace('\n', ' ').replace('\r', ' ').trim(); }
}
