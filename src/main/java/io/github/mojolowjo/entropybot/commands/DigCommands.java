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
import io.github.mojolowjo.entropybot.clear.McClearWorld;
import io.github.mojolowjo.entropybot.clear.PlaceRules;
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
    static final String DIG_USAGE = "error: usage dig x1 y1 z1 x2 y2 z2 [force] [ores]";
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
            if (pos == null) return "I can't see you - come closer or give coordinates";
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

    /** "dig x1 y1 z1 x2 y2 z2 [force] [ores]": any box, the careful way (ores listed; "ores" mines them; "force" building blocks too). */
    static String dig(Commands c, LocalPlayer p, String rest, String from) {
        List<String> w = new ArrayList<>(List.of(rest == null ? new String[0] : rest.trim().split("\\s+")));
        boolean force = false, ores = false;
        while (w.size() > 6 && w.get(w.size() - 1).matches("(?i)^(force|ores)$")) {
            if (w.remove(w.size() - 1).equalsIgnoreCase("force")) force = true;
            else ores = true;
        }
        if (w.size() != 6) return DIG_USAGE;
        int[] n = new int[6];
        try {
            for (int i = 0; i < 6; i++) n[i] = Integer.parseInt(w.get(i));
        } catch (NumberFormatException e) {
            return DIG_USAGE;
        }
        ClearBox box = ClearBox.of(n[0], n[1], n[2], n[3], n[4], n[5]);
        if (box.volume() > 20000) return "error: that box is too big (20000 blocks max)";
        if (force && box.volume() > 64) return "error: dig ... force is for small boxes (64 blocks max)";
        // force is the owner's (guests never get dig at all; this is the second lock)
        if (force && (from == null || !from.equalsIgnoreCase(c.owner()))) return "only " + c.owner() + " can dig ... force";
        // a dig box has to lie inside an area: in strict mode the lease says so, in log mode this gate does
        if (Core.INSTANCE.guard.core.mode() != io.github.mojolowjo.entropybot.guard.GuardCore.Mode.STRICT && !Clearing.boxInAreas(box)) {
            List<String> names = new ArrayList<>();
            for (var a : Core.INSTANCE.guard.core.policy().areas) names.add(a.name == null ? "?" : a.name);
            return "error: that box is not inside one of my areas (" + (names.isEmpty() ? "none set" : String.join(", ", names)) + ") - " + PolicyCommands.AREA_HINT;
        }
        String label = "digging " + n[0] + " " + n[1] + " " + n[2] + " to " + n[3] + " " + n[4] + " " + n[5] + (force ? " (force)" : "") + (ores ? " (ores too)" : "");
        ClearJob.Options o = new ClearJob.Options().box(box).force(force).collect(ores).label(label);
        return startClear(c, p, o, restockSteps(c, p, box.volume()));
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
        Seq.Step st = Clearing.finishingClearStep(o);
        String err = Clearing.prepare(st, p, c);
        if (err != null) return err;
        Clearing.ClearState s = (Clearing.ClearState) st.state;
        List<Seq.Step> steps = new ArrayList<>();
        Seq seq = new Seq(c.jobs, c.storage, s.job.label, List.of(), "always");
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
        seq.splice(0, steps);
        c.jobs.startSeq(seq, "always");
        c.jobs.job.holdOnFight = true;              // the bridge never ended a clear for a fight: a reflex holds it
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
        JsonObject z = zone(c);
        if (!complete(z)) return NO_ZONE;
        if (!zoneDim(z).equals(Storage.dim())) return "error: the zone is in " + zoneDim(z);
        if (shape.equals("clear")) return startClear(c, p, new ClearJob.Options(), List.of());
        if (parts.length < 2 || parts[1].isEmpty()) return "error: which block? e.g. build " + shape + " cobblestone";
        Map<String, Integer> counts = Gui.inventory(p);
        String id = c.crafting.planner.resolveItem(parts[1], counts);
        if (id == null) return "error: I don't know a block called " + parts[1];
        if (counts.getOrDefault(id, 0) <= 0) return "error: I have no " + CraftPlanner.shortId(id) + " - give me some first";
        IBaritone b = Jobs.baritone();
        if (b == null) return "error: baritone not loaded";
        ClearBox box = zoneBox(c);
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
        return "started: " + bs.status + " (" + zoneText(z) + ")";
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
