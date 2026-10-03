package io.github.mojolowjo.entropybot.clear;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The state of one clear (the bridge's job object of type "clear", minus the walking and breaking that the
 * B7d-part-2 job drives): the box, the options of startClear, and the bookkeeping the engine reads and writes
 * (blocks skipped and why, failed tries, spots it couldn't reach, built blocks and ores left).
 */
public final class ClearJob {
    /** startClear's opts. */
    public static final class Options {
        /** the box to clear (null: the work zone, or the bounding box of {@link #only}) */
        public ClearBox box;
        /** break just these blocks */
        public List<Pos> only;
        public String label;
        /** leave ores in place and list them (default true; never when collecting) */
        public Boolean keepOres;
        /** ores are mined and counted ("N ores mined"); the dumps keep the valuables */
        public boolean collect;
        /** the job never fails its errand by itself: "ok: gave up ..." */
        public boolean soft;
        /** the whole box has to end up open (a strip mine's corridor) */
        public boolean mustFinish;
        /** "dig ... force": building blocks are broken too (never block entities) */
        public boolean force;
        /** never stand (or chase drops) lower than this (a vein clear stays on the walkway) */
        public Integer minStandY;
        /** floor spots to put torches on once they're dug out */
        public List<Pos> torches;
        /** chests to empty the inventory into when full (null: the base chests) */
        public List<Pos> dump;

        public Options box(ClearBox b) { box = b; return this; }
        public Options only(List<Pos> o) { only = o; return this; }
        public Options label(String l) { label = l; return this; }
        public Options keepOres(boolean k) { keepOres = k; return this; }
        public Options collect(boolean c) { collect = c; return this; }
        public Options soft(boolean s) { soft = s; return this; }
        public Options mustFinish(boolean m) { mustFinish = m; return this; }
        public Options force(boolean f) { force = f; return this; }
        public Options minStandY(Integer y) { minStandY = y; return this; }
        public Options torches(List<Pos> t) { torches = t; return this; }
        public Options dump(List<Pos> d) { dump = d; return this; }
    }

    /** A block it tried and gave up on: how often, at which broken-count, and why. */
    public static final class Fail {
        public int n;
        public int at;
        public String why;
    }

    public final ClearBox box;
    /** "x y z" keys of the only blocks to break, or null */
    public final Set<String> only;
    public final String label;
    public final boolean keepOres, collect, soft, mustFinish, force;
    /** true when no box or only list was given (the work zone) */
    public final boolean zone;
    public Integer minStandY;
    public final List<Pos> torches;
    public final List<Pos> dump;
    public final OreBook ores;

    public int broken, brokenAtScan, consecFails, sightBudget, oresMined, protectedCount, craftTries, torchesSkipped;
    /** ores newly listed during this job (the bridge's oresNoted - job.oreBase) */
    public int oresNoted;
    /** targets in the box at the last plan's scan, or null before the first */
    public Integer lastScanLeft;
    public boolean retried, memDirty;
    /** the pickaxe it was using (noTool crafts more of the same kind when stone fails) */
    public String lastPick;
    /** package B: it tried once to make stone pickaxes rather than wear the iron one out on stone (Tools.stonePicksFirst) */
    public boolean stoneTried;

    public final Map<String, String> skip = new LinkedHashMap<>();
    public final Map<String, Fail> fails = new LinkedHashMap<>();
    public final Set<String> noSpot = new LinkedHashSet<>();
    public final Map<String, Integer> badSpots = new HashMap<>();
    public final Map<String, Integer> brokenKeys = new HashMap<>();
    public final Map<String, String> protectedLeft = new LinkedHashMap<>();
    /** ore block id -> mined by this job (the bridge's global oreTally; the wiring adds these to it) */
    public final Map<String, Integer> oreTally = new LinkedHashMap<>();

    private ClearJob(ClearBox box, Set<String> only, boolean zone, Options o, OreBook ores) {
        this.box = box;
        this.only = only;
        this.zone = zone;
        this.label = o.label != null ? o.label : "clearing the zone";
        this.collect = o.collect;
        this.keepOres = !Boolean.FALSE.equals(o.keepOres) && !o.collect;
        this.soft = o.soft;
        this.mustFinish = o.mustFinish;
        this.force = o.force;
        this.minStandY = o.minStandY;
        this.torches = o.torches == null ? new ArrayList<>() : new ArrayList<>(o.torches);
        this.dump = o.dump;
        this.ores = ores != null ? ores : new OreBook.Simple();
    }

    /**
     * startClear's setup: an "only" list becomes its bounding box; no box at all means the work zone
     * ({@code zoneBox}, which may then not be null).
     */
    public static ClearJob start(Options o, ClearBox zoneBox, OreBook ores) {
        ClearBox box = o.box;
        Set<String> only = null;
        if (o.only != null && !o.only.isEmpty()) {
            only = new LinkedHashSet<>();
            for (Pos p : o.only) only.add(p.key());
            box = ClearBox.around(o.only);
        }
        boolean zone = box == null;
        if (zone) {
            if (zoneBox == null) throw new IllegalArgumentException("no box and no zone");
            box = zoneBox;
        }
        return new ClearJob(box, only, zone, o, ores);
    }

    /** The reply to the command that started it. zoneText describes the work zone ("zone -26 53 164 to ..."). */
    public String startedMessage(String zoneText) {
        return "started: " + label + (zone ? " (" + zoneText + ")" : "") + ", top down, one block at a time";
    }

    /** The bridge's clearStatus: "label: what (N broken, ~M left)". */
    public String status(String what) {
        Integer left = lastScanLeft != null ? Math.max(0, lastScanLeft - (broken - brokenAtScan)) : null;
        return label + ": " + what + " (" + broken + " broken" + (left != null ? ", ~" + left + " left" : "") + ")";
    }

    /** The box its break lease covers (with {@link #force} the lease is a force lease). */
    public ClearBox breakLeaseBox() {
        return box;
    }

    /** The box its place lease covers when it places torches (the box plus a 1-block shell), else null. */
    public ClearBox torchLeaseBox() {
        return torches.isEmpty() ? null : box.grow(1);
    }

    public String torchLeaseTask() {
        return label + " (torches)";
    }

    boolean wanted(String key) {
        return only == null || only.contains(key);
    }
}
