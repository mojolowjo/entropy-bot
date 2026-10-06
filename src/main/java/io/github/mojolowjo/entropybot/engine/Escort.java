package io.github.mojolowjo.entropybot.engine;

import baritone.api.pathing.goals.GoalNear;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * C7 "escort me|<player>": the reflex side. The walking is the ordinary follow job (Commands starts it); this picks
 * the mob to fight for the player ({@link EscortRules#pick}), keeps the bot 2-4 blocks off, throws food to a hungry
 * owner (companion v2's food field) and whispers once per creeper or skeleton closing in. Reflexes call it each tick.
 * Never attacks a player or a pet: candidates pass {@link Hostility#kind} and {@link Hostility#mayAttack}.
 */
public final class Escort {
    private static final Logger LOG = LogUtils.getLogger();

    /** One whisper for Commands to send: to whom, what. */
    public record Whisper(String to, String text) {}

    private volatile String name, from;
    private volatile int radius = EscortRules.RADIUS;
    private int fights;
    private long startedMs, lastFedMs = -EscortRules.FEED_COOLDOWN_MS, stepUntil;
    private boolean stepping;
    private final Set<Integer> warned = new HashSet<>();
    private final Map<Integer, Double> lastDist = new HashMap<>();
    private final List<Whisper> out = new ArrayList<>();
    /** name -> {food, health} from the companion (-1 = unknown), or null; set by Commands. */
    private volatile Function<String, int[]> vitals = n -> null;

    public void setVitals(Function<String, int[]> f) { vitals = f == null ? n -> null : f; }

    public boolean active() { return name != null; }

    public String name() { return name; }

    public void start(String player, String requester, int r) {
        name = player;
        from = requester;
        radius = r;
        fights = 0;
        startedMs = System.currentTimeMillis();
        warned.clear();
        lastDist.clear();
    }

    /** Ends it; the text for the log (null when it was not on). */
    public String stop(String why) {
        if (name == null) return null;
        String t = "escort of " + name + " ended: " + why + " (" + fights + " fight" + (fights == 1 ? "" : "s") + ")";
        name = null;
        stepping = false;
        LOG.info("[entropybot] {}", t);
        return t;
    }

    public void countFight() { fights++; }

    public String statusText() {
        if (name == null) return "not escorting anyone (escort me [radius] | escort <player> [radius])";
        long min = (System.currentTimeMillis() - startedMs) / 60000;
        return "escorting " + name + " (radius " + radius + ", " + fights + " fight" + (fights == 1 ? "" : "s") + " in " + min + " min, food throws "
                + (lastFedMs > startedMs ? "done" : "none yet") + ")";
    }

    public JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("on", name != null);
        if (name != null) {
            o.addProperty("player", name);
            o.addProperty("radius", radius);
            o.addProperty("fights", fights);
        }
        return o;
    }

    public synchronized List<Whisper> takeWhispers() {
        if (out.isEmpty()) return List.of();
        List<Whisper> l = List.copyOf(out);
        out.clear();
        return l;
    }

    private synchronized void say(String text) {
        if (from != null) out.add(new Whisper(name != null && !name.equalsIgnoreCase(from) ? name : from, text));
    }

    /** The guarded player when in view, else null. */
    Player player(Minecraft mc) {
        String n = name;
        if (n == null || mc.level == null) return null;
        for (Player pl : mc.level.players()) if (pl.getGameProfile().getName().equalsIgnoreCase(n)) return pl;
        return null;
    }

    /** The mob to fight for the guarded player (in view), or null. */
    Entity threat(Minecraft mc, LocalPlayer p) {
        Player g = player(mc);
        if (g == null || g == p) return null;
        List<EscortRules.Mob> mobs = new ArrayList<>();
        List<Entity> ents = new ArrayList<>();
        int r = radius;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || e instanceof Player || !(e instanceof LivingEntity le) || !le.isAlive()) continue;
            if (e.distanceTo(g) > r * 2.0 + 1) continue;
            if (!Hostility.INSTANCE.kind(e, false).counts() || !Hostility.mayAttack(e)) continue;   // never a player or a pet
            boolean aggressive = e instanceof Mob m && m.isAggressive();
            mobs.add(new EscortRules.Mob(e.getId(), idOf(e), e.getX(), e.getY(), e.getZ(), aggressive, e.getYHeadRot(), e instanceof Creeper));
            ents.add(e);
        }
        int i = EscortRules.pick(mobs, g.getX(), g.getY(), g.getZ(), p.getX(), p.getZ(), r);
        return i < 0 ? null : ents.get(i);
    }

    /** Where to stand for a creeper near the player (between them), or null when the player is not in view. */
    BlockPos interpose(Minecraft mc, Entity creeper) {
        Player g = player(mc);
        if (g == null) return null;
        double[] s = EscortRules.interpose(g.getX(), g.getZ(), creeper.getX(), creeper.getZ());
        return BlockPos.containing(s[0], g.getY(), s[1]);
    }

    /** Every tick while escorting: the danger whispers. Never throws. */
    void watch(Minecraft mc) {
        try {
            Player g = player(mc);
            if (g == null) return;
            for (Entity e : mc.level.entitiesForRendering()) {
                String id = idOf(e);
                if (!EscortRules.warnKind(id) || !e.isAlive()) continue;
                double d = e.distanceTo(g);
                if (d > EscortRules.WARN_DIST + 8) continue;
                Double prev = lastDist.put(e.getId(), d);
                String w = EscortRules.warning(id, prev == null ? Double.MAX_VALUE : prev, d, g.getX(), g.getZ(), e.getX(), e.getZ(), warned.contains(e.getId()));
                if (w != null) {
                    warned.add(e.getId());
                    say(w);
                }
            }
            if (lastDist.size() > 200) lastDist.clear();
            if (warned.size() > 500) warned.clear();
        } catch (RuntimeException ex) {
            LOG.warn("[entropybot] escort watch: {}", ex.toString());
        }
    }

    /** While no reflex runs: step back to 2-4 blocks, throw food to a hungry player. Never throws. */
    void idle(Minecraft mc, LocalPlayer p, EngineProcess engine, long now) {
        try {
            Player g = player(mc);
            if (g == null) {
                if (stepping) { stepping = false; engine.release(); }
                return;
            }
            double[] s = EscortRules.standoff(g.getX(), g.getZ(), p.getX(), p.getZ());
            if (stepping && (s == null || now >= stepUntil)) {
                stepping = false;
                engine.release();
            } else if (!stepping && s != null && p.distanceTo(g) < EscortRules.STAND_MIN) {
                stepping = true;
                stepUntil = now + 40;
                engine.override(new GoalNear(BlockPos.containing(s[0], p.getY(), s[1]), 0));
            }
            if (now % 20 == 0) feed(mc, p, g);
        } catch (RuntimeException ex) {
            LOG.warn("[entropybot] escort: {}", ex.toString());
        }
    }

    /** The reflexes took over (a fight): drop the step-back, they own the engine now. */
    void interrupted() { stepping = false; }

    private void feed(Minecraft mc, LocalPlayer p, Player g) {
        if (mc.screen != null || p.containerMenu != p.inventoryMenu || p.distanceTo(g) > 5) return;
        int[] v = vitals.apply(name);
        int food = v == null ? -1 : v[0];
        int carried = 0, bestSlot = -1, bestCount = 0;
        var inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getItem(i);
            if (Reflexes.foodScore(st, 20f) < 0) continue;          // never golden apples, never harmful food
            carried += st.getCount();
            if (st.getCount() > bestCount) { bestCount = st.getCount(); bestSlot = i; }
        }
        long nowMs = System.currentTimeMillis();
        int n = Math.min(EscortRules.feed(food, carried, nowMs, lastFedMs), bestCount);
        if (n <= 0 || bestSlot < 0) return;
        String id = BuiltInRegistries.ITEM.getKey(inv.getItem(bestSlot).getItem()).toString();
        String item = BuiltInRegistries.ITEM.getKey(inv.getItem(bestSlot).getItem()).getPath();
        p.lookAt(EntityAnchorArgument.Anchor.EYES, g.getEyePosition());
        // 0.21.0: the same THROW click as give/carry/fetch (one helper, Mule.throwItems)
        int thrown = io.github.mojolowjo.entropybot.commands.Mule.throwItems(p, id, n);
        if (thrown == 0) return;
        lastFedMs = nowMs;
        say("You're hungry (food " + food + "): threw you " + thrown + " " + item.replace('_', ' ') + ".");
        LOG.info("[entropybot] escort: threw {} {} to {} (food {})", thrown, item, name, food);
    }

    static String idOf(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }
}
