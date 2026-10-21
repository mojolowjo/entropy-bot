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

    /** Read the chest just placed or reached (a storage action); {@code base}: the base's chests. */
    record Scan(boolean base) implements Step {
        public Scan() { this(false); }

        public String text() { return base ? "scan base" : "scan"; }
    }

    /** Walk new land for {@code minutes} until a cave opening comes into view (the find verb's cave search). */
    record FindCave(int minutes) implements Step {
        public String text() { return "find cave " + minutes; }
    }

    /** Walk new land for {@code minutes} (the explore verb: Baritone's explore goal), the want's way. */
    record Explore(int minutes) implements Step {
        public String text() { return "explore " + minutes; }
    }

    // ---- rm-execution2: the kinds the remaining option sets and desires run ----

    /** Put the bag's surplus into the base chests (a storage action). */
    record Deposit() implements Step {
        public String text() { return "deposit"; }
    }

    /** Throw {@code n} of {@code item} away (junk by its item data, Junk.is). */
    record DropJunk(String item, int n) implements Step {
        public String text() { return "drop " + item + " " + n; }
    }

    /** Place a chest from the bag at x y z (Baritone's builder places it). */
    record PlaceChest(int x, int y, int z) implements Step {
        public String text() { return "place chest " + x + " " + y + " " + z; }
    }

    /** Mark x y z as the base (a fact for the home, no block). */
    record MarkBase(int x, int y, int z) implements Step {
        public String text() { return "base mark " + x + " " + y + " " + z; }
    }

    /** Lie down in the known bed (the sleep job). */
    record Sleep() implements Step {
        public String text() { return "sleep"; }
    }

    /** Dig down and seal over me (Moves.burrow, the fork's ground check). */
    record Burrow() implements Step {
        public String text() { return "burrow"; }
    }

    /** Run from the foes (Moves.flee: Baritone's goal away from them). */
    record Flee() implements Step {
        public String text() { return "flee"; }
    }

    /** Fight the nearest foe (the strike input over Baritone's walk). */
    record Attack() implements Step {
        public String text() { return "attack nearest"; }
    }

    /** Pillar up out of reach (Moves.pillar). */
    record Pillar() implements Step {
        public String text() { return "pillar"; }
    }

    /** Walk to the base (one Baritone goal). */
    record GoBase() implements Step {
        public String text() { return "go base"; }
    }

    /** Build the house in the marked area (the builder). */
    record House(String area) implements Step {
        public String text() { return "house " + area; }
    }

    /** Build the owner's schematic {@code name} at its origin (Baritone's builder, what the bag holds). */
    record BuildSchematic(String name, int x, int y, int z) implements Step {
        public String text() { return "build schematic " + name + " " + x + " " + y + " " + z; }
    }

    /** Walk to the owner. */
    record Come() implements Step {
        public String text() { return "come"; }
    }

    /** Give the owner {@code n} of {@code item}. */
    record Give(String item, int n) implements Step {
        public String text() { return "give " + item + " " + n; }
    }

    /** Torches on a grid within {@code radius} of where I stand. */
    record Light(int radius) implements Step {
        public String text() { return "light here " + radius; }
    }

    /** The bootstrap (its tools part only when {@code toolsOnly}). */
    record Bootstrap(boolean toolsOnly) implements Step {
        public String text() { return toolsOnly ? "bootstrap tools" : "bootstrap"; }
    }

    /**
     * Fish at the nearest water: the rod's cast and reel are item inputs on Baritone's look, the walk to the water is a
     * Baritone goal; until {@code n} fish are in the bag ({@code n} 0: for {@code minutes}). A rod must be in the bag (the
     * option puts a craft step before this one when it has the sticks and string); no rod, no water within reach, no fish
     * in the time are the step's named failure.
     */
    record Fish(int n, int minutes) implements Step {
        public Fish(int minutes) { this(0, minutes); }

        public boolean timed() { return n == 0; }

        public String text() { return timed() ? "fish " + minutes + "m" : "fish " + n + " fish"; }
    }

    /** Cut {@code n} kelp over Baritone's MineProcess on the game's kelp blocks (counted in the bag). */
    record KelpCut(int n) implements Step {
        public String text() { return "gather kelp " + n; }
    }

    /** Cut {@code n} kelp and smelt it to dried kelp (the cut, then the cook). */
    record Kelp(int n) implements Compound {
        public String text() { return "kelp " + n + " cook"; }

        public List<Step> parts() {
            String raw = "kelp";          // the owner's word for the cook verb (cook kelp n)
            return List.of(new KelpCut(n), new Cook(raw, n));
        }
    }

    /** Eat the bag's best food (the eat reflex's verb). */
    record Eat() implements Step {
        public String text() { return "eat"; }
    }

    /** Break the plants in the column box {@code box} ({x1, z1, x2, z2}, the surface) {@code up} high: forage, seeds. */
    record DigArea(int[] box, int up) implements Step {
        public DigArea {
            box = box.clone();
        }

        public String text() { return "dig " + box[0] + " " + box[1] + " " + box[2] + " " + box[3] + " +" + up; }
    }

    /** Plant {@code n} of {@code crop} by water here (the farm job). */
    record FarmPlant(String crop, int n) implements Step {
        public String text() { return "farm plant " + crop + " here " + n; }
    }

    /** Mark the farm at x y z ({@code at} null: here). */
    record MarkFarm(int[] at) implements Step {
        public MarkFarm {
            at = at == null ? null : at.clone();
        }

        public String text() { return "place farm" + (at == null ? "" : " " + at[0] + " " + at[1] + " " + at[2]); }
    }

    /**
     * The planner's routine for {@code goal} ("torch 16", "iron tools"): when the step runs, {@link Steps} asks its hands
     * for the planner's routine (typed once at the planner's side) and runs those steps in its place. AA never builds the
     * routine's text.
     */
    record Plan(String goal) implements Step {
        public String text() { return "plan " + goal; }
    }

    /** A step made of steps: {@link Steps} runs its parts in its place (one machine, one failure path). */
    sealed interface Compound extends Step permits StripLeg, ClearChunk, Kelp {
        List<Step> parts();
    }

    /**
     * A staircase down from x y z heading {@code dir} ({dx, dz}) for {@code levels} levels: per level the 3-high cell is dug
     * (Baritone's builder, ores kept) and walked into; {@code mark}: a strip mine is marked at the bottom.
     */
    record StripLeg(int x, int y, int z, String dir, int dx, int dz, int levels, boolean mark) implements Compound {
        public String text() { return "stairs down " + dir + " " + levels + " from " + x + " " + y + " " + z + (mark ? " and mark a mine" : ""); }

        public List<Step> parts() {
            List<Step> out = new ArrayList<>();
            for (int i = 1; i <= levels; i++) {
                int cx = x + dx * i, cz = z + dz * i;
                out.add(new Dig(new int[]{cx, y - i, cz}, new int[]{cx, y - i + 2, cz}, true, true));
                out.add(new Walk(cx, y - i, cz, 0));
            }
            if (mark) out.add(new MarkMine(null, dir));
            return out;
        }
    }

    /** Dig the box x0..x0+15, y..y+slab-1, z0..z0+15 keeping the ores (the owner's clear-chunk way). */
    record ClearChunk(int x0, int y, int z0, int slab) implements Compound {
        public String text() { return "clear chunk " + x0 + " " + y + " " + z0 + " slab " + slab; }

        public List<Step> parts() { return List.of(new Dig(new int[]{x0, y, z0}, new int[]{x0 + 15, y + slab - 1, z0 + 15}, true, false)); }  // game fact: a chunk is 16 blocks wide (x0 + 15)
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
