package io.github.mojolowjo.entropybot.aa.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * BRAIN_PLAN "knowledge from the game at load": where an item comes from besides recipes and ores: mob drops, block
 * drops, structure chest loot and villager trades. The adapter reads the loot tables (encoded to JSON with the game's
 * own codec) and the trade lists once per world on the integrated server; on a multiplayer client (loot-book-bg) it reads
 * the loot-table JSON in the client's own game and mod jars ({@link LootFiles}), in the background after the join, with a
 * cache per mod list. Pure: the JSON parser, the index and the plan
 * ("kill X for Y", "break X for Y") are JUnit-tested.
 *
 * <p>The numbers are expectations, not exact odds: per pool, rolls x (weight / total weight) x the random_chance
 * conditions x the average count. Entries behind a tool condition (silk touch, shears) are skipped: the bot breaks
 * with a pickaxe/hand and does not carry those.
 */
public final class LootBook {
    public static final String FACT = "aa.lootBook", CTX_FACT = "aa.lootCtx";
    /** AA_ROUND3 5: mob id -> {x, z, distance, confidence x 100} of the best herd seen and not in view (Sightings). */
    public static final String HERDS_FACT = "aa.herdsSeen";

    /**
     * A multiplayer client has no loot tables. No vanilla table is hard-coded (rm-names, RULES 4): the book is empty with
     * its reason, which {@code aa want} shows and the log says.
     */
    public static LootBook vanilla() { return empty("no loot tables on a multiplayer client: mob drops are unknown, not guessed"); }

    public enum Kind { MOB, BLOCK, CHEST, TRADE }

    /**
     * One way to get an item. from: the mob, block, chest table or villager profession id; per: the expected count per
     * kill / block / chest (TRADE: items per trade); chance: the chance that one kill/block/chest gives any (0..1);
     * player: only a player's kill drops it; cost: TRADE only, e.g. "12 emerald".
     */
    public record Source(Kind kind, String from, String item, double per, double chance, boolean player, String cost) {
        public String text() {
            String f = Thing.shortId(from);
            return switch (kind) {
                case MOB -> "kill " + f + " (" + odds() + ")";
                case BLOCK -> "break " + f + " (" + odds() + ")";
                case CHEST -> f.replaceFirst("^chests/", "") + " chests (" + odds() + ")";
                case TRADE -> f + " villager trades it" + (cost == null ? "" : " for " + cost);
            };
        }

        private String odds() {
            return chance >= 0.995 ? "about " + fmt(per) + " each" : Math.round(chance * 100) + "%";  // structural: display: a chance of 99.5% or more is shown as per-item; percent
        }
    }

    /** What the bot may do now (the adapter fills it, {@link #CTX_FACT}). */
    public record Ctx(boolean huntingOn, Set<String> passive, Set<String> ownerAllowed, Predicate<String> breakable, boolean armed) {
        public static final Ctx NONE = new Ctx(false, Set.of(), Set.of(), b -> false, false);
    }

    /** A plan for n of an item: the typed step to run (null = none) and the line that explains it. */
    public record Plan(io.github.mojolowjo.entropybot.aa.moves.Step step, String text) {
        /** The step's line (logs), or null. */
        public String command() { return step == null ? null : step.text(); }
    }

    /** loot-book-bg: the start of the reason a chain gives while the book is still being read ("waiting for the loot book (N of M)"). */
    public static final String WAITING = "waiting for the loot book";

    /**
     * loot-book-bg: how far a background read is: tables done of the tables found, and where they come from ("from the jars",
     * "integrated server", "from the cache"). Written by the reader thread, read by the game thread (status, the chain's reason).
     */
    public static final class Progress {
        public final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(), total = new java.util.concurrent.atomic.AtomicInteger();
        public volatile String from;

        public Progress(String from) { this.from = from; }

        public String text() { return done.get() + " of " + total.get(); }
    }

    private final Map<String, List<Source>> byItem = new HashMap<>();
    private final List<Source> all;
    private final Progress progress;     // null: the book is read
    private int mobs, blocks, chests, trades;
    public final String origin;

    /** loot-book-bg: the book while a background read runs: empty, not ready; chains that would need it wait ({@link #waiting}). */
    public static LootBook reading(Progress p) { return new LootBook(List.of(), p.from, p); }

    public LootBook(List<Source> all, String origin) { this(all, origin, null); }

    private LootBook(List<Source> all, String origin, Progress progress) {
        this.origin = origin;
        this.progress = progress;
        this.all = List.copyOf(all);
        for (Source s : all) {
            byItem.computeIfAbsent(TechTree.id(s.item()), k -> new ArrayList<>()).add(s);
            switch (s.kind()) {
                case MOB -> mobs++;
                case BLOCK -> blocks++;
                case CHEST -> chests++;
                case TRADE -> trades++;
            }
        }
    }

    public static LootBook empty(String origin) { return new LootBook(List.of(), origin); }

    public boolean isEmpty() { return byItem.isEmpty(); }

    /** loot-book-bg: false while the background read runs (the book is a placeholder then). */
    public boolean ready() { return progress == null; }

    /** loot-book-bg: the chain's reason while the book is read: "waiting for the loot book (N of M)"; null once it is ready. */
    public String waiting() { return progress == null ? null : WAITING + " (" + progress.text() + ")"; }

    /** Every source in the book (the cache writes them). */
    public List<Source> all() { return all; }

    public String counts() {
        return mobs + " mob drops, " + blocks + " block drops, " + chests + " chest items, " + trades + " trades (" + origin + ")";
    }

    /** loot-book-bg: the aa status line: "loot book: 108 mob drops, ... (jars: 3,104 tables, 2.1 s)" or "loot book: reading N of M tables (from the jars)". */
    public String line() {
        return progress == null ? "loot book: " + counts() : "loot book: reading " + progress.text() + " tables (" + progress.from + ")";
    }

    /**
     * loot-book-bg: the owner's manual correction ({@code entropybot/lootbook-override.json}): for every item in {@code over}
     * its sources replace the book's (an empty list removes them). The origin says how many items were overridden.
     */
    public LootBook withOverride(Map<String, List<Source>> over, String file) {
        if (over == null || over.isEmpty()) return this;
        Set<String> items = new HashSet<>();
        for (String k : over.keySet()) items.add(TechTree.id(k));
        List<Source> out = new ArrayList<>();
        for (Source s : all) if (!items.contains(TechTree.id(s.item()))) out.add(s);
        for (List<Source> l : over.values()) out.addAll(l);
        return new LootBook(out, origin + "; " + items.size() + " items from " + file, progress);
    }

    /** What one block of the id drops: the BLOCK sources whose from is the block (item, expected count). */
    public List<Source> blockDrops(String block) {
        List<Source> out = new ArrayList<>();
        for (List<Source> l : byItem.values()) for (Source s : l) if (s.kind() == Kind.BLOCK && s.from().equals(block) && s.chance() > 0) out.add(s);
        return out;
    }

    /** Every source of the item, best first (mobs and blocks by per, then chests, then trades). */
    public List<Source> sources(String item) {
        List<Source> l = new ArrayList<>(byItem.getOrDefault(TechTree.id(item), List.of()));
        l.sort(Comparator.comparingInt((Source s) -> s.kind().ordinal() >= 2 ? 1 : 0).thenComparing((Source s) -> -s.per() * Math.max(0.01, s.chance()))
                .thenComparing(Source::from));
        return l;
    }

    /** rm-execution: the blocks whose loot gives the item (the collect step's MineProcess filter), best first. */
    public List<String> blocksDropping(String item) {
        List<String> out = new ArrayList<>();
        for (Source s : sources(item)) if (s.kind() == Kind.BLOCK && s.chance() > 0 && !out.contains(s.from())) out.add(s.from());
        return out;
    }

    /**
     * TAG_NEEDS: every source whose item the test accepts (a tag need: any member), read off the book's own index (no member
     * list): the same order as {@link #sources}, then by item.
     */
    public List<Source> sourcesMatching(Predicate<String> item) {
        List<Source> l = new ArrayList<>();
        for (var e : byItem.entrySet()) if (item.test(e.getKey())) l.addAll(e.getValue());
        l.sort(Comparator.comparingInt((Source s) -> s.kind().ordinal() >= 2 ? 1 : 0).thenComparing((Source s) -> -s.per() * Math.max(0.01, s.chance()))
                .thenComparing(Source::from).thenComparing(Source::item));
        return l;
    }

    /** TAG_NEEDS: the blocks whose loot gives any item the test accepts (a tag need's source set). */
    public List<String> blocksDroppingAny(Predicate<String> item) {
        List<String> out = new ArrayList<>();
        for (Source s : sourcesMatching(item)) if (s.kind() == Kind.BLOCK && s.chance() > 0 && !out.contains(s.from())) out.add(s.from());
        return out;
    }

    /** "from: kill cow (about 1 each), break gravel (10%); +3 more" (or null when the book has nothing). */
    public String sourcesText(String item, int max) {
        List<Source> l = sources(item);
        if (l.isEmpty()) return null;
        List<String> s = new ArrayList<>();
        for (int i = 0; i < Math.min(max, l.size()); i++) s.add(l.get(i).text());
        return "from: " + String.join(", ", s) + (l.size() > max ? "; +" + (l.size() - max) + " more" : "");
    }

    /**
     * The step for n of an item: a breakable block that drops it ({@code mine <block> k}), else a mob hunt may take
     * ({@code hunt k <mob> for <item>}): passive mobs with hunting on, a hostile one only when the owner allowed it
     * (the animals kind) and {@code fightOk} (the brain's danger/fight rules). Else no command and the reason.
     */
    public Plan plan(String item, int n, Ctx ctx, boolean fightOk) { return plan(item, n, n, ctx, fightOk); }

    /** stopAt: the bag total the hunt stops at (the goal's n; hunt counts the whole bag, not what it got). */
    public Plan plan(String item, int n, int stopAt, Ctx ctx, boolean fightOk) { return plan(item, n, stopAt, ctx, fightOk, null); }

    /**
     * AA_ROUND3 5: herds = {@link #HERDS_FACT} (null none): a mob not in view but seen earlier is hunted at its place
     * ({@code hunt k cow for leather n at x z}); the line names the place, the distance and the confidence.
     */
    public Plan plan(String item, int n, int stopAt, Ctx ctx, boolean fightOk, Map<String, int[]> herds) {
        Plan p = plan0(item, n, stopAt, ctx, fightOk);
        if (p.text() == null || herds == null || !(p.step() instanceof io.github.mojolowjo.entropybot.aa.moves.Step.HuntAt hu)) return p;
        int[] h = herds.get(hu.type());
        if (h == null) return p;
        return new Plan(new io.github.mojolowjo.entropybot.aa.moves.Step.HuntAt(hu.type(), hu.n(), new int[]{h[0], h[1]}, hu.forItem(), hu.forCount()), p.text() + " at " + h[0] + " " + h[1] + " (seen there, " + h[2] + " away, confidence " + String.format(Locale.ROOT, "%.2f", h[3] / 100.0) + ")");  // structural: percent
    }

    private Plan plan0(String item, int n, int stopAt, Ctx ctx, boolean fightOk) {
        String id = TechTree.id(item);
        List<Source> l = sources(id);
        if (l.isEmpty()) return new Plan(null, null);
        Ctx c = ctx == null ? Ctx.NONE : ctx;
        String why = null;
        for (Source s : l) {
            if (s.kind() == Kind.BLOCK && !s.from().equals(id)) {          // the block itself is gather/mine's job
                if (!c.breakable().test(s.from())) { if (why == null) why = "break " + Thing.shortId(s.from()) + ": my mine verb does not take that block"; continue; }
                int k = (int) Math.ceil(n / Math.max(0.05, s.per()));
                return new Plan(new io.github.mojolowjo.entropybot.aa.moves.Step.Mine(Thing.shortId(s.from()), Math.min(k, 512), io.github.mojolowjo.entropybot.aa.moves.Step.Mine.How.KNOWN), "break " + Thing.shortId(s.from()) + " for " + Thing.shortId(id));
            }
        }
        for (Source s : l) {
            if (s.kind() != Kind.MOB) continue;
            boolean passive = c.passive().contains(s.from()), allowed = c.ownerAllowed().contains(s.from());
            String m = Thing.shortId(s.from());
            if (!c.huntingOn()) { if (why == null) why = "kill " + m + ": hunting is off (hunting on)"; continue; }
            if (!passive && !allowed) { if (why == null) why = "kill " + m + ": hostile, not in the animals kind (kinds animals add " + m + ")"; continue; }
            if (!passive && !fightOk) { if (why == null) why = "kill " + m + ": the fight rules say no now (weapon, health or danger)"; continue; }
            if (s.player() && s.chance() < Tuning.get("lootBook.minChance")) continue;
            int k = (int) Math.ceil(n / Math.max(0.05, s.per()));
            return new Plan(new io.github.mojolowjo.entropybot.aa.moves.Step.HuntAt(s.from(), Math.min(k, 64), null, id, Math.max(n, stopAt)), "kill " + m + " for " + Thing.shortId(id));
        }
        if (why == null) why = sources(id).get(0).text() + ": no verb for that yet";
        return new Plan(null, why);
    }

    // ---- the loot table JSON (LootTable.DIRECT_CODEC output) ----

    /**
     * Item -> expected {per, chance, player} for one table. lookup: another table's JSON by id (for "loot_table"
     * entries), may return null.
     */
    public static Map<String, double[]> parse(JsonElement table, Function<String, JsonElement> lookup) {
        Map<String, double[]> out = new HashMap<>();
        parse(table, lookup, 1.0, false, out, new HashSet<>(), 0);
        return out;
    }

    private static void parse(JsonElement table, Function<String, JsonElement> lookup, double mult, boolean player,
                              Map<String, double[]> out, Set<String> path, int depth) {
        if (table == null || !table.isJsonObject() || depth > 4) return;  // structural: loot table nesting depth cap
        JsonObject t = table.getAsJsonObject();
        if (!t.has("pools") || !t.get("pools").isJsonArray()) return;
        for (JsonElement pe : t.getAsJsonArray("pools")) {
            if (!pe.isJsonObject()) continue;
            JsonObject p = pe.getAsJsonObject();
            double[] pc = conditions(p.get("conditions"));
            if (pc == null) continue;
            double rolls = number(p.get("rolls"), 1);
            JsonArray es = p.has("entries") && p.get("entries").isJsonArray() ? p.getAsJsonArray("entries") : new JsonArray();
            double total = 0;
            for (JsonElement e : es) total += weight(e);
            if (total <= 0) continue;
            for (JsonElement e : es)
                entry(e, lookup, mult * rolls * pc[0] * weight(e) / total, player || pc[1] > 0, out, path, depth);
        }
    }

    private static void entry(JsonElement ee, Function<String, JsonElement> lookup, double mult, boolean player,
                              Map<String, double[]> out, Set<String> path, int depth) {
        if (!ee.isJsonObject() || mult <= 0) return;
        JsonObject e = ee.getAsJsonObject();
        double[] c = conditions(e.get("conditions"));
        if (c == null) return;
        mult *= c[0];
        player |= c[1] > 0;
        String type = str(e, "type").replaceFirst("^minecraft:", "");
        switch (type) {
            case "item" -> {
                String name = str(e, "name");
                if (name.isEmpty()) return;
                double count = 1;
                for (JsonElement f : arr(e.get("functions"))) {
                    if (!f.isJsonObject()) continue;
                    JsonObject fo = f.getAsJsonObject();
                    if (conditions(fo.get("conditions")) == null) continue;
                    String fn = str(fo, "function").replaceFirst("^minecraft:", "");
                    if (fn.equals("set_count")) count = fo.has("add") && fo.get("add").getAsBoolean() ? count + number(fo.get("count"), 0) : number(fo.get("count"), 1);
                }
                if (count <= 0) return;
                String id = TechTree.id(name);
                double[] r = out.computeIfAbsent(id, k -> new double[]{0, 0, 1});
                r[0] += mult * count;
                r[1] = 1 - (1 - r[1]) * (1 - Math.min(1, mult));
                if (!player) r[2] = 0;
            }
            case "alternatives" -> {        // the first child whose conditions hold; tool-gated ones never hold for the bot
                double rem = 1;
                for (JsonElement ch : arr(e.get("children"))) {
                    double[] cc = ch.isJsonObject() ? conditions(ch.getAsJsonObject().get("conditions")) : null;
                    if (cc == null) continue;
                    entry(ch, lookup, mult * rem, player, out, path, depth);
                    rem *= 1 - Math.min(1, cc[0]);
                    if (rem <= 0) break;
                }
            }
            case "group", "sequence" -> { for (JsonElement ch : arr(e.get("children"))) entry(ch, lookup, mult, player, out, path, depth); }
            case "loot_table" -> {
                JsonElement v = e.get("value");
                if (v == null) v = e.get("name");
                if (v == null) return;
                if (v.isJsonPrimitive()) {
                    String ref = TechTree.id(v.getAsString());
                    if (lookup == null || !path.add(ref)) return;
                    parse(lookup.apply(ref), lookup, mult, player, out, path, depth + 1);
                    path.remove(ref);
                } else parse(v, lookup, mult, player, out, path, depth + 1);      // an inline table
            }
            default -> {}                  // tag, dynamic, empty: nothing a bot can name
        }
    }

    /** {chance multiplier, killed-by-player 1/0}, or null when a tool condition gates it (silk touch, shears). */
    static double[] conditions(JsonElement cs) {
        double ch = 1, pl = 0;
        for (JsonElement ce : arr(cs)) {
            if (!ce.isJsonObject()) continue;
            JsonObject c = ce.getAsJsonObject();
            String k = str(c, "condition").replaceFirst("^minecraft:", "");
            switch (k) {
                case "random_chance" -> ch *= number(c.get("chance"), 1);
                case "random_chance_with_enchanted_bonus" -> ch *= number(c.get("unenchanted_chance"), 1);
                case "random_chance_with_looting" -> ch *= number(c.get("chance"), 1);
                case "killed_by_player" -> pl = 1;
                case "match_tool" -> { return null; }
                case "table_bonus" -> {            // fortune odds: the bot's tools carry none, so the first step
                    JsonArray a = arr(c.get("chances"));
                    if (!a.isEmpty()) ch *= number(a.get(0), 1);
                }
                default -> {}
            }
        }
        return new double[]{ch, pl};
    }

    private static double weight(JsonElement e) {
        if (!e.isJsonObject()) return 0;
        JsonObject o = e.getAsJsonObject();
        return o.has("weight") ? Math.max(0, o.get("weight").getAsDouble()) : 1;
    }

    /** A number provider's mean: a constant, uniform {min,max}, binomial {n,p}, constant {value}. */
    static double number(JsonElement v, double def) {
        if (v == null || v.isJsonNull()) return def;
        if (v.isJsonPrimitive()) return v.getAsJsonPrimitive().isNumber() ? v.getAsDouble() : def;
        if (!v.isJsonObject()) return def;
        JsonObject o = v.getAsJsonObject();
        String t = str(o, "type").replaceFirst("^minecraft:", "");
        return switch (t) {
            case "uniform" -> (number(o.get("min"), 0) + number(o.get("max"), 0)) / 2;
            case "binomial" -> number(o.get("n"), 0) * number(o.get("p"), 0);
            case "constant" -> number(o.get("value"), def);
            default -> o.has("min") && o.has("max") ? (number(o.get("min"), 0) + number(o.get("max"), 0)) / 2 : def;
        };
    }

    private static JsonArray arr(JsonElement e) { return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray(); }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString().toLowerCase(Locale.ROOT) : "";
    }

    /** The sources one parsed table gives (kind and from set by the caller). */
    public static List<Source> sources(Kind kind, String from, Map<String, double[]> parsed) {
        List<Source> out = new ArrayList<>();
        for (var e : parsed.entrySet())
            if (e.getValue()[0] > 0) out.add(new Source(kind, from, e.getKey(), e.getValue()[0], Math.min(1, e.getValue()[1]), e.getValue()[2] > 0, null));
        return out;
    }

    static String fmt(double v) {
        return v >= 10 ? String.valueOf(Math.round(v)) : String.valueOf(Math.round(v * 10) / 10.0);  // structural: rounded to one decimal for the text
    }
}

