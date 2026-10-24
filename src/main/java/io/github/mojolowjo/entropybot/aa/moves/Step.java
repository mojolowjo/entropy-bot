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
    record Mine(String family, int n, How how, int minutes) implements Step {
        public enum How { KNOWN, DIG, CAVE, STRIP }

        /** No time bound (the verb's own default); 0.39.z44: {@code minutes} bounds a cave leg (the cave way). */
        public Mine(String family, int n, How how) { this(family, n, how, 0); }

        public String text() {
            return switch (how) {
                case KNOWN -> "mine " + family + " " + n;
                case DIG -> "mine " + family + " " + n + " dig";
                case CAVE -> "mine cave " + family + " " + n + (minutes > 0 ? " " + minutes + "m" : "");   // 0.39.z44: the cave way's bound
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
     * The owner's strip order: the fork's StripProcess on the ore block ids {@code ores} at Y {@code y} along
     * {@code heading} (north/east/south/west); {@code serial}: the order's time (two orders alike are two runs).
     */
    record Strip(List<String> ores, java.util.Map<String, Integer> amounts, int y, String heading, long serial) implements Step {
        public Strip {
            ores = List.copyOf(ores);
            amounts = java.util.Map.copyOf(amounts);
        }

        public String text() {
            List<String> s = new ArrayList<>();
            for (String o : ores) s.add(o.replaceFirst("^minecraft:", ""));
            List<String> a = new ArrayList<>();
            for (var e : new java.util.TreeMap<>(amounts).entrySet()) a.add(e.getKey() + " " + e.getValue());
            return "strip " + String.join(",", s) + " at feet y " + y + " " + heading + (a.isEmpty() ? " until stopped" : " for " + String.join(", ", a));
        }

        /** The order's identity for its saved progress: the ore set, the Y, the heading (amounts and time left out). */
        public String key() {
            List<String> s = new ArrayList<>(ores);
            s.sort(null);
            return String.join(",", s) + "|" + y + "|" + heading;
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

    /**
     * Walk new land for {@code minutes} (the explore verb: Baritone's explore goal), the want's way. live-facts: {@code until}
     * (an item id, null: none) ends it the moment a source of that item is known (a chain's explore for a missing source).
     */
    record Explore(int minutes, String until, boolean whilePaths) implements Step {
        public Explore(int minutes) { this(minutes, null); }
        public Explore(int minutes, String until) { this(minutes, until, false); }
        /**
         * 0.39.z42: {@code whilePaths}: the chain's ways all wait on a walk's path (computing, chunks not loaded): this explore
         * moves the bot meanwhile and ends the moment a path answer lands (the order is priced again then).
         */
        public String text() { return "explore " + minutes + (until == null ? "" : " until " + until) + (whilePaths ? " while paths compute" : ""); }
    }

    // ---- rm-execution2: the kinds the remaining option sets and desires run ----

    /**
     * Put the bag's surplus into the base chests (a storage action); with {@code order} (the strip order's deposit leg,
     * DepositRule): every bag item outside the kept set into the nearest known chest (or one from the bag / crafted, placed
     * near the tunnel start), its own hands (StepHands' DepositLeg).
     */
    record Deposit(Strip order) implements Step {
        public Deposit() { this(null); }

        public String text() { return order == null ? "deposit" : "deposit the strip's finds"; }
    }

    /** The strip order's walk back to where its bag filled (the saved deposits' back cell), Baritone's goal. */
    record StripBack(Strip order) implements Step {
        public String text() { return "back to the tunnel"; }
    }

    /** Throw {@code n} of {@code item} away (junk by its item data, Junk.is). */
    record DropJunk(String item, int n) implements Step {
        public String text() { return "drop " + item + " " + n; }
    }

    /**
     * Place {@code item} (from the bag) near me: no cell is named by the mod. The fork's {@code IBuilderProcess.placeNear}
     * picks a free, reachable, policy-allowed cell within {@code steps.placeNearR}, the stand and the face; when none fits,
     * its reason ("no cell within 3 blocks", "cannot reach: ...") is the step's failure.
     */
    record PlaceNear(String item) implements Step {
        public String text() { return "place " + item + " near me"; }
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
     * one-planner: "I need {@code n} of {@code what}": when the step runs, {@link Steps} asks its hands for the chain
     * ({@code NeedChain} on the facts of that moment: have it, craft it, dig a known source, else explore) and runs its steps
     * in its place. {@code what}: an item id or a tag ("#minecraft:logs"); {@code anyOf}: a group (any one member, the
     * cheapest: the plain foods), null none; then {@code what} is only its name in the lines.
     */
    record Obtain(String what, int n, List<String> anyOf) implements Step {
        public Obtain {
            anyOf = anyOf == null ? null : List.copyOf(anyOf);
        }

        public Obtain(String what, int n) { this(what, n, null); }

        public String text() { return "obtain " + n + " " + what; }
    }

    /**
     * one-planner: go to the height band {@code minY..maxY} where the wanted block generates (the catalog's worldgen): one
     * Baritone goal (GoalYLevel to the nearest Y in the band); done at once when the bot is in the band.
     */
    record ToBand(int minY, int maxY) implements Step {
        public String text() { return "to y " + minY + ".." + maxY; }
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

    /** Put back the blocks the ledger says I broke, within {@code radius} (the builder leg; the owner's aa restore). */
    record PutBack(int radius) implements Step {
        public String text() { return "put back broken blocks within " + radius; }
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
