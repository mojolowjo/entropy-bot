package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.clear.ClearBox;
import io.github.mojolowjo.entropybot.guard.Box;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import io.github.mojolowjo.entropybot.guard.Policy;
import io.github.mojolowjo.entropybot.strip.McStripWorld;
import io.github.mojolowjo.entropybot.strip.MineBook;
import io.github.mojolowjo.entropybot.strip.MineGeom;
import io.github.mojolowjo.entropybot.strip.OreSpec;
import io.github.mojolowjo.entropybot.strip.StripPlan;
import io.github.mojolowjo.entropybot.strip.StripRules;
import io.github.mojolowjo.entropybot.strip.StripTexts;
import io.github.mojolowjo.entropybot.strip.StripWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B7d D2: the strip mine in the mod (the bridge's startStripmine, stripTurn, mineCommand's "mine strip" and oresCommand's
 * "ores prefer"), with the same wording. The verbs return the reply text; a run is a {@link Seq} job whose steps are in
 * {@link StripSteps}. The mines' progress lives in commands.json ("mine", "mines", as memory.json had them; moved over
 * once from the bridge's memory.json, "minesMoved" marks it) and so does "orePrefer". The mine itself is a place
 * (places.json, "mark mine"): a turn moves it there.
 *
 * <p>Wiring (B7d merge): {@code StripMine.get()} needs nothing else; see docs/b7d-d2.md for the dispatcher lines.
 */
public final class StripMine {
    private static final Logger LOG = LogUtils.getLogger();
    private static StripMine instance;

    private final Core core;
    private boolean imported;
    private List<String> oreIds;

    StripMine(Core core) { this.core = core; }

    public static synchronized StripMine get() {
        if (instance == null) instance = new StripMine(Core.INSTANCE);
        return instance;
    }

    Commands commands() { return core.commands; }

    Crafting crafting() { return core.commands.crafting; }

    Storage storage() { return core.commands.storage; }

    // ---- the notes (commands.json) ----

    /** commands.json; the bridge's mine notes and preferred ores are moved over the first time. */
    JsonObject data() {
        JsonObject b = commands().brainData();
        if (!imported && commands().ready()) {
            imported = true;
            if (!b.has("minesMoved")) {
                JsonObject mem = readMemoryJson();
                int n = 0;
                if (mem != null) {
                    for (String k : new String[]{"mine", "mines", "orePrefer"}) {
                        if (!b.has(k) && mem.has(k) && !mem.get(k).isJsonNull()) {
                            b.add(k, mem.get(k).deepCopy());
                            n++;
                        }
                    }
                }
                b.addProperty("minesMoved", true);
                saved();
                LOG.info("[entropybot] strip mine: {} notes moved over from memory.json (mine, mines, orePrefer)", n);
            }
        }
        return b;
    }

    private static JsonObject readMemoryJson() {
        try {
            Path p = Minecraft.getInstance().gameDirectory.toPath().resolve(Commands.BRIDGE_DIR).resolve("memory.json");
            if (!Files.exists(p)) return null;
            return JsonStore.parse(Files.readString(p, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    MineBook book() { return new MineBook(data()); }

    void saved() { commands().saved(); }

    JsonObject place(String name) { return core.knowledge.places().get(name); }

    String dim(LocalPlayer p) { return Guard.dimOf(p.level()); }

    String placeDim(JsonObject place, LocalPlayer p) {
        String d = MineBook.str(place, "dim");
        return d != null ? d : dim(p);
    }

    StripWorld world(LocalPlayer p) { return new McStripWorld(p.level(), p); }

    /** The mine's table (a crafting table candidate for others), or null. */
    public int[] mineTable() {
        if (!commands().ready()) return null;
        var t = MineBook.table(book().current());
        return t == null ? null : new int[]{t.x(), t.y(), t.z()};
    }

    /** Old mine notes past the cap go, never one a marked place points at. */
    private void prune() {
        Set<String> live = new HashSet<>();
        for (JsonObject pl : core.knowledge.places().values()) {
            MineGeom g = MineBook.geom(pl);
            if (g != null) live.add(g.key());
        }
        int n = book().prune(live);
        if (n > 0) LOG.info("[entropybot] strip mine: dropped the oldest {} old mine notes (over the limit)", n);
    }

    // ---- the areas (where ores are mined, what a strip dig may lease) ----

    private static Policy policy() { return Guard.INSTANCE.core.policy(); }

    /** inMapArea with the mod: one area's x/z range holds x z (y is ignored here). */
    boolean inArea(String dim, int x, int z) {
        for (Box a : policy().areas) if (a.dim.equals(dim) && x >= a.x1 && x <= a.x2 && z >= a.z1 && z <= a.z2) return true;
        return false;
    }

    /** boxInMapArea: one area's x/z range holds the whole box. */
    boolean boxInArea(String dim, ClearBox b) {
        for (Box a : policy().areas) if (a.dim.equals(dim) && b.x1() >= a.x1 && b.x2() <= a.x2 && b.z1() >= a.z1 && b.z2() <= a.z2) return true;
        return false;
    }

    String areaText() {
        List<String> names = new ArrayList<>();
        for (Box a : policy().areas) names.add(a.name != null ? a.name : "box");
        return StripTexts.areaText(names);
    }

    /**
     * Would the guard refuse this dig's lease (strict mode: the box, and the torch shell, must lie inside an area)? Its
     * reason, or null. The clear would end the errand on that refusal; asked first, the run turns or skips instead.
     */
    String areaRefusal(LocalPlayer p, ClearBox b, boolean torches) {
        GuardCore g = Guard.INSTANCE.core;
        if (g.mode() != GuardCore.Mode.STRICT) return null;
        Policy pol = g.policy();
        String dim = dim(p);
        List<ClearBox> boxes = new ArrayList<>(List.of(b));
        if (torches) boxes.add(b.grow(1));
        for (ClearBox x : boxes) {
            if (!pol.areaCovers(new Box(null, dim, x.x1(), x.y1(), x.z1(), x.x2(), x.y2(), x.z2()))) {
                return pol.areas.isEmpty() ? "no areas set" : "that box is not inside one of my areas";
            }
        }
        return null;
    }

    // ---- ore lists ----

    List<String> oreIds() {
        if (oreIds == null) {
            List<String> out = new ArrayList<>();
            for (ResourceLocation rl : BuiltInRegistries.BLOCK.keySet()) {
                String id = rl.toString();
                if (OreSpec.oreId(id)) out.add(id);
            }
            oreIds = out;
        }
        return oreIds;
    }

    /** The default ore list of "mine strip" and "mine cave" (commands.json "orePrefer"), or null. */
    public String orePrefer() {
        if (!commands().ready()) return null;
        JsonObject b = data();
        return b.has("orePrefer") && b.get("orePrefer").isJsonPrimitive() ? b.get("orePrefer").getAsString() : null;
    }

    /** "ores prefer [list]" (rest = what follows "ores"). Guests only get the show form (Texts.guestRefusal). */
    public String oresPrefer(String rest) {
        String want = (rest == null ? "" : rest.trim().toLowerCase()).replaceFirst("^prefer\\s*", "");
        if (want.isEmpty()) return StripTexts.preferShow(orePrefer());
        OreSpec s = OreSpec.parse(want, oreIds());
        if (s.err != null) return "error: " + s.err;
        data().addProperty("orePrefer", s.label);
        saved();
        return StripTexts.preferSet(s.label);
    }

    // ---- the verbs ----

    /** "stripmine [branches] [length] | status | reset | ores [collect|list] | turn left|right". */
    public String command(LocalPlayer p, String rest) {
        return start(p, rest, null, 0, "mine");
    }

    private static final Pattern STRIP = Pattern.compile("^strip\\b\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern AT = Pattern.compile("\\s*\\bat\\s+([a-z0-9_-]+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT = Pattern.compile("(^|\\s+)(\\d+)(m?)\\s*$", Pattern.CASE_INSENSITIVE);

    /** "mine strip <ores> [n] [at <mine>]" (text = what follows "mine"): runs one after another until n of the listed ores are mined. */
    public String mineStrip(LocalPlayer p, String text) {
        String t = text == null ? "" : text.trim();
        Matcher m = STRIP.matcher(t);
        if (!m.find()) return StripTexts.MINE_GRAMMAR;
        String rest = m.group(1), at = null;
        int n = 0, minutes = 0;
        Matcher a = AT.matcher(rest);
        if (a.find()) {
            at = a.group(1).toLowerCase();
            rest = rest.substring(0, a.start());
        }
        // a count, minutes ("10m"), or both, after the ores
        for (Matcher c = COUNT.matcher(rest); c.find(); c = COUNT.matcher(rest)) {
            int v = Integer.parseInt(c.group(2));
            if (!c.group(3).isEmpty()) minutes = v;
            else n = v;
            rest = rest.substring(0, c.start());
        }
        rest = rest.trim();
        if (rest.isEmpty()) rest = orePrefer() != null ? orePrefer() : "";
        OreSpec spec = OreSpec.parse(rest, oreIds());
        if (spec.err != null) return "error: " + spec.err + (rest.isEmpty() ? " - or set a default with \"ores prefer iron,diamond\"" : "");
        if (minutes != 0) return "error: a strip mine counts ores, not minutes: mine strip " + spec.label + " 16";
        return start(p, "", spec, n, at != null ? at : "mine");
    }

    /** JavaScript's parseInt(w) || def: the leading digits, or def when there are none or they make 0. */
    static int leadInt(String w, int def) {
        Matcher m = Pattern.compile("^[+-]?\\d+").matcher(w == null ? "" : w.trim());
        if (!m.find()) return def;
        try {
            int v = Integer.parseInt(m.group());
            return v == 0 ? def : v;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** startStripmine. spec != null: "mine strip" (collects, runs until target of the listed ores). */
    String start(LocalPlayer p, String text, OreSpec spec, int target, String mineName) {
        JsonObject m = place(mineName);
        MineGeom g = MineBook.geom(m);
        String[] w = (text == null ? "" : text.trim().toLowerCase()).split("\\s+");
        String p0 = w.length > 0 ? w[0] : "", p1 = w.length > 1 ? w[1] : "";
        if (g == null) return StripTexts.noMine(mineName);
        String mdim = MineBook.str(m, "dim");
        if (mdim != null && !mdim.equals(dim(p))) return "error: the mine is in " + mdim;
        MineBook book = book();
        JsonObject note = book.select(g.key());
        prune();
        saved();
        boolean wantCollect = spec != null || MineBook.collect(note);
        switch (p0) {
            case "reset" -> {
                book.reset(g.key());
                saved();
                return StripTexts.resetReply();
            }
            case "status" -> {
                return StripTexts.status(g, note, wantCollect);
            }
            case "ores" -> {
                if (p1.equals("collect") || p1.equals("list")) {
                    boolean c = p1.equals("collect");
                    note.addProperty("collectOres", c);
                    saved();
                    return StripTexts.oresSet(c, areaText());
                }
                return StripTexts.oresMode(wantCollect);
            }
            case "turn" -> {
                // package A: a new mine at the end of the corridor, at a right angle
                if (!p1.equals("left") && !p1.equals("right")) return StripTexts.TURN_USAGE;
                TurnResult tr = turn(p, mineName, p1, "you asked", true);
                return tr.err() != null ? "error: " + tr.err() : "ok: " + tr.ok();
            }
            default -> { }
        }
        StripWorld world = world(p);
        // a new mine whose entrance is solid rock (marked by coordinates) can't be reached: say so instead of trying
        if (!MineBook.setup(note) && MineBook.k(note) == 1 && world.loaded(g.x, g.y, g.z)) {
            String bad = StripRules.standCheck(world, g.start());
            if (bad != null) return StripTexts.badEntrance(mineName, g.start(), bad);
        }
        int branches = Math.min(Math.max(leadInt(p0, 1), 1), 10), length = Math.min(Math.max(leadInt(p1, 12), 4), 32);
        String areaDim = mdim != null ? mdim : dim(p);
        StripPlan.Run plan = StripPlan.run(g, note, branches, length, wantCollect, b -> boxInArea(areaDim, b), Jobs.here(p));
        if (spec != null && !inArea(areaDim, g.x, g.z)) return StripTexts.outsideAreas(mineName, g.start(), areaText());
        StripSteps.Run run = new StripSteps.Run(mineName, spec, target, g.key());
        List<Seq.Step> steps = StripSteps.toSteps(plan.items(), run);
        String label = plan.label();
        if (spec != null) {
            StripSteps.Data nx = new StripSteps.Data(run);
            nx.length = length;
            steps.add(StripSteps.make("stripnext", nx));
            label = StripTexts.stripJobLabel(spec, target, mineName);
            run.deadline = core.tick() + StripRules.MAX_TICKS;
        }
        Jobs jobs = commands().jobs;
        return jobs.startSeq(new Seq(jobs, storage(), label, steps, "always"), "always");
    }

    /** A turn's answer: ok ("mine turned west at x y z - the next run digs there") or err. */
    public record TurnResult(String ok, String err) {}

    /**
     * stripTurn: "stripmine turn left|right" (only), or a corridor that can't go on (only = null: left, right, then a
     * fresh mine 8 along the last branch, on or out): a new mine at the last open corridor cell, checked first. It
     * keeps the old mine's chests and table; the old mine's progress stays in "mines". quiet: no whisper.
     */
    TurnResult turn(LocalPlayer p, String mineName, String only, String why, boolean quiet) {
        JsonObject m = place(mineName);
        MineGeom g = MineBook.geom(m);
        if (g == null) return new TurnResult(null, "no mine marked - PM \"mark mine\"");
        String dim = dim(p), mdim = MineBook.str(m, "dim");
        if (mdim != null && !mdim.equals(dim)) return new TurnResult(null, "the mine is in " + mdim);
        MineBook book = book();
        JsonObject old = book.select(g.key());
        StripWorld w = world(p);
        Jobs jobs = commands().jobs;
        boolean fence = commands().fenceOn();
        StripRules.Turn t = StripRules.chooseTurn(StripRules.turnOptions(g, MineBook.k(old), only),
                o -> StripRules.mineSpotCheck(w, o.pos(), o.dir(), q -> jobs.goalAllowed(q.x(), q.y(), q.z()),
                        (x, y, z) -> io.github.mojolowjo.entropybot.api.BotAPI.check(dim, x, y, z, "break"), fence));
        if (t.err() != null) return new TurnResult(null, t.err());
        JsonObject pl = new JsonObject();
        pl.addProperty("x", t.option().pos().x());
        pl.addProperty("y", t.option().pos().y());
        pl.addProperty("z", t.option().pos().z());
        pl.addProperty("dim", mdim != null ? mdim : dim);
        pl.addProperty("dir", t.option().dir());
        commands().putPlace(mineName, pl);
        JsonObject cur = book.select(MineBook.geom(pl).key());
        if (MineBook.setup(old)) {
            cur.addProperty("setup", true);
            if (old.has("chests")) cur.add("chests", old.get("chests").deepCopy());
            if (old.has("table")) cur.add("table", old.get("table").deepCopy());
        }
        if (!MineBook.collect(old)) cur.addProperty("collectOres", false);
        JsonElement from = old.get("at");
        if (from != null) cur.add("turnedFrom", from.deepCopy());
        prune();
        saved();
        String said = StripRules.turnedText(t.option().dir(), t.option().pos());
        if (!quiet) commands().whisper(commands().owner(), said + (why != null ? " (" + why + ")" : ""));
        LOG.info("[entropybot] strip mine: {}", said + (why != null ? " (" + why + ")" : ""));
        return new TurnResult(StripTexts.turnOk(said), null);
    }
}
