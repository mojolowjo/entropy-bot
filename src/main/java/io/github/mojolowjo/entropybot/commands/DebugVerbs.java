package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.recorder.Recorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * B7e (E1): the {@code debug} verbs (docs/B7E_CONTRACT.md), the read-only replacement of the bridge's {@code eval}:
 * owner only, from the fast channel and cmd.json only (bridge.ps1 {@code do debug ...}, the dashboard), refused from
 * a PM. The rules and texts are in {@link DebugRules}; the recorder is read through {@link Recorder} only and every
 * verb answers sensibly with {@code Recorder.NONE}.
 */
public final class DebugVerbs {
    private DebugVerbs() {}

    /** The verb: args = what follows "debug". Runs on the game thread. Never throws. */
    public static String handle(Core core, String args, DebugRules.Source source, boolean isOwner, String owner) {
        String no = DebugRules.gate(source, isOwner, owner);
        if (no != null) return no;
        List<String> w = DebugRules.words(args);
        if (w.isEmpty() || w.get(0).equalsIgnoreCase("help") || w.get(0).equalsIgnoreCase("list")) return DebugRules.LIST;
        try {
            return run(core, w);
        } catch (IllegalArgumentException e) {
            return e.getMessage().startsWith("usage") ? e.getMessage() : "error: " + e.getMessage();
        } catch (RuntimeException e) {
            return "error: " + e;
        }
    }

    private static String run(Core core, List<String> w) {
        String verb = w.get(0).toLowerCase();
        Recorder rec = core.recorder;
        long now = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();
        // the recorder's own verbs work without a world (its data is on disk)
        switch (verb) {
            case "trail" -> { return trail(rec, w, now, zone); }
            case "incident", "incidents" -> { return incident(rec, w, now, zone); }
            case "events" -> {
                int n = DebugRules.optInt(w, 1, DebugRules.EVENTS_DEFAULT, 1, DebugRules.EVENTS_MAX, "usage: debug events [n]");
                return core.events.since(Math.max(0, core.events.lastSeq() - n), n);
            }
            case "baritone" -> { return baritone(); }
            case "hits", "hit", "damage" -> {      // 0.24.4 damage log
                int n = DebugRules.optInt(w, 1, 10, 1, io.github.mojolowjo.entropybot.threat.HitLog.CAP, "usage: debug hits [n]");
                return "last hits (oldest first): " + io.github.mojolowjo.entropybot.threat.HitLog.text(io.github.mojolowjo.entropybot.threat.HitLog.INSTANCE.last(n), zone)
                        + "\nmeasured per hit: " + io.github.mojolowjo.entropybot.threat.MobDamage.INSTANCE.toJson();
            }
            case "threats", "threat" -> {
                if (w.size() >= 4) { int[] c = DebugRules.ints(w, 1, 3, "usage: debug threats [x y z]"); return io.github.mojolowjo.entropybot.threat.ThreatRuntime.INSTANCE.probe(c[0], c[1], c[2]); }
                return io.github.mojolowjo.entropybot.threat.ThreatRuntime.INSTANCE.debug();
            }    // B2
            default -> {}
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) return "error: not in a world";
        switch (verb) {
            case "gui" -> { return io.github.mojolowjo.entropybot.gui.Gui.describe(p); }
            case "inv", "inventory" -> { return inv(p); }
            case "block" -> { return block(level, DebugRules.ints(w, 1, 3, "usage: debug block x y z")); }
            case "blocks" -> { return blocks(level, rec, w, now, zone); }
            case "guard" -> {
                int[] c = DebugRules.ints(w, 1, 3, "usage: debug guard x y z");
                String dim = Guard.dimOf(level);
                List<String> out = new ArrayList<>();
                for (String a : new String[]{"break", "place", "go"}) out.add(a + ": " + io.github.mojolowjo.entropybot.api.BotAPI.check(dim, c[0], c[1], c[2], a));
                String liquid = Guard.liquidNextTo(level, c[0], c[1], c[2]);
                return "guard at " + c[0] + " " + c[1] + " " + c[2] + " (" + dim + ", " + core.guard.core.mode().name().toLowerCase() + "): "
                        + String.join(" | ", out) + (liquid != null ? " | next to " + liquid : "");
            }
            case "changes" -> { return changes(level, rec, w, now, zone); }
            case "mobs" -> { return mobs(level, p, w); }
            default -> { return "unknown debug verb \"" + verb + "\". " + DebugRules.LIST; }
        }
    }

    // ---- the game ----

    /**
     * {@code debug mobs [radius]}: every living entity within the radius (default 24, at most 64), nearest first (20 rows):
     * registry id, java class and its superclasses up to Mob, health, distance, line of sight, whether it is an
     * {@code Enemy} / {@code NeutralMob}, and what the fight code (Reflexes.nearestThreat) makes of it right now, with the
     * reason when it does not count. Read-only; it answers "why does the bot not fight that mob".
     */
    static String mobs(ClientLevel level, LocalPlayer p, List<String> w) {
        int radius = DebugRules.optInt(w, 1, 24, 1, 64, "usage: debug mobs [radius]");
        record Row(double d, String text) {}
        List<Row> rows = new ArrayList<>();
        // 0.19.1: the same verdict as the fight code (engine.Hostility), with the bot's current "just hit" state
        boolean hurt = io.github.mojolowjo.entropybot.Core.INSTANCE.reflexes.recentlyHurt();
        var host = io.github.mojolowjo.entropybot.engine.Hostility.INSTANCE;
        int look = io.github.mojolowjo.entropybot.engine.ReflexRules.lookRadius(hurt);
        for (net.minecraft.world.entity.Entity e : level.entitiesForRendering()) {
            if (e == p || !(e instanceof net.minecraft.world.entity.LivingEntity le)) continue;
            double d = p.distanceTo(e);
            if (d > radius) continue;
            boolean enemy = e instanceof net.minecraft.world.entity.monster.Enemy;
            boolean neutral = e instanceof net.minecraft.world.entity.NeutralMob;
            boolean los = false;
            try { los = p.hasLineOfSight(e); } catch (RuntimeException ignored) {}
            String verdict;
            var kind = host.kind(e, hurt);
            if (!le.isAlive()) verdict = "dead";
            else if (!kind.counts()) verdict = kind.text() + (host.onList(e) && kind == io.github.mojolowjo.entropybot.engine.HostileRules.Kind.PROTECTED ? " - on the hostile list, but tamed" : "");
            else if (d > look) verdict = kind.text() + ", but too far now (look radius " + io.github.mojolowjo.entropybot.engine.ReflexRules.lookRadius(false) + ", " + io.github.mojolowjo.entropybot.engine.ReflexRules.lookRadius(true) + " when just hit)";
            else if (!io.github.mojolowjo.entropybot.engine.ReflexRules.counts(d, hurt, los)) verdict = kind.text() + ", but no line of sight now (only within 2.5 blocks without one)";
            else verdict = kind.text() + " - a threat now"
                    + (kind == io.github.mojolowjo.entropybot.engine.HostileRules.Kind.RETALIATION && io.github.mojolowjo.entropybot.engine.Hostility.strong(p, le) ? " (too strong: I retreat)" : "");
            StringBuilder cls = new StringBuilder();
            Class<?> c = e.getClass();
            for (int i = 0; c != null && i < 5; i++, c = c.getSuperclass()) {
                if (i > 0) cls.append(" < ");
                cls.append(c.getSimpleName().isEmpty() ? c.getName() : c.getName().substring(c.getName().lastIndexOf('.') + 1));
                if (c == net.minecraft.world.entity.Mob.class || c == net.minecraft.world.entity.LivingEntity.class) break;
            }
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            rows.add(new Row(d, String.format("%s \"%s\" %.1f blocks, hp %.1f/%.1f, los %s, Enemy %s, Neutral %s | %s | %s",
                    id, e.getName().getString(), d, le.getHealth(), le.getMaxHealth(), los, enemy, neutral, cls, verdict)));
        }
        rows.sort(java.util.Comparator.comparingDouble(Row::d));
        if (rows.isEmpty()) return "no living entities within " + radius + " blocks";
        StringBuilder sb = new StringBuilder("mobs within " + radius + " (" + rows.size() + (rows.size() > 20 ? ", nearest 20" : "") + ")"
                + (hurt ? ", I was just hit" + (host.lastAttacker() != null ? " (last by " + host.lastAttacker() + ")" : "") : "") + ":");
        for (int i = 0; i < Math.min(20, rows.size()); i++) sb.append("\n").append(rows.get(i).text());
        return sb.toString();
    }

    /** JSON: [{slot, id, n, dur?, max?, ench?: {id: level}}] for every filled slot (0-8 hotbar, 9-35 bag, 36-39 armor, 40 offhand). */
    static String inv(LocalPlayer p) {
        JsonArray a = new JsonArray();
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("slot", i);
            o.addProperty("id", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
            o.addProperty("n", s.getCount());
            if (s.isDamageableItem()) {
                o.addProperty("dur", s.getMaxDamage() - s.getDamageValue());
                o.addProperty("max", s.getMaxDamage());
            }
            ItemEnchantments en = s.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            if (!en.isEmpty()) {
                JsonObject e = new JsonObject();
                for (var entry : en.entrySet()) {
                    String id = entry.getKey().unwrapKey().map(k -> k.location().toString()).orElse("?");
                    e.addProperty(id, entry.getIntValue());
                }
                o.add("ench", e);
            }
            a.add(o);
        }
        return a.toString();
    }

    static String block(ClientLevel level, int[] c) {
        BlockPos pos = new BlockPos(c[0], c[1], c[2]);
        String at = c[0] + " " + c[1] + " " + c[2];
        if (!level.isLoaded(pos)) return at + ": not loaded";
        BlockState st = level.getBlockState(pos);
        BlockEntity be = level.getBlockEntity(pos);
        String beId = be == null ? "none" : String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()));
        var fluid = level.getFluidState(pos);
        String liquid = Guard.liquidNextTo(level, c[0], c[1], c[2]);
        return at + ": " + st + " | block entity: " + beId
                + " | protected: " + (Guard.INSTANCE.isProtectedBlock(st.getBlock()) ? "yes" : Guard.INSTANCE.floorReady() ? "no" : "unknown (floor not built)")
                + " | ore: " + (st.is(net.neoforged.neoforge.common.Tags.Blocks.ORES) ? "yes" : "no")
                + " | fluid: " + (fluid.isEmpty() ? "none" : String.valueOf(BuiltInRegistries.FLUID.getKey(fluid.getType())))
                + " | next to: " + (liquid == null ? "no lava or water" : liquid);
    }

    static String blocks(ClientLevel level, Recorder rec, List<String> w, long now, ZoneId zone) {
        DebugRules.Box b = DebugRules.box(w);
        String atWord = DebugRules.after(w, "at");
        String dim = Guard.dimOf(level);
        if (atWord != null) {
            long at = DebugRules.time(atWord, now, zone);
            Recorder.Slice s = rec.blocksAt(dim, b.x1(), b.y1(), b.z1(), b.x2(), b.y2(), b.z2(), at);
            if (s == null || s.ids() == null || s.ids().length != b.cells()) {
                return "nothing recorded for that box at " + DebugRules.timeText(at, now, zone) + (rec.enabled() ? "" : " (" + rec.summary() + ")");
            }
            return DebugRules.sliceJson(b, s.ids(), s.note());
        }
        String[] ids = new String[(int) b.cells()];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = b.y1(); y <= b.y2(); y++) {
            for (int z = b.z1(); z <= b.z2(); z++) {
                for (int x = b.x1(); x <= b.x2(); x++) {
                    m.set(x, y, z);
                    ids[b.index(x, y, z)] = level.isLoaded(m) ? BuiltInRegistries.BLOCK.getKey(level.getBlockState(m).getBlock()).toString() : null;
                }
            }
        }
        return DebugRules.sliceJson(b, ids, "live (" + dim + ")");
    }

    /** Settings that differ from Baritone's defaults, how it holds the protected list, the process in control. */
    static String baritone() {
        try {
            baritone.api.Settings s = baritone.api.BaritoneAPI.getSettings();
            List<String> changed = new ArrayList<>();
            for (baritone.api.Settings.Setting<?> st : baritone.api.utils.SettingsUtil.modifiedSettings(s)) {
                String name = st.getName();
                if (name.equalsIgnoreCase("blocksToDisallowBreaking") || name.equalsIgnoreCase("logger")) continue;
                String v;
                try {
                    v = baritone.api.utils.SettingsUtil.settingValueToString(st);
                } catch (RuntimeException e) {
                    v = String.valueOf(st.value);
                }
                if (v.length() > 120) v = v.substring(0, 117) + "...";
                changed.add(name + "=" + v);
            }
            baritone.api.IBaritone b = io.github.mojolowjo.entropybot.baritone.SafetyNet.primary();
            String control = "none", goal = "none";
            boolean pathing = false;
            if (b != null) {
                control = b.getPathingControlManager().mostRecentInControl().map(pr -> pr.displayName()).orElse("none");
                pathing = b.getPathingBehavior().isPathing();
                var g = b.getPathingBehavior().getGoal();
                if (g != null) goal = g.toString();
            }
            return "baritone: " + (b == null ? "not loaded" : "in control: " + control + ", " + (pathing ? "pathing" : "not pathing") + ", goal " + goal)
                    + " | protected list: " + io.github.mojolowjo.entropybot.baritone.SafetyNet.INSTANCE.held()
                    + " | changed settings: " + (changed.isEmpty() ? "none" : String.join(", ", changed));
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    // ---- the recorder (through the interface only) ----

    static String changes(ClientLevel level, Recorder rec, List<String> w, long now, ZoneId zone) {
        String usage = "usage: debug changes x y z [r] [since <time>]";
        int[] c = DebugRules.ints(w, 1, 3, usage);
        int r = DebugRules.CHANGES_R_DEFAULT;
        if (w.size() > 4 && !w.get(4).equalsIgnoreCase("since")) r = DebugRules.optInt(w, 4, DebugRules.CHANGES_R_DEFAULT, 0, DebugRules.CHANGES_R_MAX, usage);
        String sinceWord = DebugRules.after(w, "since");
        long since = sinceWord == null ? 0 : DebugRules.time(sinceWord, now, zone);
        List<Recorder.Change> list = rec.changes(Guard.dimOf(level), c[0], c[1], c[2], r, since, DebugRules.CHANGES_MAX);
        String where = "within " + r + " of " + c[0] + " " + c[1] + " " + c[2] + (sinceWord == null ? "" : " since " + DebugRules.timeText(since, now, zone));
        if (list == null || list.isEmpty()) return "no changes recorded " + where + (rec.enabled() ? "" : " (" + rec.summary() + ")");
        List<String> out = new ArrayList<>();
        for (Recorder.Change ch : list) {
            out.add(DebugRules.timeText(ch.atMs(), now, zone) + " " + ch.x() + " " + ch.y() + " " + ch.z() + " " + ch.from() + " -> " + ch.to() + (ch.byBot() ? " (bot)" : ""));
        }
        return list.size() + " changes " + where + ", newest first: " + String.join(" | ", out);
    }

    static String trail(Recorder rec, List<String> w, long now, ZoneId zone) {
        int min = DebugRules.optInt(w, 1, DebugRules.TRAIL_MINUTES_DEFAULT, 1, DebugRules.TRAIL_MINUTES_MAX, "usage: debug trail [minutes]");
        List<Recorder.TrailPoint> list = rec.trail(now - min * 60_000L, DebugRules.TRAIL_MAX);
        if (list == null || list.isEmpty()) return "no trail in the last " + min + " minutes" + (rec.enabled() ? "" : " (" + rec.summary() + ")");
        List<String> out = new ArrayList<>();
        for (Recorder.TrailPoint t : list) {
            out.add(DebugRules.timeText(t.atMs(), now, zone) + " " + t.x() + " " + t.y() + " " + t.z() + (t.note() != null ? " (" + t.note() + ")" : ""));
        }
        return "trail, last " + min + " minutes (" + list.size() + " points, oldest first): " + String.join(" | ", out);
    }

    static String incident(Recorder rec, List<String> w, long now, ZoneId zone) {
        if (w.size() > 1) {
            int n = DebugRules.ints(w, 1, 1, "usage: debug incident [n]")[0];
            String text = rec.incident(n);
            return text != null ? text : "no incident " + n + (rec.enabled() ? " (debug incident lists them)" : " (" + rec.summary() + ")");
        }
        List<Recorder.Incident> list = rec.incidents(DebugRules.INCIDENTS_LIST);
        if (list == null || list.isEmpty()) return "no incidents kept" + (rec.enabled() ? "" : " (" + rec.summary() + ")");
        List<String> out = new ArrayList<>();
        for (Recorder.Incident i : list) out.add("#" + i.n() + " " + DebugRules.timeText(i.atMs(), now, zone) + " " + i.reason() + " (" + i.file() + ")");
        return "incidents, newest first: " + String.join(" | ", out) + " - debug incident <n> shows one";
    }
}
