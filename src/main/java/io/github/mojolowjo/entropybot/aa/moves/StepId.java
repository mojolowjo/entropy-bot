package io.github.mojolowjo.entropybot.aa.moves;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 0.39.z70 (design D2 3.1, Job A; run M 19:00:59 / 19:01:49: two strips to y 16 from y 70 and y 84 were "the same step"
 * because both lines read "to y 16 (priced path)"): a step's identity - its kind and its value fields in one stable string
 * (arrays by content, maps sorted). {@link Steps} and {@link io.github.mojolowjo.entropybot.aa.Replan} compare steps by it,
 * never by {@link Step#text()} (a line for people, which may leave fields out) and never by record equality (a record with an
 * array field compares the array by reference, so two equal ChopTrees were "different"). One {@code switch} over the sealed
 * {@link Step} type, so a new kind does not compile until it says its identity. Pure (JUnit: Z70StepIdTest). Loader notes:
 * none (plain Java).
 */
public record StepId(String kind, String target) {
    /** The identity of {@code s}; null for a null step. */
    public static StepId of(Step s) {
        if (s == null) return null;
        return switch (s) {
            case Step.Walk w -> new StepId("walk", cell(w.x(), w.y(), w.z()) + " near " + w.near());
            case Step.ToStation w -> new StepId("toStation", w.kind() + " " + w.name() + " " + cell(w.x(), w.y(), w.z()));
            case Step.MineAt m -> new StepId("mineAt", m.family() + " " + cell(m.x(), m.y(), m.z()) + (m.dig() ? " dig" : " walk"));
            case Step.Mine m -> new StepId("mine", m.family() + " " + m.n() + " " + m.how() + " " + m.minutes() + "m");
            case Step.Cave k -> new StepId("cave", k.family() + " " + k.n() + " " + k.x() + " " + k.y() + " " + k.z() + " " + k.minutes() + "m");
            case Step.ChopTree t -> new StepId("chopTree", t.logs() + " " + t.log() + " at " + arr(t.at()));
            case Step.Strip st -> new StepId("strip", strip(st));
            case Step.HuntAt h -> new StepId("huntAt", h.type() + " " + h.n() + " at " + arr(h.at()) + " for " + h.forItem() + " " + h.forCount());
            case Step.Build b -> {
                List<String> c = new ArrayList<>();
                for (int i = 0; i < b.cells().size(); i++) c.add(b.items().get(i) + " " + arr(b.cells().get(i)));
                yield new StepId("build", String.join(", ", c));
            }
            case Step.TakeStation t -> new StepId("takeStation", t.item() + " " + cell(t.x(), t.y(), t.z()));
            case Step.Craft k -> new StepId("craft", k.item() + " " + k.n());
            case Step.Smelt k -> new StepId("smelt", k.item() + " " + k.n() + (k.here() ? " here" : ""));
            case Step.Cook k -> new StepId("cook", k.item() + " " + k.n());
            case Step.Collect k -> new StepId("collect", k.item() + " " + k.n());
            case Step.Fetch k -> new StepId("fetch", k.item() + " " + k.n());
            case Step.SmeltCollect k -> new StepId("smeltCollect", "");
            case Step.Shelter k -> new StepId("shelter", "");
            case Step.Wait w -> new StepId("wait", String.valueOf(w.seconds()));
            case Step.Climb k -> new StepId("climb", "");
            case Step.Scan k -> new StepId("scan", k.base() ? "base" : "");
            case Step.FindCave f -> new StepId("findCave", String.valueOf(f.minutes()));
            case Step.Explore e -> new StepId("explore", e.minutes() + " until " + e.until());
            case Step.AwaitPaths a -> new StepId("stand", "paths " + a.waitS());   // 0.39.z76 send-back 3 (rule 22)
            case Step.Deposit d -> new StepId("deposit", d.order() == null ? "" : strip(d.order()));
            case Step.StripBack b -> new StepId("stripBack", b.order() == null ? "" : strip(b.order()));
            case Step.DropJunk d -> new StepId("dropJunk", d.item() + " " + d.n());
            case Step.PlaceNear p -> new StepId("placeNear", p.item());
            case Step.MarkBase m -> new StepId("markBase", cell(m.x(), m.y(), m.z()));
            case Step.Sleep k -> new StepId("sleep", "");
            case Step.Burrow k -> new StepId("burrow", "");
            case Step.Flee k -> new StepId("flee", "");
            case Step.Attack k -> new StepId("attack", "");
            case Step.Pillar k -> new StepId("pillar", "");
            case Step.GoBase k -> new StepId("goBase", "");
            case Step.House h -> new StepId("house", String.valueOf(h.area()));
            case Step.BuildSchematic b -> new StepId("buildSchematic", b.name() + " " + cell(b.x(), b.y(), b.z()));
            case Step.Come k -> new StepId("come", "");
            case Step.Give g -> new StepId("give", g.item() + " " + g.n());
            case Step.Light l -> new StepId("light", String.valueOf(l.radius()));
            case Step.Fish f -> new StepId("fish", f.n() + " " + f.minutes() + "m");
            case Step.KelpCut k -> new StepId("kelpCut", String.valueOf(k.n()));
            case Step.Kelp k -> new StepId("kelp", String.valueOf(k.n()));
            case Step.Eat k -> new StepId("eat", "");
            case Step.DigArea d -> new StepId("digArea", arr(d.box()) + " +" + d.up());
            case Step.FarmPlant f -> new StepId("farmPlant", f.crop() + " " + f.n());
            case Step.MarkFarm m -> new StepId("markFarm", arr(m.at()));
            case Step.Obtain o -> new StepId("obtain", o.what() + " " + o.n() + (o.anyOf() == null ? "" : " any of " + o.anyOf()));
            case Step.ToBand b -> new StepId("toBand", b.minY() + ".." + b.maxY());
            case Step.ToStrip t -> new StepId("toStrip", cell(t.x(), t.y(), t.z()) + " " + t.strip());
            case Step.ToSurface t -> new StepId("toSurface", cell(t.x(), t.y(), t.z()));
            case Step.StripLeg l -> new StepId("stripLeg", cell(l.x(), l.y(), l.z()) + " " + l.dir() + " " + l.dx() + "," + l.dz() + " " + l.levels() + (l.mark() ? " mark" : ""));
            case Step.ClearChunk c -> new StepId("clearChunk", cell(c.x0(), c.y(), c.z0()) + " slab " + c.slab());
            case Step.Dig d -> new StepId("dig", arr(d.from()) + " - " + arr(d.to()) + (d.ores() ? " ores" : "") + (d.viaEngine() ? " via engine" : ""));
            case Step.MarkMine m -> new StepId("markMine", arr(m.at()) + " " + m.dir());
            case Step.Restore r -> new StepId("restore", (r.keep() ? "keep " : "") + r.radius());
            case Step.PutBack p -> new StepId("putBack", String.valueOf(p.radius()));
        };
    }

    /** The identities of {@code steps}, in order (null steps: null entries). */
    public static List<StepId> of(List<? extends Step> steps) {
        List<StepId> out = new ArrayList<>();
        if (steps != null) for (Step s : steps) out.add(of(s));
        return out;
    }

    /** The two lists hold the same steps by identity, in the same order (null: never the same as a list). */
    public static boolean same(List<? extends Step> a, List<? extends Step> b) {
        if (a == null || b == null) return false;
        return of(a).equals(of(b));
    }

    /** The two steps are one step by identity (null: never the same). */
    public static boolean same(Step a, Step b) {
        StepId x = of(a), y = of(b);
        return x != null && x.equals(y);
    }

    /** "walk -27 16 -55 near 0" for the lines. */
    public String text() { return target.isEmpty() ? kind : kind + " " + target; }

    private static String cell(int x, int y, int z) { return x + " " + y + " " + z; }

    /** An array by content ("1 2 3"), "-" for none. */
    private static String arr(int[] a) {
        if (a == null) return "-";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) b.append(i == 0 ? "" : " ").append(a[i]);
        return b.toString();
    }

    /** A strip by its saved identity, its amounts sorted and its order's time (two orders alike are two runs). */
    private static String strip(Step.Strip s) {
        Map<String, Integer> a = new TreeMap<>(s.amounts());
        return s.key() + " for " + a + " #" + s.serial();
    }
}
