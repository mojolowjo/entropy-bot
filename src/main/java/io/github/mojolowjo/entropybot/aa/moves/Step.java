package io.github.mojolowjo.entropybot.aa.moves;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * rm-execution: one typed step of an option. AA's option sets build a list of these; {@link Steps} runs them one after
 * the other over {@link Steps.Hands} (Baritone underneath, the item actions through their own jobs). A step says what to
 * achieve and where (a site, a cell, a count); Baritone decides where to stand, where to look and what to click.
 * {@link #text()} is the step's line for logs, {@code aa why} and the failure's stage; it is never parsed back into a
 * step by AA. Pure (no game classes).
 */
public sealed interface Step {
    /** The line for logs and the failure's stage ("mine iron 1 at 1 2 3 dig"). */
    String text();

    /** Walk to x y z: within {@code near} blocks (0 = onto that block). Baritone GoalBlock / GoalNear. */
    record Walk(int x, int y, int z, int near) implements Step {
        public String text() { return "goto " + x + " " + y + " " + z + (near > 0 ? " near " + near : ""); }
    }

    /** Mine one known ore cell (and its vein) of {@code family}: the mine verb's per-ore step over Baritone's MineProcess. */
    record MineAt(String family, int x, int y, int z, boolean dig) implements Step {
        public String text() { return "mine " + family + " 1 at " + x + " " + y + " " + z + (dig ? " dig" : " walk"); }
    }

    /** {@code n} ore of {@code family} the mine verb's own way ({@link How}): the known ores, a cave, a strip mine. */
    record Mine(String family, int n, How how) implements Step {
        public enum How { KNOWN, DIG, CAVE, STRIP }

        public String text() {
            return switch (how) {
                case KNOWN -> "mine " + family + " " + n;
                case DIG -> "mine " + family + " " + n + " dig";
                case CAVE -> "mine cave " + family + " " + n;
                case STRIP -> "mine strip " + family + " " + n;
            };
        }
    }

    /**
     * Baritone's own tree mining: its MineProcess on the log blocks ({@code log}: one block id, null = every block the
     * game tags as a log) until {@code logs} more are in the bag. {@code at}: the seen tree (Sightings) the walk before
     * it went to, or null (Baritone mines the nearest it knows).
     */
    record ChopTree(int[] at, int logs, String log) implements Step {
        public ChopTree {
            at = at == null ? null : at.clone();
        }

        public String text() {
            return "cut " + logs + (log == null ? "" : " " + log) + (at == null ? "" : " at " + at[0] + " " + at[1] + " " + at[2]);
        }
    }

    /**
     * Hunt {@code n} of the animal {@code type} (an entity id) near {@code at} ({x, z}, null = in view): the walk is a
     * Baritone GoalNear on the animal, the strike stays the reflexes' input. {@code forItem}: the drop wanted (null = its
     * raw meat), {@code forCount} of it.
     */
    record HuntAt(String type, int n, int[] at, String forItem, int forCount) implements Step {
        public HuntAt {
            at = at == null ? null : at.clone();
        }

        public HuntAt(String type, int n, int[] at) { this(type, n, at, null, 0); }

        public String text() {
            String t = type.replaceFirst("^minecraft:", "");
            return "hunt " + n + " " + t + (forItem == null ? "" : " for " + forItem.replaceFirst("^minecraft:", "") + " " + forCount)
                    + (at == null ? "" : " at " + at[0] + " " + at[1]);
        }
    }

    /** Place blocks: cells.get(i) gets items.get(i), a schematic leg for Baritone's BuilderProcess. */
    record Build(List<int[]> cells, List<String> items) implements Step {
        public Build {
            List<int[]> c = new ArrayList<>();
            for (int[] x : cells) c.add(x.clone());
            cells = List.copyOf(c);
            items = List.copyOf(items);
            if (cells.size() != items.size() || cells.isEmpty()) throw new IllegalArgumentException("build: " + cells.size() + " cells, " + items.size() + " items");
        }

        public static Build one(String item, int x, int y, int z) { return new Build(List.of(new int[]{x, y, z}), List.of(item)); }

        public String text() {
            StringBuilder b = new StringBuilder("place");
            for (int i = 0; i < cells.size(); i++) {
                int[] c = cells.get(i);
                b.append(i == 0 ? " " : ", ").append(items.get(i)).append(' ').append(c[0]).append(' ').append(c[1]).append(' ').append(c[2]);
            }
            return b.toString();
        }
    }

    /** Craft {@code n} of {@code item} (an item action: the craft job, no Baritone). */
    record Craft(String item, int n) implements Step {
        public String text() { return "craft " + item + " " + n; }
    }

    /** Smelt {@code n} of {@code item} at a furnace ({@code here}: the one from the bag, on the spot). */
    record Smelt(String item, int n, boolean here) implements Step {
        public String text() { return "smelt " + item + " " + n + (here ? " here" : ""); }
    }

    /** Cook {@code n} raw {@code item} (the smelt job's food form). */
    record Cook(String item, int n) implements Step {
        public String text() { return "cook " + item + " " + n; }
    }

    /**
     * Baritone's MineProcess on the blocks that give {@code item} until {@code n} more are in the bag (cobblestone from
     * stone: the game's drop data says which blocks).
     */
    record Collect(String item, int n) implements Step {
        public String text() { return "collect " + item + " " + n; }
    }

    /** Take {@code n} of {@code item} from storage into the bag (a storage action: the get job). */
    record Fetch(String item, int n) implements Step {
        public String text() { return "get " + item + " " + n; }
    }

    /** Take the furnaces' finished output (a storage action). */
    record SmeltCollect() implements Step {
        public String text() { return "smelt collect all"; }
    }

    /** A shell round me from the blocks I carry (the shelter job). */
    record Shelter() implements Step {
        public String text() { return "shelter"; }
    }

    /** Wait {@code seconds}. */
    record Wait(int seconds) implements Step {
        public String text() { return "wait " + seconds; }
    }

    /** Climb out of the hole I am in (the climb verb: Baritone's way up). */
    record Climb() implements Step {
        public String text() { return "climb"; }
    }

    /** Read the chest just placed or reached (a storage action). */
    record Scan() implements Step {
        public String text() { return "scan"; }
    }

    /** Find {@code n} caves by walking (the explore verb's cave search). */
    record FindCave(int n) implements Step {
        public String text() { return "find cave " + n; }
    }

    /**
     * Dig the box {@code from}-{@code to} (a staircase leg): the dig job on Baritone's builder ({@code ores}: keep the
     * ores it meets; {@code viaEngine}: the engine's own break order).
     */
    record Dig(int[] from, int[] to, boolean ores, boolean viaEngine) implements Step {
        public Dig {
            from = from.clone();
            to = to.clone();
        }

        public String text() {
            return "dig " + from[0] + " " + from[1] + " " + from[2] + " " + to[0] + " " + to[1] + " " + to[2] + (ores ? " ores" : "") + (viaEngine ? " via engine" : "");
        }
    }

    /** Mark a strip mine here or at x y z facing {@code dir} ({@code at} null = here). */
    record MarkMine(int[] at, String dir) implements Step {
        public MarkMine {
            at = at == null ? null : at.clone();
        }

        public String text() { return "place mine " + (at == null ? "" : at[0] + " " + at[1] + " " + at[2] + " ") + dir; }
    }

    /** Take down what the option placed ({@code keep}: keep the pillar as a way up), within {@code radius}. */
    record Restore(boolean keep, int radius) implements Step {
        public String text() { return keep ? "restore keep pillar" : "restore placed " + radius; }
    }

    // ---- the step list's lines ----

    /** The steps' lines joined by " > " (logs, {@code aa why}; an option's label). Never parsed back. */
    static String label(List<? extends Step> steps) {
        List<String> t = new ArrayList<>();
        for (Step s : steps) t.add(s.text());
        return String.join(" > ", t);
    }

    /** "mine", "goto", "cut"... the first word of the step's line (the failure classifiers read it). */
    static String verb(Step s) {
        String t = s.text();
        int i = t.indexOf(' ');
        return (i < 0 ? t : t.substring(0, i)).toLowerCase(Locale.ROOT);
    }
}
