package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.guard.Box;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import io.github.mojolowjo.entropybot.guard.Policy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B7e package N (items 3 and 8): the game side of {@link SelfCheck} (the {@code check} verb and the idle check every
 * 30 minutes) and the facts {@link ConfirmGate}'s summaries use. 0.21.2: the idle check whispers one line at most every
 * 30 minutes (also across restarts), only bag full / no base / no food chest (gear only for items in the supplies), each
 * cause once ({@link SelfCheck#idle}; the causes told and the last whisper's time are in commands.json "selfCheck").
 */
public final class SelfCheckLive implements ConfirmGate.Facts {
    private static final Logger LOG = LoggerFactory.getLogger("entropybot");
    public static final long IDLE_EVERY_MS = 30 * 60_000L;

    private final Commands c;
    private long lastIdleCheck;

    public SelfCheckLive(Commands c) {
        this.c = c;
    }

    /** "check": every finding with its fix (and the idle check starts from these). */
    public String command(LocalPlayer p) {
        List<SelfCheck.Finding> f = findings(p);
        remember(SelfCheck.keys(f));
        lastIdleCheck = System.currentTimeMillis();
        return SelfCheck.report(f);
    }

    /** The rules' findings plus routing's (routing stage 1: planner not running, its errors, many fallbacks). */
    private List<SelfCheck.Finding> findings(LocalPlayer p) {
        List<SelfCheck.Finding> f = new java.util.ArrayList<>(SelfCheck.run(state(p)));
        // C5 tool care: a worn or broken tool that nothing could replace
        for (String id : c.camp.careFindings()) f.add(new SelfCheck.Finding("toolcare:" + id, "my " + id + " is nearly broken or gone, and nothing can replace it (none in the chests, nothing to craft one from)",
                "put a " + id + " (or its materials) in the base chests"));
        f.addAll(RouteCommand.findings(c));
        try {
            f.addAll(io.github.mojolowjo.entropybot.watchview.TunnelView.INSTANCE.findings());   // camera hooks (0.15.2)
        } catch (RuntimeException e) {
            f.add(new SelfCheck.Finding("watchcheck", "couldn't check the watch camera: " + e, "watch status"));
        }
        try {
            f.addAll(io.github.mojolowjo.entropybot.surface.SurfaceExport.INSTANCE.findings());      // 0.19.3: surface export
        } catch (RuntimeException e) {
            f.add(new SelfCheck.Finding("surface", "couldn't check the surface export: " + e, "surface status"));
        }
        try {
            f.addAll(io.github.mojolowjo.entropybot.engine.WatchSteer.INSTANCE.findings());      // watch steer (TLL 32b)
        } catch (RuntimeException e) {
            f.add(new SelfCheck.Finding("steercheck", "couldn't check watch steer: " + e, "watch steer status"));
        }
        try {
            f.addAll(io.github.mojolowjo.entropybot.engine.Hostility.INSTANCE.findings());   // defence.json (0.19.1)
        } catch (RuntimeException e) {
            f.add(new SelfCheck.Finding("hostilecheck", "couldn't check the hostile list: " + e, "defend hostile list"));
        }
        try {
            f.addAll(RestoreLive.INSTANCE.findings());      // P1: the restore hook, restore.json, blocks waiting, build hints
        } catch (RuntimeException e) {
            f.add(new SelfCheck.Finding("restorehook", "couldn't check the restore ledger: " + e, "restore status"));
        }
        try {
            var z = Core.INSTANCE.guard.core.near;          // 0.21.2 (check only: never whispered)
            f.addAll(SelfCheck.nearFindings(z.unknownForMs(System.currentTimeMillis()), z.errors(), ownerOnline()));
        } catch (RuntimeException e) {
            f.add(new SelfCheck.Finding("nearzone", "couldn't check the near-me zone: " + e, "area near status"));
        }
        return f;
    }

    /**
     * Call now and then (every 200 ticks is plenty): while the bot is idle, at most every 30 minutes, whispers the owner
     * the findings that are new and the ones that went away. idle: no job, no chain, nothing the bridge runs.
     */
    public void idleTick(LocalPlayer p, boolean idle, long now) {
        // 0.21.2: look once a minute; the whisper itself is rate-limited to one per 30 min across restarts
        // (SelfCheck.idle and commands.json selfCheck.lastWhisper), one line, once per cause
        if (!idle || p == null || now - lastIdleCheck < IDLE_LOOK_MS) return;
        if (!ownerOnline()) return;                       // nobody to tell: keep the keys, look again later
        lastIdleCheck = now;
        try {
            List<SelfCheck.Finding> f = findings(p);
            Set<String> supplyIds = c.ownerSupplyIds();      // the owner's gear targets only, never the autominer's defaults
            int free = 0;
            for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) free++;
            Set<String> before = remembered();
            SelfCheck.Idle r = SelfCheck.idle(before, f, supplyIds, free, now, lastWhisper());
            if (r.whisper() == null) {
                if (!r.told().equals(before)) remember(r.told(), lastWhisper());      // a cause cleared: forget it silently
                return;
            }
            LOG.info("[entropybot] {}", r.whisper());
            idleWhispers++;
            if (c.whisperSent(c.owner(), r.whisper())) remember(r.told(), now);     // only what was really sent counts as told
            else remember(before, now);                   // dropped by a full outbox: try that cause again, but not before 30 min
        } catch (RuntimeException e) {
            idleErrors++;
            if (idleErrors <= 5 || idleErrors % 100 == 0) LOG.warn("[entropybot] self-check #{}: {}", idleErrors, e.toString());
        }
    }

    /** 0.21.2: the idle check looks this often (it whispers at most every {@link SelfCheck#WHISPER_EVERY_MS}). */
    public static final long IDLE_LOOK_MS = 60_000L;
    private long idleWhispers, idleErrors;

    /** When the last self-check whisper went out (commands.json selfCheck.lastWhisper), -1 = never. */
    private long lastWhisper() {
        JsonObject b = c.brainData();
        try {
            if (b.has("selfCheck") && b.get("selfCheck").isJsonObject() && b.getAsJsonObject("selfCheck").has("lastWhisper"))
                return b.getAsJsonObject("selfCheck").get("lastWhisper").getAsLong();
        } catch (RuntimeException ignored) {}
        return -1;
    }

    private Set<String> remembered() {
        Set<String> out = new LinkedHashSet<>();
        JsonObject b = c.brainData();
        if (b.has("selfCheck") && b.get("selfCheck").isJsonObject()) {
            JsonObject s = b.getAsJsonObject("selfCheck");
            if (s.has("keys") && s.get("keys").isJsonArray()) for (JsonElement e : s.getAsJsonArray("keys")) out.add(e.getAsString());
        }
        return out;
    }

    private void remember(Set<String> keys) { remember(keys, lastWhisper()); }

    /** keys: the causes told (or seen by "check"); lastWhisperMs: when the last idle whisper went out (-1 = never). */
    private void remember(Set<String> keys, long lastWhisperMs) {
        if (keys.equals(remembered()) && c.brainData().has("selfCheck") && lastWhisperMs == lastWhisper()) return;
        JsonObject s = new JsonObject();
        JsonArray a = new JsonArray();
        keys.forEach(a::add);
        s.add("keys", a);
        s.addProperty("at", System.currentTimeMillis());
        if (lastWhisperMs >= 0) s.addProperty("lastWhisper", lastWhisperMs);
        c.brainData().add("selfCheck", s);
        c.saved();
    }

    /** What the rules look at, read from the stores and the game. */
    public SelfCheck.State state(LocalPlayer p) {
        Core core = Core.INSTANCE;
        GuardCore g = core.guard.core;
        Policy pol = g.basePolicy();          // the owner's areas, not the near-me zone
        int areas = pol == null || pol.areas == null ? 0 : pol.areas.size();
        Map<String, JsonObject> places = core.knowledge.places();
        JsonObject base = places.get("base");
        int baseChests = base == null ? 0 : chestsNear(core, Jobs.pos(base), Jobs.dimOf(base));
        JsonObject mine = places.get("mine");
        int[] minePos = mine == null ? null : Jobs.pos(mine);
        String mineDir = mine != null && mine.has("dir") ? mine.get("dir").getAsString() : null;
        List<SelfCheck.Tool> tools = new ArrayList<>();
        int free = 0;
        if (p != null) {
            for (int i = 0; i < 36; i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (st.isEmpty()) {
                    free++;
                    continue;
                }
                if (st.isDamageableItem() && st.getMaxDamage() > 0) {
                    tools.add(new SelfCheck.Tool(Commands.itemId(st), st.getMaxDamage() - st.getDamageValue(), st.getMaxDamage()));
                }
            }
        }
        return new SelfCheck.State(g.mode() == GuardCore.Mode.STRICT, areas, base != null, baseChests, places.containsKey("food"), c.home() != null,
                minePos, mineDir, mine == null ? null : mineGaveUp(), c.suppliesMap(), free, tools, ownerOnline(), companionAge(core));
    }

    /** Trusted container notes within 32 blocks of the base (as Storage.baseChests, without looking at the blocks). */
    static int chestsNear(Core core, int[] center, String dim) {
        int n = 0;
        for (Map.Entry<String, JsonObject> e : core.knowledge.chests().entrySet()) {
            JsonObject ch = e.getValue();
            if (!dim.equals(ch.has("dim") ? ch.get("dim").getAsString() : null)) continue;
            if (ch.has("trusted") && !ch.get("trusted").getAsBoolean()) continue;
            String[] q = e.getKey().split(" ");
            if (q.length < 3) continue;
            try {
                int[] pos = {Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2])};
                if (Jobs.distSq(pos, center) <= 32 * 32) n++;
            } catch (NumberFormatException ignored) {}
        }
        return n;
    }

    /** The last strip run's end when it gave up (the autominer's log), else null. */
    private String mineGaveUp() {
        JsonObject b = c.brainData();
        JsonObject a = b.has("autominer") && b.get("autominer").isJsonObject() ? b.getAsJsonObject("autominer") : null;
        if (a == null || !a.has("log") || !a.get("log").isJsonArray()) return null;
        JsonArray log = a.getAsJsonArray("log");
        for (int i = log.size() - 1; i >= 0; i--) {
            JsonObject e = log.get(i).getAsJsonObject();
            String what = e.has("what") ? e.get("what").getAsString() : "";
            if (!what.startsWith("mine strip")) continue;
            if (!Chains.stripGaveUp(e)) return null;
            return e.get("result").getAsString().replaceFirst("^stopped at step \\d+ \\([^)]*\\): ", "");
        }
        return null;
    }

    private boolean ownerOnline() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return false;
        for (PlayerInfo i : mc.getConnection().getOnlinePlayers()) if (i.getProfile().getName().equalsIgnoreCase(c.owner())) return true;
        return false;
    }

    /** How old the companion's last position is (ms), -1 without owner.json. */
    private static long companionAge(Core core) {
        try {
            if (core.files() == null) return -1;
            Path f = core.files().root().resolve("owner.json");
            if (!Files.exists(f)) return -1;
            OwnerFix.Fix fix = OwnerFix.parse(Files.readString(f));
            return fix == null ? -1 : Math.max(0, System.currentTimeMillis() - fix.received());
        } catch (Exception e) {
            return -1;
        }
    }

    // ---- ConfirmGate.Facts ----

    @Override
    public String zone() {
        JsonObject z = DigCommands.zone(c);
        return DigCommands.complete(z) ? DigCommands.zoneText(z) : null;
    }

    @Override
    public String mine() {
        JsonObject m = Core.INSTANCE.knowledge.places().get("mine");
        if (m == null) return null;
        JsonObject cur = StripMine.get().book().current();
        return "the mine at " + Jobs.fmt(Jobs.pos(m)) + (m.has("dir") ? " " + m.get("dir").getAsString() : "")
                + (cur != null ? " (next branch " + io.github.mojolowjo.entropybot.strip.MineBook.k(cur) + ")" : "");
    }

    @Override
    public String area(String name) {
        Policy pol = Core.INSTANCE.guard.core.basePolicy();
        if (pol == null || pol.areas == null) return null;
        for (Box b : pol.areas) {
            if (b.name != null && b.name.equalsIgnoreCase(name)) {
                Minecraft mc = Minecraft.getInstance();
                boolean here = mc.level != null && Guard.dimOf(mc.level).equals(b.dim);
                return "x " + b.x1 + ".." + b.x2 + ", z " + b.z1 + ".." + b.z2 + (here ? "" : ", " + b.dim);
            }
        }
        return null;
    }
}
