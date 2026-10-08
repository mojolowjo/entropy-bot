package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.camp.BootstrapPlan;
import io.github.mojolowjo.entropybot.camp.JunkRules;
import io.github.mojolowjo.entropybot.camp.LightGrid;
import io.github.mojolowjo.entropybot.camp.StockRules;
import io.github.mojolowjo.entropybot.camp.ToolCareRules;
import io.github.mojolowjo.entropybot.camp.ArmorCareRules;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.gui.McMenu;
import io.github.mojolowjo.entropybot.storage.StorageRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C4 + C5 (0.20.1, branch comp-c4): the game side of {@code bootstrap}, {@code light}, {@code stock} / {@code restock
 * base} / {@code gather ... to base}, {@code junk} (and the mid-job junk drop) and tool care. The rules are pure in
 * {@code camp.*} (JUnit); this class reads the world and the bag and starts chains or Seq jobs made of steps that
 * already exist (chop, craft, place, dig, gather, deposit, get), so every step reports on its own and a failed one
 * says what is missing.
 *
 * <p>Loader notes (docs/PLANNING.md): block lookups use vanilla {@code ClientLevel.getBlockState / getFluidState}; the
 * junk drop is a THROW click in the player's own inventory menu ({@link McMenu}, vanilla {@code MultiPlayerGameMode});
 * nothing here is NeoForge-specific, so a Fabric port only swaps the event wiring in Commands.
 */
final class CampCommands {
    private static final Logger LOG = LogUtils.getLogger();
    private final Commands c;
    /** Tool care: kind -> id carried at the last look (a kind gone since = broken), and when each kind was last tried. */
    private Map<String, String> toolsBefore = new LinkedHashMap<>();
    private final Map<String, Long> careTried = new LinkedHashMap<>();
    /** Tool care: kinds nearly broken that nothing could replace (for "check"). */
    final Map<String, String> careStuck = new LinkedHashMap<>();
    static final long CARE_RETRY_MS = 10 * 60_000L;

    private String lastRun;
    CampCommands(Commands c) { this.c = c; }

    private JsonObject brain() { return c.brainData(); }

    // ---------------------------------------------------------------- bootstrap

    String bootstrap(LocalPlayer p, String from, boolean internal, String rest) {
        boolean status = rest != null && rest.trim().equalsIgnoreCase("status");
        if (rest != null && !rest.isBlank() && !status) return "usage: bootstrap | bootstrap status";
        // 0.24.3: inside a chain its steps go right after it (the chain "bootstrap then gather ..." stopped silently)
        boolean splice = !status && c.chainsRef().running() && internal;
        if (!status && !splice && c.chainsRef().running()) return "busy: " + c.chainsRef().chainStatus() + " (pm \"stop\" first)";
        if (!status && c.jobs.running() && !c.jobs.walking()) return "busy: " + c.jobs.job.status + " (pm \"stop\" first)";
        Minecraft mc = Minecraft.getInstance();
        ClientLevel lv = mc.level;
        int[] feet = Jobs.here(p);
        String dim = Guard.dimOf(lv);
        // the fence: the camp's digs and placements need leases inside an area (strict mode)
        boolean strict = Core.INSTANCE.guard.core.mode() == GuardCore.Mode.STRICT;
        if (!status && strict && c.areaGap(feet[0], feet[1], feet[2], dim) > 0) {
            return Hints.next("error: this spot is outside my work areas, so I may not dig or place here", "area here 48 camp, then bootstrap");
        }
        boolean tableNear = near(lv, feet, "crafting_table"), furnaceNear = near(lv, feet, "furnace"), chestNear = near(lv, feet, "chest") || near(lv, feet, "barrel");
        int[] dir = quarryDir(lv, feet);
        List<int[]> free = new ArrayList<>(freeRing(lv, feet, dir));
        List<String> pre = new ArrayList<>();
        if (free.size() < 3) {
            // make spots: clear leaves or plants next to me (natural blocks, a dig of one cell and the one above)
            for (int[] cl : io.github.mojolowjo.entropybot.camp.FreeSpots.toClear(feet, x -> clearable(lv, x), 3 - free.size())) {
                pre.add("dig " + cl[0] + " " + cl[1] + " " + cl[2] + " " + cl[0] + " " + (cl[1] + 1) + " " + cl[2]);
                free.add(cl);
            }
        }
        if (free.isEmpty() && !status) {
            int[] spot = flatSpotNear(lv, feet, dir);
            if (spot == null) return Hints.next("error: no free spot for the crafting table within 16 blocks (every cell around me is solid or water)", "goto a flat, open spot, then bootstrap");
            String text = "goto " + Jobs.fmt(spot) + " then bootstrap";
            LOG.info("[entropybot] bootstrap: no room at {}, moving to {}", Jobs.fmt(feet), Jobs.fmt(spot));
            if (splice) {
                String err = c.chainsRef().spliceNext(text);
                return err == null ? "ok: no room for a camp here - walking to " + Jobs.fmt(spot) + " first" : "error: " + err;
            }
            return c.chainsRef().startChain(from, "bootstrap", text, 1);
        }
        int[] table = free.size() > 0 ? free.get(0) : null, furnace = free.size() > 1 ? free.get(1) : null, chest = free.size() > 2 ? free.get(2) : null;
        boolean campMarked = Core.INSTANCE.knowledge.places().containsKey("camp");
        BootstrapPlan.Plan plan = BootstrapPlan.plan(new BootstrapPlan.Facts(Gui.inventory(p), tableNear, furnaceNear, chestNear, campMarked,
                feet, table, furnace, chest, dir));
        if (plan.err() != null) return plan.err();
        if (plan.steps().isEmpty()) return plan.summary();
        List<String> all = new ArrayList<>(pre);
        all.addAll(plan.steps());
        if (status) return "bootstrap here would run " + plan.summary() + ": " + String.join(" > ", all);
        LOG.info("[entropybot] bootstrap at {}: {}", Jobs.fmt(feet), String.join(" > ", all));
        if (splice) {
            String err = c.chainsRef().spliceNext(String.join(" then ", all));
            lastRun = (err == null ? "started (in a chain) " : "refused: ") + plan.summary();
            return err == null ? "ok: bootstrap (" + plan.summary() + "): " + all.size() + " steps next in this chain" : "error: bootstrap: " + err;
        }
        String r = c.chainsRef().startChain(from, "bootstrap", String.join(" then ", all), 1);
        lastRun = (r.startsWith("started") ? "started " : "refused: ") + plan.summary();
        return r.startsWith("started") ? "started: bootstrap (" + plan.summary() + ") - each step reports; a failed one says what is missing" : r;
    }

    /** A block whose id contains the word within BootstrapPlan.NEAR blocks (a few levels up and down). */
    static boolean near(ClientLevel lv, int[] c, String word) {
        int r = BootstrapPlan.NEAR;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) for (int dy = -3; dy <= 3; dy++) for (int dz = -r; dz <= r; dz++) {
            m.set(c[0] + dx, c[1] + dy, c[2] + dz);
            String id = lv.getBlockState(m).getBlock().getDescriptionId();
            if (id.endsWith("." + word) || id.endsWith("_" + word) && !id.contains("ender_chest")) return true;
        }
        return false;
    }

    /** The cell can take a block: air (or grass), no fluid, a solid full block under it. */
    static boolean placeable(ClientLevel lv, BlockPos b) {
        BlockState st = lv.getBlockState(b);
        return st.canBeReplaced() && lv.getFluidState(b).isEmpty() && lv.getBlockState(b.below()).isCollisionShapeFullBlock(lv, b.below())
                && lv.getFluidState(b.below()).isEmpty();
    }

    /** The quarry's direction: the first of east, south, west, north whose 10 columns stand on dry, solid ground. */
    static int[] quarryDir(ClientLevel lv, int[] f) {
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        for (int[] d : dirs) {
            boolean ok = true;
            for (int i = 2; i <= BootstrapPlan.QUARRY_LEN + 1 && ok; i++) {
                BlockPos g = new BlockPos(f[0] + d[0] * i, f[1] - 1, f[2] + d[1] * i);
                BlockState st = lv.getBlockState(g);
                ok = !st.isAir() && lv.getFluidState(g).isEmpty() && lv.getFluidState(g.above()).isEmpty() && !st.hasBlockEntity();
            }
            if (ok) return d;
        }
        return dirs[0];
    }

    /** Free cells 2 away from the bot (feet level), on the side away from the quarry, nearest the bot's back first. */
    static List<int[]> freeRing(ClientLevel lv, int[] f, int[] dir) {
        // 0.24.3: rings 1-3 (a 3x3 pit has only ring 1), air above each, the quarry's side last (FreeSpots)
        return io.github.mojolowjo.entropybot.camp.FreeSpots.find(f, dir, c -> placeable(lv, new BlockPos(c[0], c[1], c[2])),
                c -> lv.getBlockState(new BlockPos(c[0], c[1] + 1, c[2])).canBeReplaced());
    }

    /** 0.24.3: leaves or a plant (not a block entity, not a built block) on solid ground: clearing it makes a free spot. */
    static boolean clearable(ClientLevel lv, int[] c) {
        BlockPos b = new BlockPos(c[0], c[1], c[2]);
        BlockState st = lv.getBlockState(b);
        if (st.isAir() || st.hasBlockEntity() || !lv.getFluidState(b).isEmpty()) return false;
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
        boolean soft = id.endsWith("_leaves") || id.contains("bush") || id.contains("fern") || id.contains("grass") && !id.equals("grass_block")
                || id.contains("flower") || id.contains("sapling") || id.contains("vine") || st.canBeReplaced();
        return soft && lv.getBlockState(b.below()).isCollisionShapeFullBlock(lv, b.below())
                && lv.getBlockState(b.above()).getCollisionShape(lv, b.above()).isEmpty() | lv.getBlockState(b.above()).getBlock().getDescriptionId().endsWith("_leaves");
    }

    /** 0.24.3: the nearest spot within 16 blocks with 3 free cells around it (a walkable cell on solid ground), or null. */
    static int[] flatSpotNear(ClientLevel lv, int[] f, int[] dir) {
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -16; dx <= 16; dx += 2) for (int dz = -16; dz <= 16; dz += 2) for (int dy = -3; dy <= 3; dy++) {
            int[] c = {f[0] + dx, f[1] + dy, f[2] + dz};
            BlockPos b = new BlockPos(c[0], c[1], c[2]);
            if (!placeable(lv, b) || !lv.getBlockState(b.above()).canBeReplaced()) continue;
            double d = dx * dx + dz * dz + dy * dy;
            if (d >= bestD || freeRing(lv, c, dir).size() < 3) continue;
            best = c;
            bestD = d;
        }
        return best;
    }

    // ---------------------------------------------------------------- light

    Chains.Reply light(LocalPlayer p, String rest, String from, boolean internal, String raw, JobRequests.Listener l) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel lv = mc.level;
        int[] me = Jobs.here(p);
        int[] box = LightGrid.parse(rest, new int[]{me[0], me[2]});
        if (box == null) return Chains.Reply.now(LightGrid.USAGE);
        String dim = Guard.dimOf(lv);
        List<int[]> spots = new ArrayList<>();
        int water = 0, air = 0, outside = 0, lit = 0;
        for (int[] s : LightGrid.spots(box[0], box[1], box[2], box[3])) {
            if (c.fenceOn() && !c.inAreas(dim, s[0], s[1])) {
                outside++;
                continue;
            }
            int[] g = ground(lv, s[0], me[1], s[1]);
            if (g == null) {
                air++;
                continue;
            }
            if (g[1] == Integer.MIN_VALUE) {
                water++;
                continue;
            }
            if (lv.getBlockState(new BlockPos(g[0], g[1], g[2])).getBlock().getDescriptionId().contains("torch")) {
                lit++;
                continue;
            }
            spots.add(g);
        }
        List<String> why = new ArrayList<>();
        if (water > 0) why.add(water + " water");
        if (air > 0) why.add(air + " no ground");
        if (outside > 0) why.add(outside + " outside my areas");
        if (lit > 0) why.add(lit + " lit already");
        int skipped = water + air + outside + lit;
        if (spots.isEmpty()) return Chains.Reply.now("ok: " + LightGrid.report(0, skipped, String.join(", ", why)));
        Map<String, Integer> bag = Gui.inventory(p);
        int have = bag.getOrDefault("minecraft:torch", 0);
        // 0.23.1: short of torches (in a chain too: camp here): fetch them, else craft them from coal/charcoal, else from logs
        // (charcoal at a furnace near me, the craft planner smelts it), else light with what I have and say what's missing
        List<Seq.Step> before = new ArrayList<>();
        String supplyNote = null;
        if (have < spots.size()) {
            if (c.jobs.running() && !c.jobs.walking()) return Chains.Reply.now("busy: " + c.jobs.job.status + " (pm \"stop\" first)");
            int stored = 0, fuel = bag.getOrDefault("minecraft:coal", 0) + bag.getOrDefault("minecraft:charcoal", 0), logs = 0;
            for (Map.Entry<String, Integer> e : bag.entrySet()) if (Chopping.isLogItem(e.getKey())) logs += e.getValue();
            List<Crafting.Source> src = c.crafting.storageSources(p);
            for (Crafting.Source s : src) {
                stored += s.items().getOrDefault("minecraft:torch", 0);
                fuel += s.items().getOrDefault("minecraft:coal", 0) + s.items().getOrDefault("minecraft:charcoal", 0);
                for (Map.Entry<String, Integer> e : s.items().entrySet()) if (Chopping.isLogItem(e.getKey())) logs += e.getValue();
            }
            LightGrid.Supply sup = LightGrid.supply(have, spots.size(), stored, fuel, logs, near(lv, me, "furnace"));
            LOG.info("[entropybot] light: {} torches for {} spots: {} ({} stored, {} coal/charcoal, {} logs)", have, spots.size(), sup.source(), stored, fuel, logs);
            switch (sup.source()) {
                case FETCH -> {
                    Map<String, Integer> need = new LinkedHashMap<>();
                    need.put("minecraft:torch", sup.count());
                    before.addAll(Crafting.takeTrips(src, need).steps());
                }
                case CRAFT -> {
                    Crafting.Prepared pr = c.crafting.prepareCraft(p, "minecraft:torch " + sup.count(), null);
                    if (pr.err() != null) {
                        supplyNote = "couldn't make torches (" + pr.err().replaceFirst("^error: ", "") + ")";
                    } else {
                        for (Seq.Step a : pr.steps()) a.direct = false;
                        before.addAll(pr.steps());
                        have += sup.count();
                    }
                }
                case PARTIAL -> supplyNote = sup.text();
                case NONE -> { return Chains.Reply.now(sup.text()); }
                default -> { }
            }
            if (sup.source() == LightGrid.Source.FETCH) have += sup.count();
        }
        if (have < spots.size()) {
            why.add((spots.size() - have) + " no torches left" + (supplyNote != null ? ": " + supplyNote : ""));
            skipped += spots.size() - have;
            spots = spots.subList(0, have);
        }
        if (spots.isEmpty()) return Chains.Reply.now(Hints.next("error: I have no torches" + (supplyNote != null ? " - " + supplyNote : ""), "craft torch 16 (coal or charcoal + sticks), then light " + rest.trim()));
        if (c.jobs.running() && !c.jobs.walking()) return Chains.Reply.now("busy: " + c.jobs.job.status + " (pm \"stop\" first)");
        c.jobs.replaceWalk();
        // nearest first, then each next one nearest the last (a short walk)
        List<int[]> order = new ArrayList<>();
        int[] at = me;
        List<int[]> left = new ArrayList<>(spots);
        while (!left.isEmpty()) {
            int bi = 0;
            for (int i = 1; i < left.size(); i++) if (Jobs.distSq(left.get(i), at) < Jobs.distSq(left.get(bi), at)) bi = i;
            at = left.remove(bi);
            order.add(at);
        }
        List<Seq.Step> steps = new ArrayList<>(before);
        for (int[] s : order) steps.add(Clearing.placeStep(s, "minecraft:torch", null, true));
        Seq.Step note = new Seq.Step("lightnote");
        note.items = new LinkedHashMap<>();
        note.n = skipped;
        note.why = String.join(", ", why);
        note.text = encode(order);
        steps.add(note);
        String r = c.jobs.startSeq(new Seq(c.jobs, c.storage, "lighting " + order.size() + " spots", steps, "fail"), "fail");
        return new Chains.Reply(r, c.jobs.attach("pm", from, raw, r, l));
    }

    /** The cell above the ground at x z near y (the top solid block in y+6 .. y-10 with air above); null: none; y MIN_VALUE: water. */
    static int[] ground(ClientLevel lv, int x, int y, int z) {
        for (int yy = y + 6; yy >= y - 10; yy--) {
            BlockPos b = new BlockPos(x, yy, z);
            if (!lv.getFluidState(b).isEmpty()) return new int[]{x, Integer.MIN_VALUE, z};
            BlockState below = lv.getBlockState(b.below());
            if (lv.getBlockState(b).getBlock().getDescriptionId().contains("torch")) return new int[]{x, yy, z};     // lit already (the caller says so)
            // 0.23.1: the cell above a torch is air over a non-full block: that spot is lit, not "no ground" (live: 22 of 25)
            if (below.getBlock().getDescriptionId().contains("torch")) return new int[]{x, yy - 1, z};
            if (lv.getBlockState(b).canBeReplaced() && !below.isAir()) {
                if (!lv.getFluidState(b.below()).isEmpty()) return new int[]{x, Integer.MIN_VALUE, z};
                if (below.isCollisionShapeFullBlock(lv, b.below())) return new int[]{x, yy, z};
                return null;
            }
        }
        return null;
    }

    static String encode(List<int[]> ps) {
        StringBuilder sb = new StringBuilder();
        for (int[] q : ps) sb.append(q[0]).append(',').append(q[1]).append(',').append(q[2]).append(';');
        return sb.toString();
    }

    /** The light job's last step: counts the torches that stand at the spots now, the end line in the note. */
    static String lightNote(Seq s, Seq.Step st) {
        ClientLevel lv = Minecraft.getInstance().level;
        int placed = 0, missed = 0;
        for (String q : st.text.split(";")) {
            if (q.isEmpty()) continue;
            String[] v = q.split(",");
            BlockPos b = new BlockPos(Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2]));
            if (lv.getBlockState(b).getBlock().getDescriptionId().contains("torch")) placed++;
            else missed++;
        }
        String why = st.why == null ? "" : st.why;
        if (missed > 0) why = (why.isEmpty() ? "" : why + ", ") + missed + " couldn't be placed (nowhere to stand within reach, or the guard refused)";
        s.note = LightGrid.report(placed, (st.n == null ? 0 : st.n) + missed, why);
        return "next";
    }

    // ---------------------------------------------------------------- stock

    Map<String, Integer> stockWant() {
        Map<String, Integer> out = new LinkedHashMap<>();
        JsonObject b = brain();
        if (b.has("stockTargets") && b.get("stockTargets").isJsonObject()) for (Map.Entry<String, JsonElement> e : b.getAsJsonObject("stockTargets").entrySet()) {
            try { out.put(e.getKey(), e.getValue().getAsInt()); } catch (RuntimeException ignored) {}
        }
        return out;
    }

    Map<String, Integer> baseHave(LocalPlayer p) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (StorageRules.Chest ch : c.storage.baseChests(p, true)) ch.items().forEach((k, v) -> out.merge(k, v, Integer::sum));
        return out;
    }

    String stock(LocalPlayer p, String rest) {
        String t = rest == null ? "" : rest.trim();
        List<String> w = Texts.words(t.toLowerCase());
        Map<String, Integer> want = stockWant();
        if (!w.isEmpty() && w.get(0).equals("set")) {
            if (w.size() != 3 || !w.get(2).matches("^\\d{1,5}$")) return StockRules.USAGE;
            String id = c.crafting.planner.resolveItem(w.get(1), Gui.inventory(p));
            if (id == null) id = JunkRules.full(w.get(1));
            if (!Storage.itemExists(id)) return "error: I don't know an item called " + w.get(1) + " - next: find or recipe the exact id";
            if (!want.containsKey(id) && want.size() >= StockRules.MAX_ITEMS) return "error: I keep at most " + StockRules.MAX_ITEMS + " stock targets - stock clear <item> first";
            int n = Integer.parseInt(w.get(2));
            if (n == 0) want.remove(id);
            else want.put(id, n);
        } else if (!w.isEmpty() && w.get(0).equals("clear")) {
            if (w.size() != 2) return StockRules.USAGE;
            if (w.get(1).equals("all")) want.clear();
            else if (want.remove(JunkRules.full(w.get(1))) == null) return "no stock target for " + w.get(1);
        } else if (!w.isEmpty() && !w.get(0).equals("targets")) {
            return StockRules.USAGE;
        }
        if (!w.isEmpty() && !w.get(0).equals("targets")) {
            JsonObject o = new JsonObject();
            want.forEach(o::addProperty);
            brain().add("stockTargets", o);
            c.saved();
        }
        return StockRules.text(want, baseHave(p));
    }

    String restockBase(LocalPlayer p, String from) {
        Map<String, Integer> want = stockWant();
        if (want.isEmpty()) return "error: no stock targets - next: stock set torch 64";
        if (c.storage.baseChests(p, true).isEmpty()) return Hints.next("error: I know no base chests", "scan base");
        Map<String, Integer> sh = StockRules.shortOf(want, baseHave(p));
        if (sh.isEmpty()) return "ok: the base chests hold all of the stock (" + StockRules.text(want, baseHave(p)).replaceFirst("^stock[^:]*: ", "") + ")";
        if (c.chainsRef().running()) return "busy: " + c.chainsRef().chainStatus() + " (pm \"stop\" first)";
        return c.chainsRef().startChain(from, "restock base", StockRules.chain(sh), 1);
    }

    // ---------------------------------------------------------------- junk

    Set<String> junkList() {
        JsonObject b = brain();
        if (!b.has("junk") || !b.get("junk").isJsonArray()) return new LinkedHashSet<>(JunkRules.DEFAULT);
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement e : b.getAsJsonArray("junk")) out.add(e.getAsString());
        return out;
    }

    String junkMode() {
        JsonObject b = brain();
        return b.has("junkMode") ? b.get("junkMode").getAsString() : "drop";
    }

    private void saveJunk(Set<String> s) {
        JsonArray a = new JsonArray();
        s.forEach(a::add);
        brain().add("junk", a);
        c.saved();
    }

    String junk(String rest) {
        List<String> w = Texts.words(rest == null ? "" : rest.toLowerCase());
        if (w.isEmpty() || w.get(0).equals("list")) return JunkRules.listText(junkList(), junkMode());
        String op = w.get(0);
        List<String> ids = w.subList(1, w.size());
        switch (op) {
            case "add" -> {
                if (ids.isEmpty()) return JunkRules.USAGE;
                List<String> refused = new ArrayList<>();
                saveJunk(JunkRules.add(junkList(), ids, refused));
                return (refused.isEmpty() ? "ok: " : "ok (never junk, left out: " + String.join(", ", refused) + "): ") + JunkRules.listText(junkList(), junkMode());
            }
            case "remove" -> {
                if (ids.isEmpty()) return JunkRules.USAGE;
                saveJunk(JunkRules.remove(junkList(), ids));
                return "ok: " + JunkRules.listText(junkList(), junkMode());
            }
            case "default" -> {
                saveJunk(new LinkedHashSet<>(JunkRules.DEFAULT));
                return "ok: " + JunkRules.listText(junkList(), junkMode());
            }
            case "mode" -> {
                if (ids.size() != 1 || !ids.get(0).matches("^(drop|chest)$")) return JunkRules.USAGE;
                brain().addProperty("junkMode", ids.get(0));
                c.saved();
                String warn = ids.get(0).equals("chest") && !Core.INSTANCE.knowledge.places().containsKey("junk")
                        ? " - no chest is marked junk yet: next: mark junk (standing at it), then scan base" : "";
                return "ok: " + JunkRules.listText(junkList(), junkMode()) + warn;
            }
            default -> { return JunkRules.USAGE; }
        }
    }

    /** Every 40 ticks: a job (not a walk) running with the bag nearly full: drop the junk (mode drop). */
    void junkTick(LocalPlayer p) {
        if (!c.jobs.running() || c.jobs.walking() || Core.INSTANCE.reflexes.hold()) return;
        if (!"drop".equals(junkMode()) || Gui.open(p) || Minecraft.getInstance().screen != null) return;
        if (c.freeSlots() > JunkRules.LOW) return;
        // never litter at the owner's feet: with them within 8 blocks the junk waits (the deposit takes it)
        net.minecraft.world.entity.player.Player owner = Jobs.findPlayer(c.owner());
        if (owner != null && owner != p && owner.distanceTo(p) < 8) return;
        Jobs.Job j = c.jobs.job;
        Map<String, Integer> plan = junkPlan(p, j.label + " " + j.status);
        if (plan.isEmpty()) return;
        int n = 0;
        for (Map.Entry<String, Integer> e : plan.entrySet()) {
            String r = io.github.mojolowjo.entropybot.gui.GuiCore.drop(new McMenu(p), e.getKey() + " " + e.getValue());
            if (r.startsWith("ok: dropped")) n += e.getValue();
            else LOG.warn("[entropybot] junk drop {}: {}", e.getKey(), r);
        }
        j.junkDropped += n;
        LOG.info("[entropybot] junk: bag nearly full mid-job, dropped {} ({})", n, plan);
    }


    Map<String, Integer> junkPlan(LocalPlayer p, String collecting) {
        List<StorageRules.Held> held = Storage.held(p);
        Map<String, Integer> dep = StorageRules.depositables(held, null, true, null, c.storage.keeps());
        return JunkRules.plan(junkList(), dep, Gui.inventory(p), collecting);
    }

    /** Junk mode chest: the junk items of a deposit go to the chest marked "junk" (its pos), or null. */
    int[] junkChest() {
        if (!"chest".equals(junkMode())) return null;
        JsonObject pl = Core.INSTANCE.knowledge.places().get("junk");
        return pl == null ? null : Jobs.pos(pl);
    }

    // ---------------------------------------------------------------- tool care

    List<ToolCareRules.Tool> tools(LocalPlayer p) {
        List<ToolCareRules.Tool> out = new ArrayList<>();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && st.isDamageableItem() && st.getMaxDamage() > 0) {
                out.add(new ToolCareRules.Tool(Gui.itemId(st), st.getMaxDamage() - st.getDamageValue(), st.getMaxDamage()));
            }
        }
        return out;
    }

    String toolsText(LocalPlayer p) {
        return p == null ? "" : ToolCareRules.text(tools(p)) + "\n" + ArmorCareRules.text(armor(p))
                + "\n" + ArmorCareRules.orderText(ironFirst());
    }

    /** 0.25.1 gear.ironFirst (a brain setting; defaults until the brain loaded them). */
    static boolean ironFirst() {
        try { return io.github.mojolowjo.entropybot.brain.BrainConfig.current().on("gear.ironFirst"); } catch (RuntimeException e) { return true; }
    }

    /** Every 100 ticks; at a pause (no job, chain or request, no reflex) a worn or broken tool is replaced. */
    void toolCareTick(LocalPlayer p, boolean idle) {
        List<ToolCareRules.Tool> tl = tools(p);
        Map<String, String> needs = ToolCareRules.needs(tl, toolsBefore);
        Map<String, String> now = ToolCareRules.carried(tl);
        // a broken kind stays "before" until replaced (so the next pause still sees it)
        for (Map.Entry<String, String> e : needs.entrySet()) now.putIfAbsent(e.getKey(), e.getValue());
        careStuck.keySet().removeIf(k -> !k.startsWith("armor:") && !needs.containsKey(k));
        if (!idle || needs.isEmpty()) {
            toolsBefore = now;
            return;
        }
        long ms = System.currentTimeMillis();
        for (Map.Entry<String, String> e : needs.entrySet()) {
            String kind = e.getKey();
            if (ms - careTried.getOrDefault(kind, 0L) < CARE_RETRY_MS) continue;
            careTried.put(kind, ms);
            Set<String> stored = new LinkedHashSet<>();
            Map<String, Integer> have = new LinkedHashMap<>(Gui.inventory(p));
            for (Crafting.Source s : c.crafting.storageSources(p)) {
                stored.addAll(s.items().keySet());
                s.items().forEach((k, v) -> have.merge(k, v, Integer::sum));
            }
            String cmd = ToolCareRules.replacement(kind, e.getValue(), stored, have);
            if (cmd == null) {
                careStuck.put(kind, e.getValue());
                LOG.info("[entropybot] tool care: my {} is worn or broken and nothing can replace it", e.getValue());
                toolsBefore = now;
                return;
            }
            careStuck.remove(kind);
            LOG.info("[entropybot] tool care: {} worn or broken -> {}", e.getValue(), cmd);
            Chains.Reply r = c.handle(c.owner(), cmd, false, true, null);
            LOG.info("[entropybot] tool care: {} -> {}", cmd, r.text());
            if (r.text() != null && r.text().startsWith("started")) {
                now.remove(kind);           // the new one shows up when the job ends
                toolsBefore = now;
                return;                     // one at a time
            }
        }
        toolsBefore = now;
    }

    // ---------------------------------------------------------------- armour care (0.23.6)

    /** The four worn slots (empty ones as a Piece with id null). */
    static List<ArmorCareRules.Piece> armor(LocalPlayer p) {
        List<ArmorCareRules.Piece> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String slot = ArmorCareRules.SLOTS.get(3 - i);          // armor list: 0 boots .. 3 helmet
            ItemStack st = p.getInventory().armor.get(i);
            out.add(st.isEmpty() ? new ArmorCareRules.Piece(slot, null, 0, 0)
                    : new ArmorCareRules.Piece(slot, Gui.itemId(st), st.getMaxDamage() - st.getDamageValue(), st.getMaxDamage()));
        }
        return out;
    }

    /** Armour pieces in the bag (not worn). */
    static List<ArmorCareRules.Piece> armorInBag(LocalPlayer p) {
        List<ArmorCareRules.Piece> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            String id = Gui.itemId(st);
            String slot = ArmorCareRules.slot(id);
            if (slot != null) out.add(new ArmorCareRules.Piece(slot, id, st.getMaxDamage() - st.getDamageValue(), st.getMaxDamage()));
        }
        return out;
    }

    private Set<String> armorBefore = new LinkedHashSet<>();

    /**
     * At a pause, after tool care: a worn or missing piece is fetched or crafted and worn ("... then wear"); a good spare
     * in the bag is just put on. One at a time, each slot retried every 10 minutes. True when it started something.
     */
    private long armorBusyLog;

    boolean armorCareTick(LocalPlayer p, String busy) {
        List<ArmorCareRules.Piece> on = armor(p), bag = armorInBag(p);
        Set<String> filled = new LinkedHashSet<>();
        for (ArmorCareRules.Piece a : on) if (!a.empty()) filled.add(a.slot());
        Set<String> before = armorBefore;
        armorBefore = filled;
        if (busy != null) {
            if (ArmorCareRules.lowest(on) != null && System.currentTimeMillis() - armorBusyLog > 60_000) {
                armorBusyLog = System.currentTimeMillis();
                LOG.info("[entropybot] armour care: {} - waiting for a pause (busy: {})", ArmorCareRules.lowest(on), busy);
            }
            return false;
        }
        // a good spare for a worn or empty slot: wear swaps it in
        for (ArmorCareRules.Piece a : on) {
            if ((a.empty() || a.worn()) && ArmorCareRules.spare(a.slot(), bag) != null && Minecraft.getInstance().screen == null) {
                String r = Gui.wearArmor(p);
                LOG.info("[entropybot] armour care: wear -> {}", r);
                if (r.startsWith("ok")) return true;
            }
        }
        boolean ironFirst = ironFirst();
        Map<String, String> needs = ArmorCareRules.ordered(ArmorCareRules.needs(on, bag), ironFirst);      // 0.25.1
        careStuck.keySet().removeIf(k -> k.startsWith("armor:") && !needs.containsKey(k.substring(6)));
        long ms = System.currentTimeMillis();
        Set<String> stored = null;
        Map<String, Integer> have = null;
        for (Map.Entry<String, String> e : needs.entrySet()) {
            String slot = e.getKey();
            if (ms - careTried.getOrDefault("armor:" + slot, 0L) < CARE_RETRY_MS) continue;
            careTried.put("armor:" + slot, ms);
            if (stored == null) {
                stored = new LinkedHashSet<>();
                have = new LinkedHashMap<>(Gui.inventory(p));
                for (Crafting.Source s : c.crafting.storageSources(p)) {
                    stored.addAll(s.items().keySet());
                    final Map<String, Integer> h = have;
                    s.items().forEach((k, v) -> h.merge(k, v, Integer::sum));
                }
            }
            String cmd = ArmorCareRules.replacement(slot, e.getValue(), stored, ArmorCareRules.spendable(slot, on, have, ironFirst));
            if (cmd == null) {
                // a worn piece, or one that broke since the last look: a check line; a slot never filled stays quiet
                if (e.getValue() != null || before.contains(slot)) careStuck.put("armor:" + slot, e.getValue() == null ? slot : e.getValue());
                continue;
            }
            careStuck.remove("armor:" + slot);
            LOG.info("[entropybot] armour care: {} {} -> {}", slot, e.getValue() == null ? "missing" : e.getValue() + " worn", cmd);
            Chains.Reply r = c.handle(c.owner(), cmd, false, true, null);
            LOG.info("[entropybot] armour care: {} -> {}", cmd, r.text());
            if (r.text() == null || !r.text().startsWith("error")) return true;      // one at a time
        }
        return false;
    }

    /** "check": tools that are nearly broken and that nothing can replace. */
    List<String> careFindings() {
        List<String> out = new ArrayList<>();
        careStuck.forEach((k, id) -> out.add(StockRules.shortId(id)));
        return out;
    }
}
