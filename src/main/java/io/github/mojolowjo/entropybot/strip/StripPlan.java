package io.github.mojolowjo.entropybot.strip;

import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.clear.ClearBox;
import io.github.mojolowjo.entropybot.clear.ClearJob;
import io.github.mojolowjo.entropybot.clear.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * B7d D2: the steps of one strip-mine run as plain items (the bridge's stripSteps and setupPlanSteps' output); the
 * mod's StripMine turns them into Seq steps. Pure, so JUnit checks which boxes, which order and which labels.
 */
public final class StripPlan {
    private StripPlan() {}

    /** One planned step; which fields count depends on {@link #type}. */
    public static final class Item {
        /** leg, setupplan, craft, dig, oredig, basedeposit, minedone, next, place, setupdone */
        public final String type;
        /** dig/oredig/minedone: the branch; dig: "corridor", "left", "right" or null (a plain dig) */
        public int k;
        public String part;
        public ClearJob.Options opts;
        /** dig/oredig: dump into the mine's chests (known once it is set up; the base chests before) */
        public boolean dumpMine;
        public List<ClearBox> boxes;
        public MineGeom.Leg leg;
        /** craft: "torch 16"; unless the bag holds unlessN of unlessId */
        public String text, unlessId;
        public int unlessN;
        public boolean optional;
        /** place: item at pos; setupdone: the chests and the table */
        public Pos pos;
        public String item;
        public List<Pos> chests;
        public Pos table;
        /** next ("mine strip"): the branch length of the runs it adds */
        public int length;

        Item(String type) { this.type = type; }

        @Override
        public String toString() {
            return switch (type) {
                case "leg" -> "leg " + MineGeom.fmt(leg.pos()) + " within " + leg.within() + " (" + leg.why() + ")";
                case "dig" -> "dig " + (part != null ? part + " " + k + " " : "") + opts.box + (opts.collect ? " collect" : "")
                        + (Boolean.FALSE.equals(opts.keepOres) ? " keepOres=false" : "") + (dumpMine ? " dump=mine" : "")
                        + (opts.torches != null && !opts.torches.isEmpty() ? " torches=" + opts.torches : "") + " '" + opts.label + "'";
                case "oredig" -> "oredig " + k;
                case "minedone" -> "minedone " + k;
                case "craft" -> "craft " + text + (unlessId != null ? " unless " + unlessId + " " + unlessN : "") + (optional ? " optional" : "");
                case "place" -> "place " + item + " " + MineGeom.fmt(pos);
                case "setupdone" -> "setupdone " + chests + " table " + table;
                case "next" -> "next " + length;
                default -> type;
            };
        }
    }

    public static Item leg(MineGeom.Leg l) {
        Item i = new Item("leg");
        i.leg = l;
        return i;
    }

    public static Item craft(String text, String unlessId, int unlessN, boolean optional) {
        Item i = new Item("craft");
        i.text = text;
        i.unlessId = unlessId;
        i.unlessN = unlessN;
        i.optional = optional;
        return i;
    }

    public static Item dig(ClearJob.Options o, int k, String part, boolean dumpMine) {
        Item i = new Item("dig");
        i.opts = o;
        i.k = k;
        i.part = part;
        i.dumpMine = dumpMine;
        return i;
    }

    public static Item place(String item, Pos pos) {
        Item i = new Item("place");
        i.item = item;
        i.pos = pos;
        return i;
    }

    public static Item simple(String type, int k) {
        Item i = new Item(type);
        i.k = k;
        return i;
    }

    public record Run(List<Item> items, String label) {}

    /**
     * stripSteps: branches branch pairs of `length` at mine g, from its note's k on. wantCollect: ores are mined (per
     * branch only when everything it digs is inside the areas: inArea tests the x/z bounds); me: where the bot is.
     */
    public static Run run(MineGeom g, JsonObject note, int branches, int length, boolean wantCollect, Predicate<ClearBox> inArea, int[] me) {
        int k0 = MineBook.k(note);
        boolean setup = MineBook.setup(note);
        List<String> bad = MineBook.bad(note);
        List<Item> out = new ArrayList<>();
        for (MineGeom.Leg l : g.legs(k0, me)) out.add(leg(l));
        if (!setup) out.add(new Item("setupplan"));
        out.add(craft("torch 16", "minecraft:torch", 8, true));
        boolean collectAny = false;
        for (int b = 0; b < branches; b++) {
            int k = k0 + b;
            ClearBox cb = g.corridor(k), lb = g.branch(k, 1, length), rb = g.branch(k, -1, length);
            boolean collect = wantCollect && inArea.test(g.union(k, length));
            if (collect) collectAny = true;
            // the corridor has to end up open (the bridge's mustFinish): StripSteps checks what is left after it
            out.add(dig(new ClearJob.Options().box(cb).keepOres(false).collect(collect).torches(g.corridorTorches(k))
                    .label("digging the mine corridor (branch " + k + ")"), k, "corridor", true));
            for (int side = 1; side >= -1; side -= 2) {
                String part = side > 0 ? "left" : "right";
                // a branch noted bad (stuck twice) is not dug again
                if (bad.contains(k + " " + part)) continue;
                out.add(dig(new ClearJob.Options().box(side > 0 ? lb : rb).torches(g.branchTorches(k, side, length)).keepOres(!collect)
                        .collect(collect).label("digging branch " + k + " " + part), k, part, true));
            }
            if (collect) {
                Item o = simple("oredig", k);
                o.boxes = List.of(cb, lb, rb);
                o.dumpMine = true;
                out.add(o);
            }
            if (b == branches - 1 && collectAny) out.add(new Item("basedeposit"));
            out.add(simple("minedone", k));
        }
        String label = "strip mining " + (branches > 1 ? "branches " + k0 + "-" + (k0 + branches - 1) : "branch " + k0) + (setup ? "" : " (setting up chests first)");
        return new Run(out, label);
    }

    public static Item next(int length) {
        Item i = new Item("next");
        i.length = length;
        return i;
    }
}
