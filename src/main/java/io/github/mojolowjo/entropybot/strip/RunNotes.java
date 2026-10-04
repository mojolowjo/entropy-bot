package io.github.mojolowjo.entropybot.strip;

import io.github.mojolowjo.entropybot.gui.GuiCore;
import io.github.mojolowjo.entropybot.storage.StorageRules;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B7d D2: what a strip-mine errand adds up for its report (the bridge's job.dug / oresMined / oreItems / oresFound /
 * liquidStops / baseTrip / baseNote / setupNote / toolNote / skipNote) and the report itself (minedone). As in the
 * bridge the totals run over the whole errand (a "mine strip" of several runs keeps counting); the setup, tool and
 * skip notes start over after each report. Pure.
 */
public final class RunNotes {
    public int dug, oresMined, oresFound, liquidStops;
    public final Map<String, Integer> oreItems = new LinkedHashMap<>();
    public boolean baseTrip, turned;
    public String baseNote, setupNote, toolNote, skipNote;

    private static final Pattern BROKE = Pattern.compile("broke (\\d+) blocks"), LEFT = Pattern.compile("(\\d+) ores left in place"),
            MINED = Pattern.compile("(\\d+) ores mined");

    /** A dig's report counted in (finishJob's after-totals): blocks broken, ores left listed, ores mined, a stop at water or lava. */
    public void addClear(String msg) {
        if (msg == null) return;
        Matcher m = BROKE.matcher(msg);
        if (m.find()) dug += Integer.parseInt(m.group(1));
        m = LEFT.matcher(msg);
        if (m.find()) oresFound += Integer.parseInt(m.group(1));
        m = MINED.matcher(msg);
        if (m.find()) oresMined += Integer.parseInt(m.group(1));
        if (msg.contains("water/lava")) liquidStops++;
    }

    /** noteGains: the valuables a collecting dig added to the bag since `before`. */
    public void addGains(Map<String, Integer> before, Map<String, Integer> now) {
        if (before == null) return;
        for (Map.Entry<String, Integer> e : now.entrySet()) {
            if (!StorageRules.VALUABLE.matcher(e.getKey()).find()) continue;
            int d = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (d > 0) oreItems.merge(e.getKey(), d, Integer::sum);
        }
    }

    public void addSkip(String text) { skipNote = (skipNote != null ? skipNote + ", " : "") + text; }

    /**
     * minedone's report: "51 blocks dug, 7 ores mined (9 raw_iron, 2 coal), took 71 items to base - next run digs
     * branch 57". putMoved: the items the base trip put away. The setup, tool and skip notes are said once.
     */
    public String doneNote(int k, int putMoved) {
        String note = dug + " blocks dug"
                + (oresMined != 0 ? ", " + oresMined + " ores mined (" + orTop() + ")" : "")
                + (oresFound != 0 ? ", " + oresFound + " ores left for you (PM \"ores\")" : "")
                + (liquidStops != 0 ? ", " + liquidStops + " tunnel(s) stopped short of water/lava" : "")
                + (baseTrip ? ", took " + putMoved + " items to base" : "") + (baseNote != null ? ", " + baseNote : "")
                + (setupNote != null ? ", " + setupNote : "") + (toolNote != null ? ", " + toolNote : "") + (skipNote != null ? ", " + skipNote : "")
                + " - next run digs branch " + (k + 1);
        setupNote = toolNote = skipNote = null;
        return note;
    }

    private String orTop() {
        String t = GuiCore.top(oreItems, 3);
        return t.isEmpty() ? "no drops picked up" : t;
    }
}
