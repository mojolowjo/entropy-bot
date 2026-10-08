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

    // ---- fighting: defend, guard, dismiss, attack, defence players (V1b-2) ----

    /** "defence players on|off" (owner only, never saved: off at every game start, decision 10). */
    static volatile boolean defencePlayers;

    /** "defend": stay within 4 blocks of where I stand and fight what comes, until stop, dismiss or another order. */
    String defend(String from, LocalPlayer p) {
        if (!Core.INSTANCE.reflexes.defence()) return Hints.next("error: self-defence is off, so I couldn't fight here", "defence on");
        String busy = c.chainBusyTextPub();
        if (busy != null) return busy;
        int[] me = Jobs.here(p);
        hold = new Hold("defending " + me[0] + " " + me[1] + " " + me[2], Guard.dimOf(p.level()), me[0], me[1], me[2], io.github.mojolowjo.entropybot.vocab.HoldRules.DEFEND_R, null, from);
        LOG.info("[entropybot] {}", hold.what());
        return "ok: defending this spot (" + me[0] + " " + me[1] + " " + me[2] + "): I stay within " + hold.r() + " blocks and fight what comes - dismiss or stop ends it";
    }

    /** "guard <area|place|marker>": stay in that area (its box) or within 8 of the place or marker, fight what comes. */
    String guard(String from, String name, LocalPlayer p) {
        if (!Core.INSTANCE.reflexes.defence()) return Hints.next("error: self-defence is off, so I couldn't fight there", "defence on");
        String busy = c.chainBusyTextPub();
        if (busy != null) return busy;
        String n = name.trim().toLowerCase(Locale.ROOT);
        String dim = Guard.dimOf(p.level());
        JsonObject a = c.policyArea(n);
        if (a != null) {
            if (a.has("round")) return "error: " + n + " is a circle - guard a box area, a place or a marker";
            if (!PolicyCommands.dimOf(a).equals(dim)) return "error: " + n + " is in " + PolicyCommands.dimOf(a);
            boolean allY = !PolicyCommands.hasY(a);
            int[] box = {PolicyCommands.n(a, "x1"), allY ? 1 : PolicyCommands.n(a, "y1"), PolicyCommands.n(a, "z1"), PolicyCommands.n(a, "x2"), allY ? 0 : PolicyCommands.n(a, "y2"), PolicyCommands.n(a, "z2")};
            int[] h = io.github.mojolowjo.entropybot.vocab.HoldRules.home(box, 0, Jobs.here(p)[1], 0);
            hold = new Hold("guarding the area " + n, dim, h[0], h[1], h[2], 0, box, from);
        } else {
            String[] err = new String[1];
            PlaceWords.Spot s = PlaceWords.resolveGo(placeList(), n, null, err);
            if (s == null) return "error: I have no area, place or marker called " + n + " (area list, places)";
            hold = new Hold("guarding " + s.name(), dim, s.x(), s.y(), s.z(), io.github.mojolowjo.entropybot.vocab.HoldRules.GUARD_R, null, from);
        }
        String why = c.jobs.goalAllowed(hold.x(), hold.y(), hold.z());
        if (why != null && !from.equalsIgnoreCase(c.owner())) {
            hold = null;
            return FenceRules.gotoRefusal(why);
        }
        LOG.info("[entropybot] {}", hold.what());
        return "ok: " + hold.what() + ": I stay there and fight what comes - dismiss or stop ends it";
    }

    /** Ends defend/guard (null: none ran). */
    String endHold(String why) {
        if (hold == null) return null;
        String t = "stopped " + hold.what() + " (" + why + ")";
        hold = null;
        LOG.info("[entropybot] {}", t);
        return t;
    }

    /** "dismiss": from the owner, ends the escort and defend/guard; from the escorted player, their escort. */
    String dismiss(String from, LocalPlayer p) {
        boolean owner = from.equalsIgnoreCase(c.owner());
        io.github.mojolowjo.entropybot.engine.Escort e = Core.INSTANCE.reflexes.escort;
        List<String> done = new ArrayList<>();
        if (e.active() && (owner || from.equalsIgnoreCase(e.name()))) done.add(c.escortCommand(from, "off", p).replaceFirst("^ok: ", ""));
        if (owner) {
            String h = endHold("dismissed");
            if (h != null) done.add(h);
        }
        if (done.isEmpty()) return e.active() ? "sorry, only " + c.owner() + " or " + e.name() + " can end this escort" : "ok: nothing to dismiss (no escort, defend or guard)";
        return "ok: " + String.join("; ", done);
    }

    /** Once a second while defend/guard holds: after a fight, walk back when out of the spot. */
    void holdTick(LocalPlayer p) {
        Hold h = hold;
        if (h == null || Core.INSTANCE.reflexes.hold()) return;
        if (!Core.INSTANCE.reflexes.defence()) {
            c.whisper(h.from(), endHold("self-defence was switched off"));
            return;
        }
        if (!Guard.dimOf(p.level()).equals(h.dim())) {
            c.whisper(h.from(), endHold("I'm in another dimension"));
            return;
        }
        if (c.jobs.running()) return;
        int[] me = Jobs.here(p);
        if (!io.github.mojolowjo.entropybot.vocab.HoldRules.outside(h.box(), h.x(), h.z(), h.r(), me[0], me[1], me[2])) return;
        int[] to = io.github.mojolowjo.entropybot.vocab.HoldRules.home(h.box(), h.x(), h.y(), h.z());
        String r = c.jobs.startTravel("goto " + to[0] + " " + to[1] + " " + to[2], "back to " + h.what().replaceFirst("^(defending|guarding) ", ""), to, h.dim(), true);
        if (!r.startsWith("ok")) LOG.info("[entropybot] hold: couldn't walk back: {}", r);
    }

    /** "attack <mob kind|player|id> [confirm]" | "attack nearest" | "attack target <id>" (the companion's point key). */
    String attack(String from, String rest, LocalPlayer p) {
        io.github.mojolowjo.entropybot.vocab.AttackRules.Arg a = io.github.mojolowjo.entropybot.vocab.AttackRules.parse(rest);
        if (a.error() != null) return a.error();
        if (!Core.INSTANCE.reflexes.defence()) return Hints.next("error: self-defence is off, so I don't fight", "defence on");
        if (a.how() == io.github.mojolowjo.entropybot.vocab.AttackRules.How.NEAREST) return Core.INSTANCE.reflexes.attack("nearest");
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.world.entity.Entity e = null;
        boolean typed = false;
        if (a.how() == io.github.mojolowjo.entropybot.vocab.AttackRules.How.ID) {
            e = mc.level.getEntity(a.id());
            if (e == null) return "error: I can't see entity " + a.id() + " (too far for me, or gone)";
        } else {
            typed = true;
            for (net.minecraft.world.entity.player.Player pl : mc.level.players()) {
                if (pl != p && pl.getGameProfile().getName().equalsIgnoreCase(a.word())) e = pl;
            }
            if (e == null) {
                String want = a.word().indexOf(':') >= 0 ? a.word() : "minecraft:" + a.word();
                double best = Double.MAX_VALUE;
                for (net.minecraft.world.entity.Entity q : mc.level.entitiesForRendering()) {
                    if (q == p || !(q instanceof net.minecraft.world.entity.LivingEntity le) || !le.isAlive()) continue;
                    String id = BuiltInRegistries.ENTITY_TYPE.getKey(q.getType()).toString();
                    if (!id.equals(want)) continue;
                    double d = p.distanceTo(q);
                    if (d < best) { best = d; e = q; }
                }
                if (e == null) return "error: I see no " + a.word() + " (and no player of that name) near me";
            }
        }
        if (!(e instanceof net.minecraft.world.entity.LivingEntity le) || !le.isAlive()) return "error: that isn't alive";
        boolean player = e instanceof net.minecraft.world.entity.player.Player;
        String kind = player ? ((net.minecraft.world.entity.player.Player) e).getGameProfile().getName() : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
        boolean pet = !player && io.github.mojolowjo.entropybot.engine.Hostility.protectedMob(e);
        boolean hostile = !player && !pet && io.github.mojolowjo.entropybot.engine.Hostility.INSTANCE.kind(e, true).counts();
        String custom = !player && e.hasCustomName() && e.getCustomName() != null ? e.getCustomName().getString() : null;
        if (player && !from.equalsIgnoreCase(c.owner())) return "sorry, only " + c.owner() + " can set me on a player";
        var ans = io.github.mojolowjo.entropybot.vocab.AttackRules.decide(new io.github.mojolowjo.entropybot.vocab.AttackRules.Target(kind, player, pet, hostile, custom, typed),
                a.confirm(), defencePlayers, e.getId());
        LOG.info("[entropybot] attack {} #{}: {} ({})", kind, e.getId(), ans.verdict(), ans.text());
        if (ans.verdict() != io.github.mojolowjo.entropybot.vocab.AttackRules.Verdict.GO) return ans.text();
        return Core.INSTANCE.reflexes.forceAttack(e, kind);
    }

    // ---- needs, goals, release, sleep auto, camp (V1b-3) ----

    private JsonObject obj(String key) {
        JsonObject b = c.brainData();
        if (!b.has(key) || !b.get(key).isJsonObject()) b.add(key, new JsonObject());
        return b.getAsJsonObject(key);
    }

    /** "need <item> <n>": a standing need (the brain gathers toward it). Null: no count (the old answer runs). */
    String needStanding(LocalPlayer p, String rest) {
        String[] s = io.github.mojolowjo.entropybot.vocab.NeedWords.standing(rest);
        if (s == null) return null;
        String id = Kinds.isKind(s[0]) ? s[0] : Commands.resolveItemNamePub(s[0]);
        if (id == null) return "error: I know no item called " + s[0];
        JsonObject needs = obj("needs");
        int n = Integer.parseInt(s[1]);
        if (n == 0) {
            needs.remove(id);
            c.saved();
            return "ok: no standing need for " + Texts.shortId(id) + " any more";
        }
        if (!needs.has(id) && needs.size() >= io.github.mojolowjo.entropybot.vocab.NeedWords.MAX_NEEDS)
            return "error: I keep at most " + io.github.mojolowjo.entropybot.vocab.NeedWords.MAX_NEEDS + " needs - needs clear <item> first";
        needs.addProperty(id, n);
        c.saved();
        return "ok: standing need " + Texts.shortId(id) + " " + n + " (have " + have(p, id) + ") - needs lists them";
    }

    /** The stock view keyed by full ids (it keeps vanilla ids short). */
    private Map<String, Integer> fullStock(LocalPlayer p) {
        Map<String, Integer> out = new java.util.HashMap<>();
        c.storage.stock(p).totals().forEach((k, v) -> out.merge(k.indexOf(':') >= 0 ? k : "minecraft:" + k, v, Integer::sum));
        return out;
    }

    /** B1: what the group has of an item or kind (the brain scores the owner's needs from it). */
    int haveOf(LocalPlayer p, String id) { return have(p, id); }

    private int have(LocalPlayer p, String id) {
        try {
            Map<String, Integer> t = fullStock(p);
            if (!Kinds.isKind(id)) return t.getOrDefault(id, 0);
            int sum = 0;
            for (String k : expand(id)) sum += t.getOrDefault(k, 0);
            return sum;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** "needs" | "needs clear <item>|all". */
    String needs(LocalPlayer p, String rest) {
        List<String> w = Texts.words(rest == null ? "" : rest.toLowerCase(Locale.ROOT));
        JsonObject needs = obj("needs");
        if (w.size() == 2 && w.get(0).equals("clear")) {
            if (w.get(1).equals("all")) {
                c.brainData().add("needs", new JsonObject());
                c.saved();
                return "ok: no standing needs";
            }
            String id = Kinds.isKind(w.get(1)) ? w.get(1) : Kinds.norm(w.get(1));
            if (needs.remove(id) == null) return "error: no standing need for " + w.get(1) + " (needs lists them)";
            c.saved();
            return "ok: no standing need for " + Texts.shortId(id) + " any more";
        }
        if (!w.isEmpty()) return "usage: needs | needs clear <item>|all";
        Map<String, Integer> want = new java.util.LinkedHashMap<>(), have = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : needs.entrySet()) {
            want.put(e.getKey(), e.getValue().getAsInt());
            have.put(e.getKey(), have(p, e.getKey()));
        }
        return io.github.mojolowjo.entropybot.vocab.NeedWords.list(want, have);
    }

    /** B3: a goal (its text and the chain or goal_ routine that does it) queued for the brain (max 10). */
    String noteGoal(String text, String chain) {
        record G(String text, String chain) {}
        G g = new G(text, chain);
        JsonObject b = c.brainData();
        JsonArray goals = b.has("goals") && b.get("goals").isJsonArray() ? b.getAsJsonArray("goals") : new JsonArray();
        if (goals.size() >= io.github.mojolowjo.entropybot.vocab.NeedWords.MAX_GOALS) return "error: I keep at most " + io.github.mojolowjo.entropybot.vocab.NeedWords.MAX_GOALS + " goals - goals clear <n>|all first";
        JsonObject o = new JsonObject();
        o.addProperty("text", g.text());
        o.addProperty("chain", g.chain());
        o.addProperty("at", System.currentTimeMillis());
        goals.add(o);
        b.add("goals", goals);
        c.saved();
        return "ok: goal " + g.text() + " noted (#" + goals.size() + ") - the brain works on goals when nothing else is asked (brain on); to do it now: " + g.chain();
    }

    /** B3: a finished goal leaves the list (the brain's goal job ended well): the first goal with that chain. */
    void goalDone(String chain) {
        JsonObject b = c.brainData();
        if (!b.has("goals") || !b.get("goals").isJsonArray()) return;
        JsonArray goals = b.getAsJsonArray("goals");
        for (int i = 0; i < goals.size(); i++) {
            try {
                if (goals.get(i).getAsJsonObject().get("chain").getAsString().equals(chain)) {
                    goals.remove(i);
                    c.saved();
                    return;
                }
            } catch (RuntimeException ignored) {}
        }
    }

    /** "goals" | "goals clear <n>|all". */
    String goals(String rest) {
        List<String> w = Texts.words(rest == null ? "" : rest.toLowerCase(Locale.ROOT));
        JsonObject b = c.brainData();
        JsonArray goals = b.has("goals") && b.get("goals").isJsonArray() ? b.getAsJsonArray("goals") : new JsonArray();
        if (w.size() == 2 && w.get(0).equals("clear")) {
            if (w.get(1).equals("all")) {
                b.add("goals", new JsonArray());
                io.github.mojolowjo.entropybot.brain.Brain.forgetGoalParks(b, null);       // 0.23.1: no park outlives the goals
                c.saved();
                return "ok: no goals";
            }
            if (!w.get(1).matches("^\\d{1,2}$") || Integer.parseInt(w.get(1)) < 1 || Integer.parseInt(w.get(1)) > goals.size()) return "error: no goal " + w.get(1) + " (goals lists them)";
            JsonElement gone = goals.remove(Integer.parseInt(w.get(1)) - 1);
            try { io.github.mojolowjo.entropybot.brain.Brain.forgetGoalParks(b, gone.getAsJsonObject().get("text").getAsString()); } catch (RuntimeException ignored) {}
            b.add("goals", goals);
            c.saved();
            return "ok: goal " + w.get(1) + " cleared";
        }
        if (!w.isEmpty()) return "usage: goals | goals clear <n>|all";
        if (goals.isEmpty()) return "no goals - " + io.github.mojolowjo.entropybot.vocab.NeedWords.GOAL_USAGE;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < goals.size(); i++) out.add((i + 1) + ". " + goals.get(i).getAsJsonObject().get("text").getAsString());
        return "goals: " + String.join(", ", out);
    }

    /** "done" / "free": the owner releases the bot (the brain's rule 2: it may leave 32 blocks of the owner). */
    String release() {
        JsonObject b = c.brainData();
        b.addProperty("released", true);
        b.addProperty("releasedAt", System.currentTimeMillis());
        c.saved();
        return "ok: released - I may do what I want now (with brain on: further than 32 blocks from you); your next order, come or escort takes me back";
    }

    boolean released() {
        JsonObject b = c.brainData();
        return b.has("released") && b.get("released").getAsBoolean();
    }

    /** The owner's next direct order lifts the release. */
    void unrelease(String verb) {
        if (!released() || !io.github.mojolowjo.entropybot.vocab.NeedWords.liftsRelease(verb)) return;
        c.brainData().addProperty("released", false);
        c.saved();
        LOG.info("[entropybot] release lifted by {}", verb);
    }

    /** "sleep auto on|off" (default on; the brain's night branch reads it), "sleep auto". Null: not that form. */
    String sleepAuto(String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        Boolean v = io.github.mojolowjo.entropybot.vocab.NeedWords.sleepAuto(r);
        JsonObject b = c.brainData();
        if (v != null) {
            b.addProperty("sleepAuto", v);
            c.saved();
            return "ok: sleep auto " + (v ? "on: I go to bed when others sleep (with brain on)" : "off: I only sleep when you say sleep");
        }
        if (r.equals("auto")) return "sleep auto is " + (!b.has("sleepAuto") || b.get("sleepAuto").getAsBoolean() ? "on" : "off") + " - sleep auto on|off";
        return null;
    }

    /**
     * "camp here": an area of 24 round the owner (or me), type neutral, named camp; the place camp with a bed marker; then
     * torches round it (and my bed put down when I carry one). Not a base: no main area, no setbase.
     */
    String campHere(String from, LocalPlayer p, JobRequests.Listener l) {
        Minecraft mc = Minecraft.getInstance();
        PolicyCommands.Pos at = c.hereOf(mc, from);
        String name = io.github.mojolowjo.entropybot.vocab.NeedWords.campName(n -> c.policyArea(n) != null);
        if (name == null) return "error: camp .. camp9 are all taken - area del <name> confirm for an old camp first";
        String area = c.policyCommand("area", "here 24 " + name + " neutral", from, mc, p);
        if (!area.startsWith("ok")) return "error: couldn't make the camp area: " + area;
        String place = c.placeCommand("mark", name + " " + at.x() + " " + at.y() + " " + at.z(), from, p);
        String bedMark = marker(from, "bed of " + name + " " + at.x() + " " + at.y() + " " + at.z());
        String bed = null;
        for (int i = 0; i < 36; i++) {
            var st = p.getInventory().getItem(i);
            if (!st.isEmpty() && Commands.itemId(st).endsWith("_bed")) { bed = Commands.itemId(st); break; }
        }
        // 0.23.1: the light step comes last (it fetches or crafts its torches, or says why there are none)
        String chain = (bed != null ? "place " + Texts.shortId(bed) + " " + (at.x() + 2) + " " + at.y() + " " + at.z() + " then " : "") + "light here 12";      // 2 off: the bed's head never lands on me
        Chains.Reply r = c.handle(from, chain, false, true, l);
        LOG.info("[entropybot] camp here: area {}, place {}, bed marker {}, chain {} -> {}", area, place, bedMark, chain, r.text());
        return "ok: camp at " + at.x() + " " + at.y() + " " + at.z() + " (area " + name + ", 24 round, neutral; place " + name + ", marker bed) - " + String.valueOf(r.text()).replaceFirst("^ok: ", "")
                + (bed == null ? " (no bed on me: sleep there with sleep when you bring one)" : "");
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
        String brain = null;
        try { brain = c.brainRuntime == null ? null : c.brainRuntime.brain.statusPart(); } catch (RuntimeException e) { LOG.warn("[entropybot] status brain: {}", e.toString()); }
        return GameStage.statusPart(deaths, stage, brain);
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
        try { stock = fullStock(p); } catch (RuntimeException ignored) {}
        String kind = w.get(0).toLowerCase(Locale.ROOT);
        String id;
        if (kind.equals("food")) {
            // 0.24.3: stock first, then plain foods, a modded food only when its whole plan resolves
            io.github.mojolowjo.entropybot.gather.GatherPlan.World gw;
            try { gw = c.gathering.world(p); } catch (RuntimeException e) { gw = null; LOG.warn("[entropybot] food choice: no gather view", e); }
            final io.github.mojolowjo.entropybot.gather.GatherPlan.World fw = gw;
            boolean farm;
            try { farm = io.github.mojolowjo.entropybot.Core.INSTANCE.knowledge.places().get("farm") != null; } catch (RuntimeException e) { farm = false; }
            id = Kinds.pickFood(ids, stock, farm, x -> fw != null && io.github.mojolowjo.entropybot.gather.GatherPlan.resolves(x, 1, fw));
            if (id == null) {
                err[0] = "error: no way to get food: none in storage, and no food I know how to make (bread needs wheat: a farm) - put some food in the base chests";
                return null;
            }
        } else id = Kinds.pick(ids, stock);
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
