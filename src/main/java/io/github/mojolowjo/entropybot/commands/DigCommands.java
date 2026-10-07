package io.github.mojolowjo.entropybot.commands;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.clear.ClearBox;
import io.github.mojolowjo.entropybot.clear.ClearJob;
import io.github.mojolowjo.entropybot.clear.DigArgs;
import io.github.mojolowjo.entropybot.clear.McClearWorld;
import io.github.mojolowjo.entropybot.clear.PlaceRules;
import io.github.mojolowjo.entropybot.clear.SurfaceDig;
import io.github.mojolowjo.entropybot.craft.CraftPlanner;
import io.github.mojolowjo.entropybot.craft.CraftTexts;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.gui.GuiCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;

/**
 * B7d D1: the verbs on top of the clear engine, ported from the bridge with its wording: {@code dig x1 y1 z1 x2 y2 z2
 * [ores] [force]}, {@code build clear|floor|walls|fill|shell <block>} (inside the work zone), {@code place <block> x y
 * z} and {@code zone corner1|corner2|pos1|pos2 [x y z] | clear}. Each returns the reply the dispatcher hands back; a
 * started job runs in the mod's job slot (a Seq) and reports its end the bridge's way.
 *
 * <p>The work zone lives in commands.json ("zone", the bridge's memory.zone, moved over once).
 */
final class DigCommands {
    private static final Logger LOG = LogUtils.getLogger();

    private DigCommands() {}

    static final String NO_ZONE = "error: no work zone - PM \"zone corner1\" and \"zone corner2\" standing on opposite corners";
    static final String DIG_USAGE = DigArgs.USAGE;
    static final String BUILD_USAGE = "error: usage build <floor|walls|fill|shell|clear> [block]";
    static final String PLACE_USAGE = "error: usage place <block item> x y z";

    // ---- the work zone ----

    /** commands.json "zone" ({dim, x1, y1, z1, x2, y2, z2}, either corner may be missing); the bridge's, moved over once. */
    static JsonObject zone(Commands c) {
        JsonObject b = c.brainData();
        if (!b.has("zoneMoved")) {
            b.addProperty("zoneMoved", true);
            JsonObject z = bridgeZone();
            if (z != null && !b.has("zone")) b.add("zone", z);
            c.saved();
        }
        return b.has("zone") && b.get("zone").isJsonObject() ? b.getAsJsonObject("zone") : null;
    }

    /** memory.json's "zone" (kubejs/bridge/memory.json), or null. */
    private static JsonObject bridgeZone() {
        try {
            Path f = Minecraft.getInstance().gameDirectory.toPath().resolve(Commands.BRIDGE_DIR).resolve("memory.json");
            if (!Files.exists(f)) return null;
            JsonObject m = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            return m.has("zone") && m.get("zone").isJsonObject() ? m.getAsJsonObject("zone").deepCopy() : null;
        } catch (Exception e) {
            LOG.info("[entropybot] zone: memory.json unreadable ({})", e.toString());
            return null;
        }
    }

    static boolean complete(JsonObject z) {
        return z != null && z.has("x1") && z.has("x2");
    }

    static String zoneText(JsonObject z) {
        if (!complete(z)) return z != null && (z.has("x1") || z.has("x2")) ? "zone half set - set the other corner" : "no work zone set";
        int x1 = z.get("x1").getAsInt(), y1 = z.get("y1").getAsInt(), z1 = z.get("z1").getAsInt();
        int x2 = z.get("x2").getAsInt(), y2 = z.get("y2").getAsInt(), z2 = z.get("z2").getAsInt();
        return "zone " + x1 + " " + y1 + " " + z1 + " to " + x2 + " " + y2 + " " + z2 + " (" + (Math.abs(x2 - x1) + 1) + "x" + (Math.abs(y2 - y1) + 1) + "x" + (Math.abs(z2 - z1) + 1) + ")";
    }

    static ClearBox zoneBox(Commands c) {
        JsonObject z = zone(c);
        if (!complete(z)) return null;
        return ClearBox.of(z.get("x1").getAsInt(), z.get("y1").getAsInt(), z.get("z1").getAsInt(), z.get("x2").getAsInt(), z.get("y2").getAsInt(), z.get("z2").getAsInt());
    }

    static String zoneDim(JsonObject z) {
        return z != null && z.has("dim") && !z.get("dim").isJsonNull() ? z.get("dim").getAsString() : "minecraft:overworld";
    }

    /** "zone corner1|corner2|pos1|pos2 [x y z]" (the sender's spot when visible, else the bot's), "zone clear", "zone". */
    static String zone(Commands c, LocalPlayer p, String rest, String from) {
        String[] parts = rest == null ? new String[0] : rest.trim().split("\\s+");
        String n = parts.length > 0 ? parts[0].toLowerCase() : "";
        if (n.equals("corner1") || n.equals("corner2") || n.equals("pos1") || n.equals("pos2")) {
            String arg = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length));
            PolicyCommands.Pos pos = c.resolvePos(Minecraft.getInstance(), arg, from);
            if (pos == null) return "I can't see you - come closer or give coordinates - next: zone " + n + " x y z";
            JsonObject z = zone(c);
            if (z == null || !zoneDim(z).equals(pos.dim())) {
                z = new JsonObject();
                z.addProperty("dim", pos.dim());
            }
            String k = n.substring(n.length() - 1);
            z.addProperty("x" + k, pos.x());
            z.addProperty("y" + k, pos.y());
            z.addProperty("z" + k, pos.z());
            c.brainData().add("zone", z);
            c.saved();
            return "corner " + k + " = " + pos.x() + " " + pos.y() + " " + pos.z() + " - " + zoneText(z);
        }
        if (n.equals("clear")) {
            zone(c);
            c.brainData().remove("zone");
            c.saved();
            return "work zone removed";
        }
        return zoneText(zone(c));
    }

    // ---- dig ----

    /**
     * "dig x1 y1 z1 x2 y2 z2 [force] [ores] [floor [block]] [junk drop]": any box, the careful way (ores listed; "ores"
     * mines them; "force" building blocks too; B7e F: "floor" fills the layer under it afterwards, "junk drop" throws
     * plain junk away when the bag is full instead of a base trip).
     */
    static String dig(Commands c, LocalPlayer p, String rest, String from) {
        int[] feet = {(int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())};
        String areaName = ConfirmGate.digArea(Texts.words(rest == null ? "" : rest.trim().toLowerCase()));
        if (areaName != null) return digArea(c, p, areaName, rest, from);
        DigArgs a = DigArgs.parse(rest, feet);
        if (a == null) return DIG_USAGE;
        if (a.surfaceForm()) return digSurface(c, p, a, rest, from);
        int[] n = a.n();
        boolean force = a.force(), ores = a.ores();
        String floorId = null;
        if (a.floor() && a.floorBlock() != null) {
            floorId = c.crafting.planner.resolveItem(a.floorBlock(), Gui.inventory(p));
            if (floorId == null) floorId = GuiCore.normId(a.floorBlock());
            String bad = FloorSteps.floorBlockProblem(floorId);
            if (bad != null) return "error: I won't make a floor of " + GuiCore.shortId(floorId) + " - " + bad;
        }
        ClearBox box = ClearBox.of(n[0], n[1], n[2], n[3], n[4], n[5]);
        if (box.volume() > 20000) return "error: that box is too big (20000 blocks max)";
        if (force && box.volume() > 64) return "error: dig ... force is for small boxes (64 blocks max)";
        // force is the owner's (guests never get dig at all; this is the second lock)
        if (force && (from == null || !from.equalsIgnoreCase(c.owner()))) return "only " + c.owner() + " can dig ... force";
        // a dig box has to lie inside an area: in strict mode the lease says so, in log mode this gate does
        if (Core.INSTANCE.guard.core.mode() != io.github.mojolowjo.entropybot.guard.GuardCore.Mode.STRICT && !Clearing.boxInAreas(box)) {
            List<String> names = new ArrayList<>();
            for (var ar : Core.INSTANCE.guard.core.policy().areas) names.add(ar.name == null ? "?" : ar.name);
            return "error: that box is not inside one of my areas (" + (names.isEmpty() ? "none set" : String.join(", ", names)) + ") - " + PolicyCommands.AREA_HINT;
        }
        String label = "digging " + n[0] + " " + n[1] + " " + n[2] + " to " + n[3] + " " + n[4] + " " + n[5] + (force ? " (force)" : "") + (ores ? " (ores too)" : "")
                + (a.floor() ? " (floor" + (floorId != null ? " of " + GuiCore.shortId(floorId) : "") + ")" : "") + (a.junkDrop() ? " (junk drop)" : "")
                + (a.water() ? " (water" + (a.large() ? ", large" : "") + ")" : "");
        ClearJob.Options o = new ClearJob.Options().box(box).force(force).collect(ores).label(label).junkDrop(a.junkDrop())
                .liquidBlocks(true)           // water plan item 1: water or lava in the way ends it as "blocked by ..."
                .water(a.water(), a.large()).line("dig " + rest.trim());
        // B7e F: a floor dig stays on the walkway (it never stands in the cave it bridges; the fill's next round digs on)
        if (a.floor()) o.floor(true, floorId).minStandY(box.y1());
        return startClear(c, p, o, restockSteps(c, p, box.volume()));
    }

    /**
     * V1a (0.22.0): the box of an area for dig/build: {x1, y1, z1, x2, y2, z2}, or an "error: ..." in err[0]. The area
     * needs its own y range (question 3: no accidental dig to bedrock).
     */
    static int[] areaBox(Commands c, String name, String[] err) {
        JsonObject b = c.policyArea(name);
        if (b == null) { err[0] = "error: I have no area called " + name + " (area list)"; return null; }
        if (b.has("round")) { err[0] = "error: " + name + " is a circle - make a box area for this"; return null; }
        if (!PolicyCommands.hasY(b)) {
            err[0] = "error: " + name + " covers every height - give it heights: area " + PolicyCommands.n(b, "x1") + " " + PolicyCommands.n(b, "z1") + " "
                    + PolicyCommands.n(b, "x2") + " " + PolicyCommands.n(b, "z2") + " " + name + " " + PolicyCommands.typeOf(b).word() + " <y1> <y2>";
            return null;
        }
        if (!PolicyCommands.dimOf(b).equals(Storage.dim())) { err[0] = "error: " + name + " is in " + PolicyCommands.dimOf(b); return null; }
        return new int[]{PolicyCommands.n(b, "x1"), PolicyCommands.n(b, "y1"), PolicyCommands.n(b, "z1"), PolicyCommands.n(b, "x2"), PolicyCommands.n(b, "y2"), PolicyCommands.n(b, "z2")};
    }

    /**
     * V1a: "dig &lt;area&gt; [ores] [junk drop] [water [large]]": the whole box of a named area, the careful way. In a destroy
     * area and from the owner, built blocks go too (a destroy lease; block entities never). A safe area refuses.
     */
    static String digArea(Commands c, LocalPlayer p, String name, String rest, String from) {
        String[] err = new String[1];
        int[] n = areaBox(c, name, err);
        if (n == null) return err[0];
        JsonObject b = c.policyArea(name);
        io.github.mojolowjo.entropybot.guard.AreaType t = PolicyCommands.typeOf(b);
        if (t == io.github.mojolowjo.entropybot.guard.AreaType.SAFE) return "error: " + name + " is a safe area - I never dig there";
        List<String> words = Texts.words(rest.trim().toLowerCase());
        boolean ores = words.contains("ores"), junk = words.contains("junk") && words.contains("drop"), water = words.contains("water") || words.contains("large"), large = words.contains("large");
        for (String w : words.subList(1, words.size())) {
            if (!List.of("ores", "junk", "drop", "water", "large").contains(w)) return "usage: dig <area> [ores] [junk drop] [water [large]]";
        }
        ClearBox box = ClearBox.of(n[0], n[1], n[2], n[3], n[4], n[5]);
        if (box.volume() > 20000) return "error: " + name + " is too big to dig in one go (" + box.volume() + " blocks, 20000 max)";
        boolean owner = from == null || from.equalsIgnoreCase(c.owner());
        boolean destroy = io.github.mojolowjo.entropybot.guard.AreaTypeRules.breakBuilt(t, owner);
        String label = "digging " + name + " (" + t.word() + ", " + box.volume() + " blocks)" + (destroy ? " (built blocks too)" : "") + (ores ? " (ores too)" : "");
        ClearJob.Options o = new ClearJob.Options().box(box).collect(ores).label(label).junkDrop(junk).liquidBlocks(true).water(water, large).line("dig " + rest.trim());
        if (destroy) o.destroyArea(name);
        LOG.info("[entropybot] dig {}: {} area, destroy lease {}", name, t.word(), destroy);
        return startClear(c, p, o, restockSteps(c, p, box.volume()));
    }

    /**
     * 0.19.5: "dig x1 z1 x2 z2 down|up N [words]": the per-column list from the surface ({@link SurfaceDig}), built once
     * from the client level, then the same careful clear as a box (Options.only: reach, sight, never containers or
     * protected blocks, never next to water or lava, the ores rule, the areas and leases, base trips).
     */
    static String digSurface(Commands c, LocalPlayer p, DigArgs a, String rest, String from) {
        if (a.floor()) return "error: floor works with a box dig only (dig x1 y1 z1 x2 y2 z2 floor)";
        boolean force = a.force(), ores = a.ores(), up = a.surface().equals("up");
        int[] n = a.n();
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return "error: not in a world";
        net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
        int max = level.getMaxBuildHeight() - 1;
        SurfaceDig.Source src = new SurfaceDig.Source() {
            @Override public int kind(int x, int y, int z) { return io.github.mojolowjo.entropybot.surface.SurfaceExport.kindOf(level, m.set(x, y, z)); }
            @Override public boolean liquid(int x, int y, int z) { return level.getBlockState(m.set(x, y, z)).getBlock() instanceof net.minecraft.world.level.block.LiquidBlock; }
            @Override public int top(int x, int z) { return Math.min(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z), max); }
        };
        SurfaceDig.Plan plan;
        try {
            plan = SurfaceDig.build(src, n[0], n[1], n[2], n[3], up, a.depth(), level.getMinBuildHeight(), max);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] dig surface: {}", e.toString());
            return "error: couldn't read the columns: " + e;
        }
        if (plan.error() != null) return plan.error();
        if (plan.blocks().isEmpty()) return "ok: nothing to dig there (" + plan.columns() + " columns" + (plan.noSurface() > 0 ? ", " + plan.noSurface() + " not loaded or empty" : "") + ")";
        ClearBox box = SurfaceDig.bounds(plan.blocks());
        if (force && plan.blocks().size() > 64) return "error: dig ... force is for small digs (64 blocks max)";
        if (force && (from == null || !from.equalsIgnoreCase(c.owner()))) return "only " + c.owner() + " can dig ... force";
        if (Core.INSTANCE.guard.core.mode() != io.github.mojolowjo.entropybot.guard.GuardCore.Mode.STRICT && !Clearing.boxInAreas(box)) {
            List<String> names = new ArrayList<>();
            for (var ar : Core.INSTANCE.guard.core.policy().areas) names.add(ar.name == null ? "?" : ar.name);
            return "error: that rectangle is not inside one of my areas (" + (names.isEmpty() ? "none set" : String.join(", ", names)) + ") - " + PolicyCommands.AREA_HINT;
        }
        String label = "digging " + n[0] + " " + n[1] + " to " + n[2] + " " + n[3] + ", " + plan.blocks().size() + " blocks"
                + " (from the surface, " + a.surface() + " " + a.depth() + ", " + plan.columns() + " columns)"
                + (force ? " (force)" : "") + (ores ? " (ores too)" : "") + (a.junkDrop() ? " (junk drop)" : "")
                + (a.water() ? " (water" + (a.large() ? ", large" : "") + ")" : "");
        ClearJob.Options o = new ClearJob.Options().only(plan.blocks()).force(force).collect(ores).label(label).junkDrop(a.junkDrop())
                .liquidBlocks(true).water(a.water(), a.large()).line("dig " + rest.trim());
        return startClear(c, p, o, restockSteps(c, p, plan.blocks().size()));
    }

    /**
     * 18c: before a dig of more than {@link PlaceRules#BIG_DIG} blocks, stone pickaxes up to 3 (or what "supplies" says):
     * from storage first, the rest crafted. Empty when it has enough. A trip that fails is reported and the dig goes on.
     */
    static List<Seq.Step> restockSteps(Commands c, LocalPlayer p, long volume) {
        Map<String, Integer> inv = Gui.inventory(p);
        String pick = "minecraft:stone_pickaxe";
        int want = PlaceRules.picksToRestock(volume, inv.getOrDefault(pick, 0), c.suppliesMap().get(pick));
        if (want <= 0) return List.of();
        Crafting crafting = c.crafting;
        List<Crafting.Source> src = crafting.sources(p);
        CraftTexts.Restock r = CraftTexts.restock(Map.of(pick, want), inv, Crafting.totals(src));
        if (r.reply() != null) return List.of();
        List<Seq.Step> steps = new ArrayList<>(Crafting.takeTrips(src, new java.util.LinkedHashMap<>(r.take())).steps());
        if (!r.craftText().isEmpty()) {
            Seq.Step craft = new Seq.Step("craftitem");
            craft.text = r.craftText();
            craft.optional = true;
            steps.add(craft);
        }
        if (!steps.isEmpty()) LOG.info("[entropybot] dig: restocking stone pickaxes to {} first ({} steps)", want, steps.size());
        return steps;
    }

    /** startClear for a verb: the leases now (a refusal is the reply), the job in the mod's slot, "started: ...". */
    static String startClear(Commands c, LocalPlayer p, ClearJob.Options o, List<Seq.Step> before) {
        // B7e F: a floor dig's clear hands its report to the fill after it, which ends the job
        Seq.Step st = o.floor ? Clearing.clearStep(o) : Clearing.finishingClearStep(o);
        String err = Clearing.prepare(st, p, c);
        if (err != null) return err;
        Clearing.ClearState s = (Clearing.ClearState) st.state;
        List<Seq.Step> steps = new ArrayList<>();
        Seq seq = new Seq(c.jobs, c.storage, s.job.label, List.of(), "always");
        // first back up onto the walkway when it stands in the cave (a restock trip or the clear can't start from there)
        if (o.floor) steps.add(FloorSteps.floorStep(o, null, true));
        if (before != null && !before.isEmpty()) {
            List<Seq.Step> pre = new ArrayList<>(before);
            // the pickaxe craft wants a table: put one down here when the nearest is far (18a)
            Seq.Step last = pre.get(pre.size() - 1);
            if (last.type.equals("craftitem")) {
                List<Seq.Step> t = Clearing.tableTrip(seq, s, p, last.text);
                if (t != null) {
                    pre.remove(pre.size() - 1);
                    pre.addAll(t);
                }
            }
            steps.addAll(pre);
            s.inTrip = true;
            s.tripKind = "restock";
        }
        steps.add(st);
        if (o.floor) steps.add(FloorSteps.floorStep(o, st, false));
        seq.splice(0, steps);
        c.jobs.startSeq(seq, "always");
        try {                                       // P1: someone's build next to the dig? (a whisper, never a box)
            io.github.mojolowjo.entropybot.clear.ClearBox bb = s.job.breakLeaseBox();
            RestoreLive.INSTANCE.scanBuilds(p, new int[]{(bb.x1() + bb.x2()) / 2, (bb.y1() + bb.y2()) / 2, (bb.z1() + bb.z2()) / 2});
        } catch (RuntimeException ignored) {}
        c.jobs.job.holdOnFight = true;             // the bridge never ended a clear for a fight: a reflex holds it
        seq.setStatus(s.job.status("starting"));
        JsonObject z = o.box == null && o.only == null ? zone(c) : null;
        return s.job.startedMessage(zoneText(z));
    }

    // ---- build ----

    /** "build <floor|walls|fill|shell|clear> [block]": only ever inside the zone; placing jobs never break. */
    static String build(Commands c, LocalPlayer p, String rest, String from) {
        String[] parts = rest == null ? new String[0] : rest.trim().split("\\s+");
        String shape = parts.length > 0 ? parts[0].toLowerCase() : "";
        Map<String, String> ops = Map.of("floor", "fill", "fill", "fill", "walls", "walls", "shell", "shell", "clear", "cleararea");
        if (!ops.containsKey(shape)) return BUILD_USAGE;
        // V1a: the zone is an area now, named last: "build floor cobblestone <area>", "build clear <area>"
        int need = shape.equals("clear") ? 2 : 3;
        if (parts.length != need) return shape.equals("clear") ? "usage: build clear <area>" : "usage: build " + shape + " <block> <area>  (e.g. build " + shape + " cobblestone yard)";
        String areaName = parts[need - 1].toLowerCase();
        String[] err = new String[1];
        int[] an = areaBox(c, areaName, err);
        if (an == null) return err[0];
        if (PolicyCommands.typeOf(c.policyArea(areaName)) == io.github.mojolowjo.entropybot.guard.AreaType.SAFE) return "error: " + areaName + " is a safe area - I never build or dig there";
        JsonObject z = new JsonObject();
        z.addProperty("dim", Storage.dim());
        z.addProperty("x1", an[0]); z.addProperty("y1", an[1]); z.addProperty("z1", an[2]);
        z.addProperty("x2", an[3]); z.addProperty("y2", an[4]); z.addProperty("z2", an[5]);
        if (shape.equals("clear")) {
            ClearBox cb = ClearBox.of(an[0], an[1], an[2], an[3], an[4], an[5]);
            if (cb.volume() > 20000) return "error: " + areaName + " is too big to clear in one go (20000 blocks max)";
            return startClear(c, p, new ClearJob.Options().box(cb).label("clearing " + areaName), List.of());
        }
        Map<String, Integer> counts = Gui.inventory(p);
        String id = c.crafting.planner.resolveItem(parts[1], counts);
        if (id == null) return "error: I don't know a block called " + parts[1];
        if (counts.getOrDefault(id, 0) <= 0) return "error: I have no " + CraftPlanner.shortId(id) + " - next: get " + CraftPlanner.shortId(id) + " 64 (or give me some)";
        IBaritone b = Jobs.baritone();
        if (b == null) return "error: baritone not loaded";
        ClearBox box = ClearBox.of(an[0], an[1], an[2], an[3], an[4], an[5]);
        int ylo = box.y1();
        // the guard: Baritone's placements need a place lease for the zone (the bridge took none; strict mode refused them)
        Clearing.BuildState bs = new Clearing.BuildState();
        String le = bs.leases.take("building " + shape + " of " + CraftPlanner.shortId(id), box, true, false);
        if (le != null) return le;
        Settings s = BaritoneAPI.getSettings();
        s.allowPlace.value = true;
        s.allowBreak.value = false;
        s.buildIgnoreExisting.value = true;
        s.allowInventory.value = true;
        var cm = b.getCommandManager();
        int x1 = z.get("x1").getAsInt(), y1 = z.get("y1").getAsInt(), z1 = z.get("z1").getAsInt();
        int x2 = z.get("x2").getAsInt(), y2 = z.get("y2").getAsInt(), z2 = z.get("z2").getAsInt();
        cm.execute("sel clear");
        cm.execute("sel pos1 " + x1 + " " + (shape.equals("floor") ? ylo : y1) + " " + z1);
        cm.execute("sel pos2 " + x2 + " " + (shape.equals("floor") ? ylo : y2) + " " + z2);
        cm.execute("sel " + ops.get(shape) + " " + id);
        bs.status = "building " + shape + " of " + CraftPlanner.shortId(id);
        bs.start = Core.INSTANCE.tick();
        Seq.Step st = Clearing.clearStep(new ClearJob.Options().box(box).label(bs.status));
        st.kind = Clearing.KIND_BUILD;
        st.state = bs;
        Clearing.placingOwned = true;
        c.jobs.startSeq(new Seq(c.jobs, c.storage, bs.status, List.of(st), "fail"), "fail");
        c.jobs.job.holdOnFight = true;
        return "started: " + bs.status + " (area " + areaName + ")";
    }

    // ---- place ----

    /** "place <block> x y z": at once when it is in reach (as the bridge), else a job that walks into reach first. */
    static String place(Commands c, LocalPlayer p, String rest, String from) {
        String[] pp = rest == null ? new String[0] : rest.trim().split("\\s+");
        if (pp.length != 4) return PLACE_USAGE;
        int x, y, z;
        try {
            x = Integer.parseInt(pp[1]);
            y = Integer.parseInt(pp[2]);
            z = Integer.parseInt(pp[3]);
        } catch (NumberFormatException e) {
            return PLACE_USAGE;
        }
        String id = GuiCore.normId(pp[0]);
        Clearing.LiveWorld w = new Clearing.LiveWorld();
        w.set(p);
        if (PlaceRules.placeSide(w, x, y, z, p.getX(), p.getEyeY(), p.getZ()) != null || !PlaceRules.free(w, x, y, z)) {
            io.github.mojolowjo.entropybot.clear.LeaseSet leases = Clearing.newLeases();
            try {
                return Clearing.placeAt(p, id, x, y, z, leases);
            } finally {
                leases.releaseAll();          // the click is checked at once: the lease isn't needed after it
            }
        }
        if (Gui.inventory(p).getOrDefault(id, 0) <= 0) return "error: I have no " + GuiCore.shortId(id);
        String label = "placing " + GuiCore.shortId(id) + " at " + x + " " + y + " " + z;
        return c.jobs.startSeq(new Seq(c.jobs, c.storage, label, List.of(Clearing.placeStep(new int[]{x, y, z}, id)), "fail"), "fail");
    }
}
