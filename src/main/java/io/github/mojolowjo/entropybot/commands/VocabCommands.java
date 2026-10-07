package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.vocab.GameStage;
import io.github.mojolowjo.entropybot.vocab.Kinds;
import io.github.mojolowjo.entropybot.vocab.PlaceWords;
import io.github.mojolowjo.entropybot.vocab.QueueRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * V1b (0.22.2): the game side of the new words that sit on top of the existing engines: places and markers, the
 * queue, the status additions, the kind-words, cut. The rules are pure in {@code vocab/}; this class only reads the
 * game and the stores and calls the old engines. Loader notes: vanilla registries (item ids, the FOOD component).
 */
final class VocabCommands {
    private static final Logger LOG = LogUtils.getLogger();

    private final Commands c;
    /** V1b: defend/guard: stay at a spot (radius r) or in an area's box {x1,y1,z1,x2,y2,z2} and fight what comes; null = none. */
    record Hold(String what, String dim, int x, int y, int z, int r, int[] box, String from) {}

    Hold hold;

    /** queue &lt;task&gt;: {from, line}, oldest first (not saved: a restart empties it). */
    final List<String[]> queued = new ArrayList<>();

    VocabCommands(Commands c) {
        this.c = c;
    }

    // ---- places and markers ----

    /** True when the word names an item that is a block (so "place cobblestone 1 2 3" puts one down). */
    static boolean isBlockItem(String word) {
        try {
            String id = io.github.mojolowjo.entropybot.gui.GuiCore.normId(word);
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) return false;
            return BuiltInRegistries.ITEM.get(rl) instanceof net.minecraft.world.item.BlockItem;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** "place <name> ..." when it names a place (null: the line is a block placement, a job). */
    String placeInstant(String from, String rest, LocalPlayer p) {
        PlaceWords.Parsed a = PlaceWords.parse(rest, VocabCommands::isBlockItem);
        if (a.kind() == PlaceWords.Kind.BLOCK) return null;
        if (a.kind() == PlaceWords.Kind.ERROR) return a.error();
        return c.placeCommand("mark", (a.name() + " " + a.rest()).trim(), from, p);
    }

    List<PlaceWords.Place> placeList() {
        List<PlaceWords.Place> out = new ArrayList<>();
        for (Map.Entry<String, JsonObject> e : Core.INSTANCE.knowledge.places().entrySet()) {
            JsonObject o = e.getValue();
            try {
                List<PlaceWords.Spot> ms = new ArrayList<>();
                if (o.has("markers") && o.get("markers").isJsonArray()) {
                    for (JsonElement m : o.getAsJsonArray("markers")) {
                        JsonObject q = m.getAsJsonObject();
                        ms.add(new PlaceWords.Spot(q.get("name").getAsString(), q.get("x").getAsInt(), q.get("y").getAsInt(), q.get("z").getAsInt()));
                    }
                }
                out.add(new PlaceWords.Place(e.getKey(), Jobs.dimOf(o), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(), ms));
            } catch (RuntimeException ex) {
                LOG.warn("[entropybot] place {} unreadable: {}", e.getKey(), ex.toString());
            }
        }
        return out;
    }

    /** "places" | "places forget <place|marker>". */
    String places(String rest) {
        List<String> w = Texts.words(rest == null ? "" : rest.toLowerCase(Locale.ROOT));
        if (w.size() == 2 && w.get(0).equals("forget")) return forget(w.get(1));
        if (!w.isEmpty()) return "usage: places | places forget <place|marker>";
        return PlaceWords.list(placeList());
    }

    private String forget(String name) {
        Map<String, JsonObject> places = Core.INSTANCE.knowledge.places();
        if (places.containsKey(name)) {
            c.putPlace(name, null);
            return "forgot the place " + name + " (and its markers)";
        }
        for (Map.Entry<String, JsonObject> e : places.entrySet()) {
            JsonObject o = e.getValue().deepCopy();
            if (!o.has("markers") || !o.get("markers").isJsonArray()) continue;
            JsonArray kept = new JsonArray();
            boolean hit = false;
            for (JsonElement m : o.getAsJsonArray("markers")) {
                if (m.getAsJsonObject().get("name").getAsString().equalsIgnoreCase(name)) hit = true;
                else kept.add(m);
            }
            if (hit) {
                o.add("markers", kept);
                c.putPlace(e.getKey(), o);
                return "forgot the marker " + name + " of " + e.getKey();
            }
        }
        return "I have no place or marker called " + name + " (places lists them)";
    }

    /** "marker <name> [of <place>] [x y z]". */
    String marker(String from, String rest) {
        PlaceWords.Marker m = PlaceWords.parseMarker(rest);
        if (m.error() != null) return m.error();
        Minecraft mc = Minecraft.getInstance();
        PolicyCommands.Pos pos = c.resolvePos(mc, m.coords(), from);
        if (pos == null) return "I can't see you - come closer or give coordinates - next: marker " + m.name() + " x y z";
        List<PlaceWords.Place> list = placeList();
        PlaceWords.Place owner = null;
        if (m.place() != null) {
            for (PlaceWords.Place q : list) if (q.name().equals(m.place())) owner = q;
            if (owner == null) return "I have no place called " + m.place() + " - place " + m.place() + " first";
        } else {
            owner = PlaceWords.nearest(list, pos.dim(), pos.x(), pos.y(), pos.z(), PlaceWords.MARKER_RANGE);
            if (owner == null) return "error: no place within " + PlaceWords.MARKER_RANGE + " blocks - say marker " + m.name() + " of <place>, or place <name> here first";
        }
        String[] err = new String[1];
        List<PlaceWords.Spot> ms = PlaceWords.withMarker(owner.markers(), new PlaceWords.Spot(m.name(), pos.x(), pos.y(), pos.z()), err);
        if (ms == null) return err[0];
        JsonObject o = Core.INSTANCE.knowledge.places().get(owner.name()).deepCopy();
        JsonArray arr = new JsonArray();
        for (PlaceWords.Spot s : ms) {
            JsonObject q = new JsonObject();
            q.addProperty("name", s.name());
            q.addProperty("x", s.x());
            q.addProperty("y", s.y());
            q.addProperty("z", s.z());
            arr.add(q);
        }
        o.add("markers", arr);
        c.putPlace(owner.name(), o);
        return "ok: marker " + m.name() + " of " + owner.name() + " at " + pos.x() + " " + pos.y() + " " + pos.z() + " (go " + owner.name() + " " + m.name() + ")";
    }

    /** "go <place> <marker>" or "go <marker>": the spot, or an error in err[0] (null spot and null err: not a marker line). */
    PlaceWords.Spot goSpot(String rest, String[] err) {
        List<String> w = Texts.words(rest == null ? "" : rest.toLowerCase(Locale.ROOT));
        if (w.isEmpty() || w.size() > 2) return null;
        return PlaceWords.resolveGo(placeList(), w.get(0), w.size() == 2 ? w.get(1) : null, err);
    }

    // ---- status ----

    /** " | deaths N in the last hour | stage X" (the stage from the stock view; left out when it can't be read). */
    String statusPart(LocalPlayer p) {
        int deaths = c.chains.recentDeaths(System.currentTimeMillis()).size();
        String stage = null;
        try {
            stage = GameStage.of(c.storage.stock(p).totals());
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] status stage: {}", e.toString());
        }
        return GameStage.statusPart(deaths, stage);
    }

    // ---- queue ----

    String queue(String from, String rest) {
        String r = rest == null ? "" : rest.trim();
        if (r.isEmpty()) {
            List<String> w = new ArrayList<>();
            for (String[] q : queued) w.add(q[1]);
            return QueueRules.show(c.chains.running() ? c.chains.chainStatus() : c.jobs.running() ? c.jobs.job.status : null, w);
        }
        if (r.equalsIgnoreCase("clear")) {
            int n = queued.size();
            queued.clear();
            return "ok: queue cleared (" + n + " tasks)";
        }
        String no = QueueRules.refusal(r);
        if (no != null) return no;
        if (ConfirmGate.kind(r) != null && !ConfirmGate.endsWithConfirm(r)) return "error: that is a big step - queue it with confirm at the end: queue " + r + " confirm";
        if (queued.size() >= QueueRules.MAX) return "error: I keep at most " + QueueRules.MAX + " queued tasks - queue clear, or wait";
        queued.add(new String[]{from, r});
        return "ok: queued " + r + " (" + queued.size() + " waiting; it starts when I'm free" + (c.heldInPlace() ? " - after dismiss" : "") + ")";
    }

    /** Once a second: the next queued task starts when nothing runs and nothing holds the bot. */
    void tick() {
        if (queued.isEmpty()) return;
        if (!QueueRules.mayStart(c.busyForQueue(), c.heldInPlace())) return;
        String[] q = queued.remove(0);
        LOG.info("[entropybot] queue: starting {} (for {})", q[1], q[0]);
        try {
            Chains.Reply r = c.handle(q[0], q[1], false, false, c.notifyListener(q[0]));
            if (r.text() != null && !r.text().isEmpty()) c.whisper(q[0], "queue: " + r.text().replaceFirst("^ok: ", ""));
        } catch (RuntimeException e) {
            c.whisper(q[0], "queue: " + q[1] + " failed to start: " + e);
        }
    }

    // ---- kinds ----

    static boolean isFood(String id) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            return rl != null && BuiltInRegistries.ITEM.containsKey(rl)
                    && BuiltInRegistries.ITEM.get(rl).components().has(net.minecraft.core.component.DataComponents.FOOD);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static List<String> allIds() {
        List<String> out = new ArrayList<>();
        for (ResourceLocation rl : BuiltInRegistries.ITEM.keySet()) out.add(rl.toString());
        return out;
    }

    Kinds.Rule rule(String kind) {
        JsonObject b = c.brainData();
        if (!b.has("kinds") || !b.get("kinds").isJsonObject()) return Kinds.Rule.none();
        JsonObject k = b.getAsJsonObject("kinds");
        if (!k.has(kind) || !k.get(kind).isJsonObject()) return Kinds.Rule.none();
        JsonObject o = k.getAsJsonObject(kind);
        TreeSet<String> ex = new TreeSet<>(), in = new TreeSet<>();
        if (o.has("exclude") && o.get("exclude").isJsonArray()) for (JsonElement e : o.getAsJsonArray("exclude")) ex.add(e.getAsString());
        if (o.has("include") && o.get("include").isJsonArray()) for (JsonElement e : o.getAsJsonArray("include")) in.add(e.getAsString());
        return new Kinds.Rule(ex, in);
    }

    private void saveRule(String kind, Kinds.Rule r) {
        JsonObject b = c.brainData();
        JsonObject k = b.has("kinds") && b.get("kinds").isJsonObject() ? b.getAsJsonObject("kinds") : new JsonObject();
        JsonObject o = new JsonObject();
        JsonArray ex = new JsonArray(), in = new JsonArray();
        r.exclude().forEach(ex::add);
        r.include().forEach(in::add);
        o.add("exclude", ex);
        o.add("include", in);
        k.add(kind, o);
        b.add("kinds", k);
        c.saved();
    }

    /** The ids of a kind with the owner's exclusions. */
    List<String> expand(String kind) {
        return Kinds.expand(kind, allIds(), VocabCommands::isFood, rule(kind));
    }

    /** "kinds" | "kinds <kind>" | "kinds <kind> exclude|include <id>". */
    String kinds(String rest) {
        List<String> w = Texts.words(rest == null ? "" : rest.toLowerCase(Locale.ROOT));
        if (w.isEmpty()) {
            Map<String, Kinds.Rule> rules = new java.util.LinkedHashMap<>();
            for (String k : Kinds.KINDS) rules.put(k, rule(k));
            return Kinds.list(rules, allIds(), VocabCommands::isFood);
        }
        if (!Kinds.isKind(w.get(0))) return "error: no kind " + w.get(0) + " - kinds: " + String.join(", ", Kinds.KINDS);
        if (w.size() == 1) {
            List<String> ids = expand(w.get(0));
            List<String> s = new ArrayList<>();
            for (int i = 0; i < Math.min(ids.size(), 40); i++) s.add(Texts.shortId(ids.get(i)));
            return w.get(0) + " (" + ids.size() + "): " + String.join(", ", s) + (ids.size() > 40 ? " (+" + (ids.size() - 40) + " more)" : "");
        }
        if (w.size() != 3) return "usage: kinds <kind> exclude|include <id>";
        String[] err = new String[1];
        Kinds.Rule r = Kinds.change(w.get(0), w.get(1), w.get(2), rule(w.get(0)), allIds(), VocabCommands::isFood, err);
        if (r == null) return err[0];
        saveRule(w.get(0), r);
        return "ok: " + w.get(0) + " now has " + expand(w.get(0)).size() + " ids" + (r.exclude().isEmpty() ? "" : " (not " + String.join(", ", shorts(r.exclude())) + ")");
    }

    private static List<String> shorts(java.util.Collection<String> ids) {
        List<String> out = new ArrayList<>();
        for (String s : ids) out.add(Texts.shortId(s));
        return out;
    }

    /**
     * A line whose item word is a kind, made concrete for the verbs that take one id (get, fetch, need, gather): the
     * kind's id with the most in stock. Null: no kind word in it. err[0]: the kind is empty.
     */
    String oneOfKind(LocalPlayer p, String rest, String[] err) {
        List<String> w = Texts.words(rest == null ? "" : rest);
        if (w.isEmpty() || !Kinds.isKind(w.get(0))) return null;
        List<String> ids = expand(w.get(0).toLowerCase(Locale.ROOT));
        Map<String, Integer> stock = null;
        try { stock = c.storage.stock(p).totals(); } catch (RuntimeException ignored) {}
        String id = Kinds.pick(ids, stock);
        if (id == null) {
            err[0] = "error: the kind " + w.get(0) + " is empty (kinds " + w.get(0) + ")";
            return null;
        }
        w.set(0, id);
        return String.join(" ", w);
    }

    /** "mine strip|cave ores ...": the ores kind as the ore list (with the exclusions); "mine logs" is cut. Null: unchanged. */
    String mineKinds(String rest, String[] redirect) {
        List<String> w = Texts.words(rest == null ? "" : rest.toLowerCase(Locale.ROOT));
        if (w.isEmpty()) return null;
        if (w.get(0).equals("logs") || w.get(0).equals("wood")) {
            String n = w.size() > 1 && w.get(1).matches("^\\d+$") ? w.get(1) : "16";
            redirect[0] = "cut " + n + " logs";
            return null;
        }
        if ((w.get(0).equals("strip") || w.get(0).equals("cave")) && w.size() > 1 && w.get(1).equals("ores")) {
            Kinds.Rule r = rule("ores");
            String list;
            if (r.exclude().isEmpty() && r.include().isEmpty()) list = "any";
            else list = String.join(",", shorts(expand("ores")));
            w.set(1, list);
            return String.join(" ", w);
        }
        if (Kinds.isKind(w.get(0))) {
            redirect[0] = "error: mine " + w.get(0) + " isn't a mine job - say mine strip ores, mine cave ores" + (w.get(0).equals("stone") ? ", or dig an area" : "");
            return null;
        }
        return null;
    }

    /** "cut <n> [logs|wood|<log id or type>]" | "cut trees <n> [type]" | "cut status": the chop engine's line. */
    String cutLine(String rest) {
        List<String> w = Texts.words(rest == null ? "" : rest.toLowerCase(Locale.ROOT));
        int i = w.indexOf("logs") >= 0 ? w.indexOf("logs") : w.indexOf("wood");
        if (i >= 0) {
            Kinds.Rule r = rule("logs");
            if (r.exclude().isEmpty()) w.remove(i);
            else {
                List<String> ex = new ArrayList<>();
                for (String s : r.exclude()) ex.add(s.substring(s.indexOf(':') + 1));
                w.set(i, io.github.mojolowjo.entropybot.chop.ChopRules.EXCEPT + String.join(".", ex));
            }
        }
        return String.join(" ", w);
    }
}
