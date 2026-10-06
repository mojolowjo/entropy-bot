package io.github.mojolowjo.entropybot.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.commands.Texts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * C2 shared stock: "what do we have as a group". Four sources, each with the time it was last updated:
 * <ul>
 *   <li>{@code bot}: the bot's bag (live, age 0);</li>
 *   <li>{@code chests}: the chest notes ({@code chests.json}, written by every scan/open/take/put/deposit through
 *       {@code Knowledge.noteChest}); untrusted chests are not ours and are left out; a {@code mark}ed chest shows its
 *       name; the source's time is the newest note's {@code seen};</li>
 *   <li>{@code rs}: the Refined Storage readings ({@code rs.json}, the last {@code rs});</li>
 *   <li>{@code owner}: the owner's inventory from {@code owner-inv.json}, which the dashboard writes from the companion
 *       ({@code {"name","t":ms,"inv":[{"id":..,"count":..}],"health","food","held"}}, age from {@code t}); a missing
 *       file or {@code inv} field means "no data".</li>
 * </ul>
 * Plain Java (Gson only): JUnit tests it. {@link #toJson} is the shape of {@code entropybot/stock.json}:
 * <pre>{"t":ms,"sources":{"bot":{"age":0,"seen":ms,"items":{id:n}},
 *   "chests":{"age":ms,"seen":ms,"count":N,"items":{id:{"total":n,"at":[["x y z","name or empty",n],...]}}},
 *   "rs":{"age":ms,"seen":ms,"items":{id:n}},
 *   "owner":{"age":ms,"seen":ms,"items":{id:n}} | {"age":-1,"seen":0,"items":{}} without data},
 *  "totals":{id:n}}</pre>
 * Ages are ms at {@code t}; {@code seen} is the epoch ms of the source's update (0 = never), so a reader can age it later.
 */
public final class Stock {
    /** One chest's count of one item. */
    public record At(String pos, String name, int count) {}

    private final long now;
    final Map<String, Integer> bot = new TreeMap<>(), rs = new TreeMap<>(), owner = new TreeMap<>(), totals = new TreeMap<>();
    final Map<String, List<At>> chests = new TreeMap<>();
    long chestsSeen, rsSeen, ownerSeen;
    int chestCount;
    boolean ownerData;

    private Stock(long now) { this.now = now; }

    /**
     * @param bag     the bot's inventory (id -> n), may be null
     * @param notes   chest notes "x y z" -> {dim, items, seen, trusted?}
     * @param places  marked places (for chest names)
     * @param rsNotes RS readings "x y z" -> {items, seen}
     * @param ownerJson owner-inv.json's text, or null
     */
    public static Stock build(Map<String, Integer> bag, Map<String, JsonObject> notes, Map<String, JsonObject> places,
                              Map<String, JsonObject> rsNotes, String ownerJson, long now) {
        Stock s = new Stock(now);
        if (bag != null) for (Map.Entry<String, Integer> e : bag.entrySet()) add(s.bot, id(e.getKey()), e.getValue());
        if (notes != null) {
            for (Map.Entry<String, JsonObject> c : notes.entrySet()) {
                JsonObject n = c.getValue();
                if (n.has("trusted") && n.get("trusted").isJsonPrimitive() && !n.get("trusted").getAsBoolean()) continue;
                Map<String, Integer> it = StorageRules.items(n);
                if (it.isEmpty()) continue;
                s.chestCount++;
                s.chestsSeen = Math.max(s.chestsSeen, StorageRules.seen(n));
                String name;
                try { name = StorageRules.chestLabel(c.getKey(), places == null ? Map.of() : places, n); } catch (RuntimeException e) { name = null; }
                for (Map.Entry<String, Integer> e : it.entrySet()) {
                    if (e.getValue() <= 0) continue;
                    s.chests.computeIfAbsent(id(e.getKey()), k -> new ArrayList<>()).add(new At(c.getKey(), name == null ? "" : name, e.getValue()));
                }
            }
            for (List<At> l : s.chests.values()) l.sort((a, b) -> b.count() - a.count());
        }
        if (rsNotes != null) {
            for (JsonObject r : rsNotes.values()) {
                s.rsSeen = Math.max(s.rsSeen, StorageRules.seen(r));
                for (Map.Entry<String, Integer> e : StorageRules.items(r).entrySet()) add(s.rs, id(e.getKey()), e.getValue());
            }
        }
        s.readOwner(ownerJson);
        add(s.totals, s.bot);
        add(s.totals, s.rs);
        add(s.totals, s.owner);
        for (Map.Entry<String, List<At>> e : s.chests.entrySet()) for (At a : e.getValue()) add(s.totals, e.getKey(), a.count());
        return s;
    }

    private void readOwner(String json) {
        if (json == null) return;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (!o.has("inv") || !o.get("inv").isJsonArray()) return;
            ownerData = true;
            ownerSeen = o.has("t") ? o.get("t").getAsLong() : o.has("received") ? o.get("received").getAsLong() : 0;
            for (JsonElement el : o.getAsJsonArray("inv")) {
                if (!el.isJsonObject()) continue;
                JsonObject it = el.getAsJsonObject();
                if (!it.has("id") || !it.has("count")) continue;
                add(owner, id(it.get("id").getAsString()), it.get("count").getAsInt());
            }
        } catch (RuntimeException e) {
            ownerData = false;
            owner.clear();
        }
    }

    static String id(String s) { return Texts.shortId(s); }

    private static void add(Map<String, Integer> m, String id, int n) {
        if (n > 0) m.merge(id, n, Integer::sum);
    }

    private static void add(Map<String, Integer> into, Map<String, Integer> from) {
        for (Map.Entry<String, Integer> e : from.entrySet()) add(into, e.getKey(), e.getValue());
    }

    public Map<String, Integer> totals() { return new TreeMap<>(totals); }

    public boolean ownerData() { return ownerData; }

    /** How many of {@code id} the owner carries (0 without data). */
    public int ownerCount(String id) { return owner.getOrDefault(id(id), 0); }

    /** The owner's items whose id contains q. */
    public Map<String, Integer> ownerMatching(String q) {
        Map<String, Integer> out = new TreeMap<>();
        for (Map.Entry<String, Integer> e : owner.entrySet()) if (e.getKey().contains(q)) out.put(e.getKey(), e.getValue());
        return out;
    }

    public long ownerSeen() { return ownerSeen; }

    /** The ids a query means: the exact id when known, else every id containing it (biggest totals first). */
    List<String> match(String q) {
        String k = id(q.trim().toLowerCase());
        List<String> out = new ArrayList<>();
        if (k.isEmpty()) return out;
        if (totals.containsKey(k)) {
            out.add(k);
            return out;
        }
        for (String id : totals.keySet()) if (id.contains(k)) out.add(id);
        out.sort((a, b) -> totals.get(b) - totals.get(a));
        return out;
    }

    /**
     * "have <item>": {@code iron_ingot: 12 on me, 64 in food chest (-28 54 189), 300 in RS (2m ago), 5 on you (3s ago)};
     * at most 3 matching items, 4 chests each. No argument: the top totals.
     */
    public String have(String q) {
        if (q == null || q.isBlank()) return top();
        List<String> ids = match(q);
        String k = id(q.trim().toLowerCase());
        if (ids.isEmpty()) return "we have no " + k + " that I know of (me, " + chestCount + " chests, RS"
                + (ownerData ? ", you" : "") + ")" + (ownerData ? "" : " - no companion data for your bag");
        List<String> lines = new ArrayList<>();
        for (String id : ids.subList(0, Math.min(3, ids.size()))) lines.add(line(id));
        if (ids.size() > 3) lines.add("+" + (ids.size() - 3) + " more kinds (stock " + k + ")");
        return String.join(" | ", lines);
    }

    String line(String id) {
        List<String> parts = new ArrayList<>();
        Integer b = bot.get(id);
        if (b != null) parts.add(b + " on me");
        List<At> at = chests.getOrDefault(id, List.of());
        for (int i = 0; i < at.size() && i < 4; i++) {
            At a = at.get(i);
            parts.add(a.count() + " in " + (a.name().isEmpty() ? "chest " + a.pos() : a.name() + " chest (" + a.pos() + ")"));
        }
        if (at.size() > 4) {
            int rest = 0;
            for (int i = 4; i < at.size(); i++) rest += at.get(i).count();
            parts.add(rest + " in " + (at.size() - 4) + " more chests");
        }
        Integer r = rs.get(id);
        if (r != null) parts.add(r + " in RS (" + Texts.ago(rsSeen, now) + ")");
        Integer o = owner.get(id);
        if (o != null) parts.add(o + " on you (" + Texts.ago(ownerSeen, now) + ")");
        return id + ": " + totals.getOrDefault(id, 0) + " - " + String.join(", ", parts);
    }

    /** "have" without an argument: the top 8 totals and how fresh each source is. */
    public String top() {
        if (totals.isEmpty()) return "I know of nothing yet - scan base and rs fill my notes";
        int sum = 0;
        for (int v : totals.values()) sum += v;
        return "we have " + sum + " items of " + totals.size() + " kinds: " + topList(totals, 8) + " - " + freshness();
    }

    /** "chests 12m ago (5), RS 2h ago, you: no companion data". */
    public String freshness() {
        return "chests " + (chestsSeen > 0 ? Texts.ago(chestsSeen, now) + " (" + chestCount + ")" : "none") + ", RS "
                + (rsSeen > 0 ? Texts.ago(rsSeen, now) : "never read") + ", you " + (ownerData ? Texts.ago(ownerSeen, now) : "no companion data");
    }

    /** "stock [filter]": totals (biggest first), at most 15, filtered by id substring. */
    public String list(String filter) {
        String f = filter == null ? "" : id(filter.trim().toLowerCase());
        Map<String, Integer> m = new TreeMap<>();
        for (Map.Entry<String, Integer> e : totals.entrySet()) if (f.isEmpty() || e.getKey().contains(f)) m.put(e.getKey(), e.getValue());
        if (m.isEmpty()) return f.isEmpty() ? top() : "nothing matching " + f + " in the stock (" + freshness() + ")";
        return "stock" + (f.isEmpty() ? "" : " (" + f + ")") + ": " + topList(m, 15) + " - " + freshness();
    }

    static String topList(Map<String, Integer> map, int n) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(map.entrySet());
        list.sort((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size() && i < n; i++) out.add(list.get(i).getKey() + " " + list.get(i).getValue());
        if (list.size() > n) out.add("+" + (list.size() - n) + " more");
        return String.join(", ", out);
    }

    /** stock.json (see the class javadoc). */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("t", now);
        JsonObject src = new JsonObject();
        src.add("bot", source(now, bot));
        JsonObject ch = new JsonObject();
        ch.addProperty("age", chestsSeen > 0 ? now - chestsSeen : -1);
        ch.addProperty("seen", chestsSeen);
        ch.addProperty("count", chestCount);
        JsonObject items = new JsonObject();
        for (Map.Entry<String, List<At>> e : chests.entrySet()) {
            JsonObject one = new JsonObject();
            int total = 0;
            JsonArray at = new JsonArray();
            for (At a : e.getValue()) {
                total += a.count();
                JsonArray row = new JsonArray();
                row.add(a.pos());
                row.add(a.name());
                row.add(a.count());
                at.add(row);
            }
            one.addProperty("total", total);
            one.add("at", at);
            items.add(e.getKey(), one);
        }
        ch.add("items", items);
        src.add("chests", ch);
        src.add("rs", source(rsSeen, rs));
        src.add("owner", ownerData ? source(ownerSeen, owner) : source(0, Map.of()));
        o.add("sources", src);
        o.add("totals", map(totals));
        return o;
    }

    private JsonObject source(long seen, Map<String, Integer> items) {
        JsonObject s = new JsonObject();
        s.addProperty("age", seen > 0 ? now - seen : -1);
        s.addProperty("seen", seen);
        s.add("items", map(items));
        return s;
    }

    private static JsonObject map(Map<String, Integer> m) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Integer> e : m.entrySet()) o.addProperty(e.getKey(), e.getValue());
        return o;
    }

    /** What changes the file: everything but the clock ("t" and the ages). */
    public String signature() {
        JsonObject o = toJson();
        o.remove("t");
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("sources").entrySet()) e.getValue().getAsJsonObject().remove("age");
        return o.toString();
    }

    /** Decides when stock.json is written: on change, at most once per {@code minGapMs}. */
    public static final class Writer {
        private final long minGapMs;
        private String lastSig;
        private long lastWrite = Long.MIN_VALUE / 2;

        public Writer(long minGapMs) { this.minGapMs = minGapMs; }

        /** The JSON to write now, or null (unchanged, or too soon after the last write). */
        public String due(Stock s, long nowMs) {
            if (nowMs - lastWrite < minGapMs) return null;
            String sig = s.signature();
            if (sig.equals(lastSig)) return null;
            lastSig = sig;
            lastWrite = nowMs;
            return s.toJson().toString();
        }

        /** A failed write: try again next time. */
        public void failed() { lastSig = null; }
    }

    /** The per-source maps, for tests. */
    Map<String, Integer> source(String name) {
        return switch (name) {
            case "bot" -> new LinkedHashMap<>(bot);
            case "rs" -> new LinkedHashMap<>(rs);
            case "owner" -> new LinkedHashMap<>(owner);
            default -> Map.of();
        };
    }
}
