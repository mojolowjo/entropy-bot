package io.github.mojolowjo.entropybot.engine;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.events.EventRing;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import io.github.mojolowjo.entropybot.memory.Knowledge;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import baritone.api.pathing.goals.GoalGetToBlock;

import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import io.github.mojolowjo.entropybot.threat.FightOrFlee;
import io.github.mojolowjo.entropybot.threat.DeadEnd;
import io.github.mojolowjo.entropybot.threat.ThreatRuntime;
import org.slf4j.Logger;

/**
 * The bot's reflexes in Java (B2, docs/BOT_PLAN.md 5.8): eat, fight monsters, run from creepers, retreat
 * home at low health, respawn, and leave a denied dimension. They run under every job; while one runs,
 * {@link #hold()} is true and the bridge's jobs hold still. Moving goes through {@link EngineProcess},
 * so a walk the bridge started keeps its goal and carries on afterwards.
 */
public final class Reflexes {
    private static final Logger LOG = LogUtils.getLogger();

    public enum Reflex { NONE, EATING, FIGHTING, FLEEING, RETREATING, FETCHING }

    private enum Fetch { TP, WALK, OPEN, TAKE }

    /** A remembered spot from the bridge's notes (the base, the /home landing). */
    public record Place(int x, int y, int z, String dim) {
        static Place of(JsonObject o) {
            if (o == null || !o.has("x") || !o.has("y") || !o.has("z")) return null;
            return new Place(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                    o.has("dim") ? o.get("dim").getAsString() : "minecraft:overworld");
        }
    }

    /** kind: why it counts (HostileRules); strong: a retaliation target too strong to fight (retreat instead). */
    private record Threat(Entity e, double d, String id, boolean creeper, HostileRules.Kind kind, boolean strong) {}

    private final EventRing events;
    private final EngineProcess engine;
    private final Knowledge knowledge;
    // the food run (B3a)
    private List<FoodRun.Target> fetchTargets;
    private int fetchIdx, fetchTaken;
    private Fetch fetchStage;
    private long fetchStageAt, fetchCooldownUntil, fetchBestTick;
    private double fetchBest;
    private String fetchNote;
    private long now;
    private boolean defence = true;
    private Reflex reflex = Reflex.NONE;
    private String target;
    private double targetDist;
    private boolean urgent;
    private float lastHealth = 20;
    private long hurtTick = -10000;
    private int errors;
    // eating
    private int eatTicks, bites, eatStartFood;
    private boolean eatRequested, noFood;
    private long eatCooldownUntil;
    // creepers
    private long fleeUntil;
    private Goal fleeGoal, retreatGoal;
    // retreating
    private long retreatStart, calmSince, homeSentAt = -100000;
    private Place retreatTo;
    private int[] fleeTo;
    private double lastX, lastY, lastZ;
    // the base and the /home landing (Commands.pushPlaces)
    private volatile Place base, home;
    // death
    private int deadTicks;
    // dimension
    private String lastDim, deniedDim;
    private long deniedAt;
    // the creeper duel (B7e C): kill a lone creeper with hit and back off instead of running
    private final CreeperDuel duel;
    private long forceFleeUntil;
    private final List<String> whispers = new java.util.ArrayList<>();
    /** C7: escort me|<player> (Commands starts the follow; this fights for them). */
    public final Escort escort = new Escort();
    private boolean escortFighting;
    /** 0.23.6: fire, milk, the fight potion. */
    public final Survival survival;

    public Reflexes(EventRing events, EngineProcess engine, Knowledge knowledge) {
        this.events = events;
        this.engine = engine;
        this.knowledge = knowledge;
        this.duel = new CreeperDuel(events);
        this.survival = new Survival(events, engine);
    }

    /** "defend creepers flee|melee|bow" (commands.json "creepers"). */
    public void setCreeperMode(CreeperRules.Mode m) {
        duel.setMode(m);
        if (m == CreeperRules.Mode.FLEE && duel.active() && reflex == Reflex.FIGHTING) settle("creepers: flee");
    }

    public CreeperRules.Mode creeperMode() { return duel.mode(); }

    // ---- attack <entityId> | nearest (C1, 0.20.5): one forced fight through the same reflex loop ----
    static final double FORCED_RANGE = 24;
    static final long FORCED_TICKS = 600;           // 30 s
    private int forcedId = -1;
    private long forcedUntil;
    private String forcedName;

    /**
     * The owner points the bot at one mob (the companion's point key on a monster). Same rules as the defence:
     * {@link Hostility#mayAttack} and a counted kind, never a player or a pet. The fight ends when it dies, moves out of
     * {@link #FORCED_RANGE} or 30 s pass; the end is whispered.
     */
    public String attack(String arg) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "error: not in a world";
        String a = arg == null ? "" : arg.trim().toLowerCase(java.util.Locale.ROOT);
        Entity e = null;
        if (a.isEmpty() || a.equals("nearest")) {
            double best = Double.MAX_VALUE;
            for (Entity c : mc.level.entitiesForRendering()) {
                if (c == p || !(c instanceof LivingEntity le) || !le.isAlive() || !Hostility.mayAttack(c)) continue;
                if (!Hostility.INSTANCE.kind(c, true).counts()) continue;
                double d = p.distanceTo(c);
                if (d <= FORCED_RANGE && d < best) { best = d; e = c; }
            }
            if (e == null) return "no monster within " + (int) FORCED_RANGE + " blocks";
        } else {
            int id;
            try { id = Integer.parseInt(a); } catch (NumberFormatException ex) { return "usage: attack <entityId> | nearest"; }
            e = mc.level.getEntity(id);
            if (e == null) return "error: I can't see entity " + id + " (too far for me, or gone)";
        }
        String name = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
        if (e instanceof net.minecraft.world.entity.player.Player) return "error: I never attack players";
        if (!(e instanceof LivingEntity le) || !le.isAlive()) return "error: that " + name + " isn't alive";
        if (!Hostility.mayAttack(e) || !Hostility.INSTANCE.kind(e, true).counts())
            return "error: I won't attack a " + name + " (not a monster, or tamed/owned)";
        double d = p.distanceTo(e);
        if (d > FORCED_RANGE) return "error: the " + name + " is " + Math.round(d) + " blocks away (" + (int) FORCED_RANGE + " at most)";
        forcedId = e.getId();
        forcedName = name;
        forcedUntil = now + FORCED_TICKS;
        forcedAnyway = false;
        events.push("reflex", "attack " + name + " #" + forcedId, null);
        return "attacking the " + name + " (" + Math.round(d) + " blocks away)";
    }

    private boolean forcedAnyway;

    /** V1b: "stop" ends a forced attack too. */
    public void stopAttack() {
        forcedId = -1;
        forcedAnyway = false;
    }

    /** V1b: the forced target the attack rules let through (a player with defence players on). */
    private boolean forcedAllows(Entity e) { return forcedAnyway && e != null && e.getId() == forcedId; }

    /**
     * V1b: attack this entity (the verb's AttackRules said go: a passive mob confirmed, or a player with defence players
     * on). Same fight loop and end rules as {@link #attack}; the may-attack check is skipped for it.
     */
    public String forceAttack(Entity e, String name) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || e == null) return "error: not in a world";
        double d = p.distanceTo(e);
        if (d > FORCED_RANGE) return "error: the " + name + " is " + Math.round(d) + " blocks away (" + (int) FORCED_RANGE + " at most)";
        forcedId = e.getId();
        forcedName = name;
        forcedUntil = now + FORCED_TICKS;
        forcedAnyway = true;
        events.push("reflex", "attack " + name + " #" + forcedId + " (rules: go)", null);
        return "ok: attacking the " + name + " (" + Math.round(d) + " blocks away)";
    }

    /** The forced target as a threat, or null (none, or it just ended: then the end is whispered). */
    private Threat forcedThreat(LocalPlayer p, Minecraft mc) {
        if (forcedId < 0) return null;
        Entity e = mc.level.getEntity(forcedId);
        String end = null;
        if (e == null) end = "lost the " + forcedName + " (gone from view)";
        else if (!e.isAlive()) end = "killed a " + forcedName;
        else if (p.distanceTo(e) > FORCED_RANGE) end = "lost the " + forcedName + " (out of range)";
        else if (now > forcedUntil) end = "gave up on the " + forcedName + " (30 s)";
        else if (!forcedAnyway && !Hostility.mayAttack(e)) end = "stopped: the " + forcedName + " may not be attacked";
        if (end != null) {
            forcedId = -1;
            forcedAnyway = false;
            whisper(end);
            return null;
        }
        return new Threat(e, p.distanceTo(e), forcedName, e instanceof Creeper, HostileRules.Kind.ENEMY, false);
    }

    private synchronized void whisper(String s) { whispers.add(s); }

    /** What the reflexes want whispered to the owner (an explosion near the bot), taken once. */
    public synchronized List<String> takeWhispers() {
        if (whispers.isEmpty()) return List.of();
        List<String> out = List.copyOf(whispers);
        whispers.clear();
        return out;
    }

    public boolean hold() { return reflex != Reflex.NONE || survival.active(); }

    public Reflex reflex() { return reflex; }

    /** 0.24.3: a forced attack (attack / hunt) is still on. */
    public boolean forcing() { return forcedId >= 0; }

    /** 0.24.1: a retreat to a lit spot (shelter / LIT verdict): the job is held, not stopped. */
    public boolean sheltering() { return reflex == Reflex.RETREATING && retreatLit; }

    public void setDefence(boolean on) {
        defence = on;
        if (!on && (reflex == Reflex.FIGHTING || reflex == Reflex.FLEEING || reflex == Reflex.RETREATING)) settle("self-defence off");
    }

    public boolean defence() { return defence; }

    /** {"base":{x,y,z,dim},"home":{x,y,z,dim}} from the bridge's notes; either may be missing. */
    public String setPlaces(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        base = o.has("base") && o.get("base").isJsonObject() ? Place.of(o.getAsJsonObject("base")) : null;
        home = o.has("home") && o.get("home").isJsonObject() ? Place.of(o.getAsJsonObject("home")) : null;
        return "ok: base " + (base == null ? "none" : base.x + " " + base.y + " " + base.z) + ", home " + (home == null ? "none" : home.x + " " + home.y + " " + home.z);
    }

    /** The "eat" verb: eat now if hungry at all (the reflex alone waits for food 14). */
    public String eat() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return "error: not in a world";
        if (reflex == Reflex.EATING) return "error: already eating";
        if (reflex != Reflex.NONE) return "error: busy " + statusText() + " - I will eat after";
        if (!p.getFoodData().needsFood()) return "error: not hungry (food " + p.getFoodData().getFoodLevel() + "/20)";
        eatRequested = true;
        eatCooldownUntil = 0;
        if (bestFood(p) >= 0) return "started: eating";
        List<FoodRun.Target> t = foodTargets(mc, p);
        if (t.isEmpty()) {
            eatRequested = false;
            return "error: no food in my inventory, and no chest I know holds food - place food standing at one (or scan the base)";
        }
        fetchCooldownUntil = 0;
        return "started: fetching food from " + t.get(0).why() + " at " + t.get(0).key() + ", then eating";
    }

    public JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("reflex", reflex.name().toLowerCase());
        o.addProperty("status", statusText());
        o.addProperty("on", defence);
        if (target != null) {
            o.addProperty("target", target);
            o.addProperty("dist", Math.round(targetDist * 10) / 10.0);
        }
        o.addProperty("urgent", urgent);
        o.addProperty("creepers", duel.mode().word());
        if (duel.active()) o.addProperty("duel", duel.describe());
        o.addProperty("noFood", noFood);
        o.add("escort", escort.status());
        if (deniedDim != null) o.addProperty("deniedDim", deniedDim);
        o.addProperty("engine", engine.disabled() ? "off" : engine.mode().name().toLowerCase());
        try {
            o.add("threat", ThreatRuntime.INSTANCE.status());      // B2: grid ok|fallback, ms a second
        } catch (RuntimeException ignored) {}
        return o;
    }

    private String statusText() {
        return switch (reflex) {
            case NONE -> survival.active() ? survival.doing() : "none";
            case EATING -> "eating";
            case FIGHTING -> duel.active() ? duel.describe() : "fighting " + target;
            case FLEEING -> "avoiding a " + target;
            case RETREATING -> "retreating from " + target + " (health " + Math.round(lastHealth) + ")";
            case FETCHING -> "fetching food" + (fetchTargets != null && fetchIdx < fetchTargets.size() ? " from " + fetchTargets.get(fetchIdx).key() : "");
        };
    }

    /** Once per client tick. Never throws. */
    public void tick(long tick) {
        try {
            now = tick;
            step();
        } catch (Throwable t) {
            errors++;
            if (errors <= 5 || errors % 1200 == 0) LOG.error("[entropybot] reflex error #{}: {}", errors, t.toString());
            try { settle("error"); } catch (Throwable ignored) {}
        }
    }

    private void step() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            if (reflex != Reflex.NONE) settle("left the world");
            return;
        }
        io.github.mojolowjo.entropybot.threat.HitWatch.INSTANCE.observe(p);     // 0.24.4 damage log
        if (p.isDeadOrDying()) {
            death(mc, p);
            return;
        }
        deadTicks = 0;
        dimensionWatch(mc, p);
        Hostility.INSTANCE.ensureLoaded();             // 0.19.1: defence.json, once (never throws)
        float hp = p.getHealth();
        if (hp < lastHealth - 0.5f) hurtTick = now;
        if (Hostility.INSTANCE.observe(p, now)) hurtTick = now;    // a hit by a mob, even one absorption took
        lastHealth = hp;
        boolean hurt = now - hurtTick < ReflexRules.HURT_TICKS;
        io.github.mojolowjo.entropybot.threat.ThreatRuntime.INSTANCE.tick(mc, p, now, hurt);     // B2: 1 Hz reach search
        if (reflex != Reflex.EATING && survival.tick(mc, p, now, reflex == Reflex.FIGHTING || duel.active())) return;     // 0.23.6

        if (reflex == Reflex.RETREATING) {
            retreat(mc, p, hurt);
            return;
        }
        if (duel.active()) {
            CreeperDuel.End end = duel.tick(mc, p, now, hurt);
            if (end == null) {
                target = "creeper";
                targetDist = duel.dist();
                urgent = false;         // the job is held, not stopped: it carries on after the duel
                return;
            }
            duelEnded(end);
            if (!end.flee()) {
                settle(end.text());
                return;
            }
            forceFleeUntil = now + 60;  // run as before, even from beyond CREEPER_RUN
            fleeUntil = 0;
        }
        Threat ft = forcedThreat(p, mc);
        Threat t = ft != null ? ft : defence ? nearestThreat(mc, p, hurt, true) : null;
        // C7 escort: what threatens the guarded player comes first, unless something is at the bot's own throat
        // (or the owner pointed at a target with `attack`, C1)
        if (escort.active()) escort.watch(mc);
        Entity ge = ft == null && defence && escort.active() ? escort.threat(mc, p) : null;
        if (ge != null && (t == null || t.d > ReflexRules.REACH || t.e == ge)) {
            escortFight(mc, p, threatOf(p, ge, hurt), hurt, hp);
            return;
        }
        if (reflex != Reflex.FIGHTING) escortFighting = false;
        // 0.23.2, the owner's creeper heuristic: a sprint runs to its end (7+ blocks); a creeper within 6 starts one
        // (already sprinting away = keep going, else one hit for the knockback, then sprint straight away)
        if (cspr.active() && creeperSprintTick(mc, p)) return;
        if (t != null && t.creeper && t.d <= CreeperSprint.START) {
            startCreeperSprint(mc, p, t);
            return;
        }
        // then re-evaluate beyond 6: a duel only with the bow setting, or melee with a big margin (armour and health)
        if (t != null && t.creeper && hp > ReflexRules.RETREAT_AT && now >= forceFleeUntil
                && CreeperSprint.duelAfter(duel.mode(), bigCreeperMargin(mc, p))
                && duel.check(mc, p, (Creeper) t.e, now, hurt) == null) {
            startDuel(mc, p, t);
            return;
        }
        // just sprinted from it and no duel: stand still facing it (the job must not walk the bot back past it)
        if (t != null && t.creeper && t.d < CreeperSprint.WATCH && cspr.watching(now, t.e.getId())) {
            begin(Reflex.FLEEING, mc, "keeping away from a creeper");
            target = "creeper";
            targetDist = t.d;
            urgent = false;
            engine.hold();
            stopMoving(mc);
            try { p.lookAt(EntityAnchorArgument.Anchor.EYES, t.e.getEyePosition()); } catch (RuntimeException ignored) {}
            return;
        }
        if (t != null && t.creeper && t.d >= ReflexRules.CREEPER_RUN && now >= fleeUntil && now >= forceFleeUntil && !hurt) t = null;   // keep an eye on it, no more
        if (t != null) {
            if (reflex == Reflex.EATING) stopEating(mc, "interrupted by a " + t.id);
            if (reflex == Reflex.FETCHING) {
                fetchCooldownUntil = now + 200;     // after the fight it tries again
                settle("interrupted by a " + t.id);
            }
            target = t.id;
            targetDist = t.d;
            urgent = hurt || t.d < ReflexRules.URGENT;
            if (hp <= ReflexRules.RETREAT_AT || t.strong) {
                io.github.mojolowjo.entropybot.threat.ThreatRuntime.INSTANCE.verdict(t.id + ": retreat home: "
                        + (t.strong ? "too strong to fight" : "health " + Math.round(hp) + " at or below " + Math.round(ReflexRules.RETREAT_AT)));
                startRetreat(mc, p, t);
            }
            else if (t.creeper) creeper(mc, p, t);
            else fightOrFlee(mc, p, t, hurt);
            return;
        }
        if (reflex == Reflex.FIGHTING || reflex == Reflex.FLEEING) settle("no monsters left");
        if (reflex == Reflex.EATING) {
            eatStep(mc, p);
            return;
        }
        if (reflex == Reflex.FETCHING) {
            fetchStep(mc, p);
            return;
        }
        if (escort.active()) escort.idle(mc, p, engine, now);
        if (now >= eatCooldownUntil && mc.screen == null && p.containerMenu == p.inventoryMenu &&
                ((eatRequested && p.getFoodData().needsFood()) || ReflexRules.wantsMeal(p.getFoodData().getFoodLevel(), hp))) {
            startEating(mc, p);
        }
    }

    /** Every reflex ends here: keys up, Baritone back to whatever it was doing. */
    private void settle(String why) {
        Minecraft mc = Minecraft.getInstance();
        duel.abort(why);
        cspr.abort(why);
        if (reflex == Reflex.FLEEING || reflex == Reflex.RETREATING) stopMoving(mc);     // 0.23.2: safe, stop sprinting
        if (reflex == Reflex.EATING) mc.options.keyUse.setDown(false);
        if (reflex == Reflex.FETCHING && mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
        if (reflex != Reflex.NONE) events.push("reflex", "done " + reflex.name().toLowerCase() + ": " + why, null);
        reflex = Reflex.NONE;
        target = null;
        urgent = false;
        engine.release();
    }

    // ---- fighting ----

    /** Whether the bot was hit in the last {@link ReflexRules#HURT_TICKS} (for debug mobs). */
    public boolean recentlyHurt() {
        return now - hurtTick < ReflexRules.HURT_TICKS;
    }

    /**
     * The nearest mob that counts (0.19.1, {@link Hostility}): an Enemy (a NeutralMob only right after a hit), a mob on
     * the owner's hostile list, or whatever hit the bot in the last 5 s; never a player or a tamed/owned mob.
     */
    private Threat nearestThreat(Minecraft mc, LocalPlayer p, boolean hurt, boolean filter) {
        Threat best = null;
        Hostility h = Hostility.INSTANCE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || !(e instanceof LivingEntity le) || !le.isAlive()) continue;
            double d = p.distanceTo(e);
            if (d > ReflexRules.lookRadius(hurt) || (best != null && d >= best.d)) continue;
            HostileRules.Kind k = h.kind(e, hurt);
            if (!k.counts()) continue;
            boolean seen = hurt || d <= 2.5 || p.hasLineOfSight(e);
            if (!ReflexRules.counts(d, hurt, seen)) continue;
            // B2: aggro + reach by path + closing in (a mob that hit the bot, or a creeper within 6, always counts)
            if (filter && !io.github.mojolowjo.entropybot.threat.ThreatRuntime.INSTANCE.counts(e, d, h.hitMe(e))) continue;
            boolean strong = k == HostileRules.Kind.RETALIATION && Hostility.strong(p, le);
            best = new Threat(e, d, BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), e instanceof Creeper, k, strong);
        }
        return best;
    }

    private Threat threatOf(LocalPlayer p, Entity e, boolean hurt) {
        HostileRules.Kind k = Hostility.INSTANCE.kind(e, hurt);
        boolean strong = k == HostileRules.Kind.RETALIATION && e instanceof LivingEntity le && Hostility.strong(p, le);
        return new Threat(e, p.distanceTo(e), BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), e instanceof Creeper, k, strong);
    }

    private void startDuel(Minecraft mc, LocalPlayer p, Threat t) {
        if (reflex == Reflex.EATING) stopEating(mc, "interrupted by a creeper");
        if (reflex == Reflex.FETCHING) {
            fetchCooldownUntil = now + 200;
            settle("interrupted by a creeper");
        }
        duel.start(mc, p, (Creeper) t.e, now);
        reflex = Reflex.FIGHTING;
        mc.options.keyShift.setDown(false);
        target = "creeper";
        targetDist = t.d;
        urgent = false;
        engine.hold();              // the duel drives the keys; Baritone stands by with its goal
    }

    /**
     * C7: a fight for the escorted player. The retreat under 6 health stays; a creeper gets the bow duel when
     * "defend creepers bow" (and the duel's own checks agree), else the bot stands between it and the player and
     * knocks it back; anything else is the ordinary fight.
     */
    private void escortFight(Minecraft mc, LocalPlayer p, Threat t, boolean hurt, float hp) {
        escort.interrupted();
        if (reflex == Reflex.EATING) stopEating(mc, "interrupted by a " + t.id + " near " + escort.name());
        if (reflex == Reflex.FETCHING) {
            fetchCooldownUntil = now + 200;
            settle("interrupted by a " + t.id + " near " + escort.name());
        }
        if (!escortFighting) {
            escortFighting = true;
            escort.countFight();
        }
        target = t.id;
        targetDist = t.d;
        urgent = hurt || t.d < ReflexRules.URGENT;
        if (hp <= ReflexRules.RETREAT_AT || t.strong) {
            escortFighting = false;
            startRetreat(mc, p, t);
            return;
        }
        if (t.creeper) {
            if (duel.mode() == CreeperRules.Mode.BOW && now >= forceFleeUntil && duel.check(mc, p, (Creeper) t.e, now, hurt) == null) {
                startDuel(mc, p, t);
                return;
            }
            begin(Reflex.FIGHTING, mc, "guarding " + escort.name() + " from a creeper");
            holdWeapon(mc, p);
            try { p.lookAt(EntityAnchorArgument.Anchor.EYES, t.e.getEyePosition()); } catch (RuntimeException ignored) {}
            BlockPos ip = escort.interpose(mc, t.e);
            if (ip != null && (now % 20 == 0 || engine.mode() != EngineProcess.Mode.OVERRIDE)) engine.override(new GoalNear(ip, 0));
            if (t.d <= ReflexRules.REACH && p.getAttackStrengthScale(0f) >= 0.9f && (Hostility.mayAttack(t.e) || forcedAllows(t.e))) {
                mc.gameMode.attack(p, t.e);         // the knockback sends it away from the bot, which stands between
                p.swing(InteractionHand.MAIN_HAND);
            }
            return;
        }
        fight(mc, p, t);
    }

    private void begin(Reflex r, Minecraft mc, String line) {
        if (reflex == r) return;
        reflex = r;
        mc.options.keyShift.setDown(false);
        events.push("reflex", line, null);
    }

    private void fight(Minecraft mc, LocalPlayer p, Threat t) {
        begin(Reflex.FIGHTING, mc, "fighting " + t.id);
        holdWeapon(mc, p);
        try { p.lookAt(EntityAnchorArgument.Anchor.EYES, t.e.getEyePosition()); } catch (RuntimeException ignored) {}
        if (t.d <= ReflexRules.REACH) {
            engine.hold();
            if (p.getAttackStrengthScale(0f) >= 0.9f && (Hostility.mayAttack(t.e) || forcedAllows(t.e))) {     // never a player or a pet (0.19.1)
                mc.gameMode.attack(p, t.e);
                p.swing(InteractionHand.MAIN_HAND);
            }
        } else if (now % 20 == 0 || engine.mode() != EngineProcess.Mode.OVERRIDE) {
            // close the distance (skeletons shoot from range)
            engine.override(new GoalNear(t.e.blockPosition(), 1));
        }
    }

    // ---- 0.23.2: the creeper sprint (CreeperSprint) ----
    private final CreeperSprint cspr = new CreeperSprint();

    private void startCreeperSprint(Minecraft mc, LocalPlayer p, Threat t) {
        if (reflex == Reflex.EATING) stopEating(mc, "interrupted by a creeper");
        if (reflex == Reflex.FETCHING) {
            fetchCooldownUntil = now + 200;
            settle("interrupted by a creeper");
        }
        if (duel.active()) duel.abort("creeper within " + (int) CreeperSprint.START + ": sprint");
        double ax = p.getX() - t.e.getX(), az = p.getZ() - t.e.getZ();
        net.minecraft.world.phys.Vec3 v = p.getDeltaMovement();
        boolean away = CreeperSprint.sprintingAway(p.isSprinting(), v.x, v.z, ax, az);
        boolean mayHit = duel.mode() != CreeperRules.Mode.FLEE && t.d <= ReflexRules.REACH && (Hostility.mayAttack(t.e) || forcedAllows(t.e));
        double[] esc = ThreatRuntime.INSTANCE.escape(p, t.e);
        CreeperSprint.Act a = cspr.start(now, t.e.getId(), away, mayHit, v.x, v.z, esc[0], esc[1]);
        reflex = Reflex.FLEEING;
        target = "creeper";
        targetDist = t.d;
        urgent = true;
        engine.hold();              // the sprint drives the keys; Baritone stands by with its goal
        if (a == CreeperSprint.Act.HIT) {
            holdWeapon(mc, p);
            try { p.lookAt(EntityAnchorArgument.Anchor.EYES, t.e.getEyePosition()); } catch (RuntimeException ignored) {}
            mc.gameMode.attack(p, t.e);
            p.swing(InteractionHand.MAIN_HAND);
        }
        driveSprint(mc, p);         // the same tick: the next movement tick runs away
        long seen = ThreatRuntime.INSTANCE.firstSeen(t.e.getId());
        String line = "creeper at " + Math.round(t.d * 10) / 10.0 + ": " + cspr.how() + " (heading run " + (int) esc[2]
                + (seen < 0 ? "" : ", " + (now - seen) + " ticks after it appeared") + ")";
        events.push("reflex", line, null);
        LOG.info("[entropybot] creeper sprint: {}", line);
    }

    /** One tick of a running sprint; false when it just ended (the caller re-evaluates). */
    private boolean creeperSprintTick(Minecraft mc, LocalPlayer p) {
        Entity c = mc.level.getEntity(cspr.creeperId());
        double d = c == null || !c.isAlive() ? Double.NaN : p.distanceTo(c);
        if (cspr.step(now, d) == CreeperSprint.Act.DONE) {
            String why = "creeper sprint: " + cspr.endWhy() + " (" + (now - cspr.startTick()) + " ticks)";
            LOG.info("[entropybot] {}", why);
            settle(why);
            return false;
        }
        // stuck against something: a new heading from the grid
        if (p.horizontalCollision && now % 5 == 0) {
            double[] esc = ThreatRuntime.INSTANCE.escape(p, c);
            cspr.steer(esc[0], esc[1]);
        }
        target = "creeper";
        targetDist = d;
        urgent = true;
        driveSprint(mc, p);
        return true;
    }

    private String guardNote;

    private void driveSprint(Minecraft mc, LocalPlayer p) {
        // 0.23.6: never off a drop over 3, into lava, water or fire: the next 2 cells along the run, each tick
        io.github.mojolowjo.entropybot.threat.RunGuard.Verdict g = ThreatRuntime.INSTANCE.runGuard(p, cspr.dirX(), cspr.dirZ());
        if (!g.go() && !g.why().equals(guardNote)) {
            guardNote = g.why();
            events.push("reflex", "run guard: " + g.why(), null);
            LOG.info("[entropybot] run guard: {}", g.why());
        }
        if (g.stop()) {
            stopMoving(mc);
            return;
        }
        if (!g.go()) cspr.steer(g.dirX(), g.dirZ());
        float yaw = (float) Math.toDegrees(Math.atan2(-cspr.dirX(), cspr.dirZ()));
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
        mc.options.keyUp.setDown(true);
        boolean sprint = FightOrFlee.sprint(true, p.getFoodData().getFoodLevel());
        mc.options.keySprint.setDown(sprint);
        if (sprint && !p.isSprinting()) p.setSprinting(true);
        mc.options.keyJump.setDown(p.horizontalCollision && p.onGround());
    }

    /** Keys up and no sprint (a retreat or a sprint ended: safe). */
    private static void stopMoving(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
        if (mc.player != null && mc.player.isSprinting()) mc.player.setSprinting(false);
    }

    private boolean bigCreeperMargin(Minecraft mc, LocalPlayer p) {
        try {
            return FightOrFlee.creeperDuelOk(new FightOrFlee.Me(p.getHealth() + p.getAbsorptionAmount(), p.getMaxHealth(), p.getArmorValue(),
                    Hostility.weaponHit(p), 0, -1, ReflexRules.RETREAT_AT));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void creeper(Minecraft mc, LocalPlayer p, Threat t) {
        if (t.d < CreeperSprint.SAFE) {         // 0.23.2: sprint, not a Baritone walk
            startCreeperSprint(mc, p, t);
            return;
        }
        begin(Reflex.FLEEING, mc, "avoiding a " + t.id);
        // one escape spot for 3 seconds: picking a new one every tick makes Baritone re-plan non-stop
        if ((t.d < ReflexRules.CREEPER_RUN || now < forceFleeUntil) && now >= fleeUntil) {
            int[] a = ReflexRules.awayFrom(p.getX(), p.getZ(), t.e.getX(), t.e.getZ(), ReflexRules.CREEPER_RUN_TO);
            fleeGoal = new GoalXZ(a[0], a[1]);
            engine.override(fleeGoal);
            fleeUntil = now + 60;
        } else if (now < fleeUntil && fleeGoal != null && engine.mode() == EngineProcess.Mode.NONE) {
            engine.override(fleeGoal);      // a Baritone cancel (the bridge stopping a job) dropped it
        }
        if (FightOrFlee.sprint(p.getDeltaMovement().horizontalDistance() > 0.05, p.getFoodData().getFoodLevel()) && !p.isSprinting()) p.setSprinting(true);
        // one that is already right here gets knocked back
        if (t.d <= ReflexRules.REACH && p.getAttackStrengthScale(0f) >= 0.9f && (Hostility.mayAttack(t.e) || forcedAllows(t.e))) {
            holdWeapon(mc, p);
            mc.gameMode.attack(p, t.e);
            p.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** A duel's end: an explosion is whispered to the owner and made an incident in the flight recorder; the rest is logged only. */
    private void duelEnded(CreeperDuel.End end) {
        if (!end.exploded()) return;
        synchronized (this) {
            whispers.add("A creeper exploded at " + end.x() + " " + end.y() + " " + end.z() + " while I fought it.");
        }
        try {
            io.github.mojolowjo.entropybot.Core.INSTANCE.recorder.jobEnded("reflex", "creeper duel at " + end.x() + " " + end.y() + " " + end.z(), end.text());
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] recorder (creeper duel): {}", e.toString());
        }
    }

    /** The best sword (else axe) into the hand: its laid-out hotbar slot (package B), else the selected one. */
    private static void holdWeapon(Minecraft mc, LocalPlayer p) {
        var inv = p.getInventory();
        int best = -1, bestRank = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            int rank = s.getItem() instanceof SwordItem ? 2 : s.getItem() instanceof AxeItem ? 1 : 0;
            if (rank > bestRank) { bestRank = rank; best = i; }
        }
        if (best < 0 || best == inv.selected) return;
        Hotbar.toHand(mc, p, best);
    }

    // ---- retreating ----

    // ---- B2: fight or flee (docs/COMBAT_PLAN.md 4-5; only safer: a fight may become a retreat, never the reverse) ----
    private long assessedAt = -100;
    private String retreatWhy;
    private boolean retreatLit;

    private void fightOrFlee(Minecraft mc, LocalPlayer p, Threat t, boolean hurt) {
        boolean fighting = reflex == Reflex.FIGHTING;
        if (!fighting || now - assessedAt >= 10) {
            assessedAt = now;
            FightOrFlee.Result v;
            try {
                v = assess(mc, p, hurt);
                v = FightOrFlee.holdIfDeadEnd(v, ThreatRuntime.INSTANCE.deadEnd(p, t.e));     // 0.24.4
            } catch (RuntimeException e) {
                LOG.warn("[entropybot] threat: fight-or-flee failed, fighting as before: {}", e.toString());
                fight(mc, p, t);
                return;
            }
            FightOrFlee.Verdict verdict = v.verdict();
            // committed: keep fighting while not losing (the lit-spot walk is only for the start)
            if (fighting && verdict == FightOrFlee.Verdict.LIT) verdict = FightOrFlee.Verdict.FIGHT;
            if (!fighting || verdict != FightOrFlee.Verdict.FIGHT) ThreatRuntime.INSTANCE.verdict(t.id + ": " + v.why());
            if (verdict == FightOrFlee.Verdict.HOLD) {
                // 0.24.4: a dead end behind: step to the corridor mouth (one mob at a time) and fight there
                DeadEnd.Check d = fighting ? null : ThreatRuntime.INSTANCE.deadEnd(p, t.e);
                int[] m = d == null ? null : d.mouth();
                int far = m == null ? 0 : Math.abs(m[0] - p.getBlockX()) + Math.abs(m[2] - p.getBlockZ());
                if (far > 0 && far <= 6) {
                    startLitRetreat(mc, p, t, new int[]{m[0], m[1], m[2], far}, v.why());
                    return;
                }
                fight(mc, p, t);
                return;
            }
            if (verdict == FightOrFlee.Verdict.HOME) {
                retreatWhy = v.why();
                startRetreat(mc, p, t);
                return;
            }
            if (verdict == FightOrFlee.Verdict.LIT || verdict == FightOrFlee.Verdict.SHELTER) {
                int[] lit = ThreatRuntime.INSTANCE.litSpot();
                if (lit != null) {
                    startLitRetreat(mc, p, t, lit, v.why());
                    return;
                }
            }
        }
        fight(mc, p, t);
    }

    private FightOrFlee.Result assess(Minecraft mc, LocalPlayer p, boolean hurt) {
        List<FightOrFlee.Foe> foes = new java.util.ArrayList<>();
        Hostility h = Hostility.INSTANCE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || !(e instanceof LivingEntity le) || !le.isAlive()) continue;
            double d = p.distanceTo(e);
            if (d > ReflexRules.lookRadius(hurt) || !h.kind(e, hurt).counts()) continue;
            boolean seen = hurt || d <= 2.5 || p.hasLineOfSight(e);
            if (!ReflexRules.counts(d, hurt, seen) || !ThreatRuntime.INSTANCE.counts(e, d, h.hitMe(e))) continue;
            foes.add(new FightOrFlee.Foe(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), d, e instanceof Creeper));
        }
        int[] lit = ThreatRuntime.INSTANCE.litSpot();
        FightOrFlee.Me me = new FightOrFlee.Me(p.getHealth() + p.getAbsorptionAmount(), p.getMaxHealth(), p.getArmorValue(),
                Hostility.weaponHit(p), mc.level.getMaxLocalRawBrightness(p.blockPosition()), lit == null ? -1 : lit[3], ReflexRules.RETREAT_AT);
        return FightOrFlee.assess(me, foes);
    }

    private void startLitRetreat(Minecraft mc, LocalPlayer p, Threat t, int[] lit, String why) {
        if (reflex == Reflex.EATING) stopEating(mc, "retreating");
        reflex = Reflex.RETREATING;
        mc.options.keyShift.setDown(false);
        retreatStart = now;
        calmSince = now;
        fleeTo = null;
        retreatLit = true;
        retreatTo = new Place(lit[0], lit[1], lit[2], Guard.dimOf(mc.level));
        retreatGoal = new GoalNear(new BlockPos(lit[0], lit[1], lit[2]), 0);
        engine.override(retreatGoal);
        lastX = p.getX();
        lastY = p.getY();
        lastZ = p.getZ();
        events.push("reflex", "retreating from " + t.id + " to a lit spot at " + lit[0] + " " + lit[1] + " " + lit[2] + " (" + why + ")", null);
    }

    private void startRetreat(Minecraft mc, LocalPlayer p, Threat t) {
        retreatLit = false;
        if (reflex == Reflex.EATING) stopEating(mc, "retreating");
        reflex = Reflex.RETREATING;
        mc.options.keyShift.setDown(false);
        retreatStart = now;
        calmSince = now;
        retreatTo = null;
        fleeTo = null;
        String dim = Guard.dimOf(mc.level);
        Place b = base;
        // 0.24.1: a base further than path maxWalk (an old home after a move) is never the goal: get away instead
        if (b != null && b.dim.equals(dim) && dist2(p, b) > 64 && dist2(p, b) <= (double) io.github.mojolowjo.entropybot.move.ServerCmds.maxWalk() * io.github.mojolowjo.entropybot.move.ServerCmds.maxWalk()) {
            retreatTo = b;
            retreatGoal = new GoalNear(new BlockPos(b.x, b.y, b.z), 2);
        } else {
            fleeTo = ReflexRules.awayFrom(p.getX(), p.getZ(), t.e.getX(), t.e.getZ(), 16);
            retreatGoal = new GoalXZ(fleeTo[0], fleeTo[1]);
        }
        engine.override(retreatGoal);
        // far from home: /home as well, and keep moving (the server may have a warm-up). 0.24.1: only under 6 health
        // and with server commands on (the live bug: phantoms at full health sent the bot to an old home ~10M blocks off)
        Place h = home;
        boolean far = h != null && now - homeSentAt >= 1200 && (!h.dim.equals(dim) || dist2(p, h) > 16 * 16);
        boolean tp = far && io.github.mojolowjo.entropybot.move.ServerCmds.homeTp(p.getHealth(), io.github.mojolowjo.entropybot.move.ServerCmds.on());
        if (far && p.getHealth() < 6 && !tp) io.github.mojolowjo.entropybot.move.ServerCmds.allowed("the retreat under 6 health");
        if (tp) {
            homeSentAt = now;
            p.connection.sendCommand("home");
        }
        lastX = p.getX();
        lastY = p.getY();
        lastZ = p.getZ();
        String why = t.strong && p.getHealth() > ReflexRules.RETREAT_AT && t.e instanceof LivingEntity le
                ? ", too strong to fight (max health " + Math.round(le.getMaxHealth()) + ")" : "";
        events.push("reflex", "retreating from " + t.id + " (health " + Math.round(p.getHealth()) + why + ")" +
                (retreatTo != null ? " to the base" : " away from it") + (tp ? ", sent /home" : "") + (retreatWhy != null ? " - " + retreatWhy : ""), null);
        retreatWhy = null;
    }

    private void retreat(Minecraft mc, LocalPlayer p, boolean hurt) {
        double jump = Math.abs(p.getX() - lastX) + Math.abs(p.getY() - lastY) + Math.abs(p.getZ() - lastZ);
        lastX = p.getX();
        lastY = p.getY();
        lastZ = p.getZ();
        if (jump > 8) {
            settle("teleported");
            return;
        }
        if (retreatTo != null && dist2(p, retreatTo) <= 9) {
            settle(retreatLit ? "at a lit spot" : "at the base");
            return;
        }
        if (fleeTo != null && Math.abs(p.getX() - fleeTo[0]) + Math.abs(p.getZ() - fleeTo[1]) <= 3) {
            settle("got away");
            return;
        }
        // a Baritone cancel (the bridge stopping its job) dropped the goal: ask again
        if (engine.mode() == EngineProcess.Mode.NONE && retreatGoal != null) engine.override(retreatGoal);
        // 0.23.2: every retreat sprints
        if (FightOrFlee.sprint(p.getDeltaMovement().horizontalDistance() > 0.05, p.getFoodData().getFoodLevel()) && !p.isSprinting()) p.setSprinting(true);
        Threat t = nearestThreat(mc, p, true, false);     // B2 never cuts a retreat short: the old test here
        if (t != null) calmSince = now;
        if (now - calmSince >= 100 || now - retreatStart >= 1800) settle(now - calmSince >= 100 ? "nothing chasing me" : "gave up after 90 s");
    }

    private static double dist2(LocalPlayer p, Place pl) {
        double dx = p.getX() - pl.x - 0.5, dy = p.getY() - pl.y, dz = p.getZ() - pl.z - 0.5;
        return dx * dx + dy * dy + dz * dz;
    }

    // ---- eating ----

    /** The inventory slot of the best food to eat now, or -1. */
    private int bestFood(LocalPlayer p) {
        var inv = p.getInventory();
        int best = -1, bestScore = -1;
        for (int i = 0; i < 36; i++) {
            int sc = foodScore(inv.getItem(i), p.getHealth());
            // the hotbar first when it's as good: no inventory click needed
            if (sc > bestScore || (sc == bestScore && sc >= 0 && i < 9 && best >= 9)) { bestScore = sc; best = i; }
        }
        return bestScore < 0 ? -1 : best;
    }

    static int foodScore(ItemStack s, float health) {
        if (s.isEmpty()) return -1;
        FoodProperties f = s.get(DataComponents.FOOD);
        if (f == null) return -1;
        boolean harmful = false;
        for (FoodProperties.PossibleEffect pe : f.effects()) {
            if (pe.effect().getEffect().value().getCategory() == MobEffectCategory.HARMFUL) harmful = true;
        }
        return ReflexRules.foodScore(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), f.nutrition(), f.saturation(), harmful, health);
    }

    /** Puts the best food in the hand (its laid-out hotbar slot, package B, else the selected one); false when there is none. */
    private boolean holdFood(Minecraft mc, LocalPlayer p) {
        var inv = p.getInventory();
        int slot = bestFood(p);
        if (slot < 0) return false;
        if (slot == inv.selected) return true;
        return Hotbar.toHand(mc, p, slot);
    }

    private void startEating(Minecraft mc, LocalPlayer p) {
        if (!holdFood(mc, p)) {
            // nothing to eat on board: the food run, else tell the owner (once) and look again in 2 minutes
            if (now >= fetchCooldownUntil && startFetch(mc, p)) return;
            if (!noFood) events.push("reflex", "out of food (food " + p.getFoodData().getFoodLevel() + "/20)" + (fetchNote != null ? ": " + fetchNote : ""), null);
            noFood = true;
            eatRequested = false;
            eatCooldownUntil = now + 200;
            return;
        }
        noFood = false;
        reflex = Reflex.EATING;
        mc.options.keyShift.setDown(false);
        eatTicks = 0;
        bites = 0;
        eatStartFood = p.getFoodData().getFoodLevel();
        engine.hold();
        events.push("reflex", "eating (food " + eatStartFood + "/20)", null);
    }

    private void eatStep(Minecraft mc, LocalPlayer p) {
        eatTicks++;
        if (eatTicks == 2) {
            // holding the use key alone doesn't start eating while the window is unfocused: start it directly
            if (!holdFood(mc, p)) {
                finishEating(mc, p, "ran out of food");
                return;
            }
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        }
        mc.options.keyUse.setDown(eatTicks >= 2 && eatTicks < 50);
        if (eatTicks > 6 && !p.isUsingItem()) {
            bites++;
            if (p.getFoodData().needsFood() && bites < 10) {
                eatTicks = 0;
                return;
            }
            finishEating(mc, p, "full");
        } else if (eatTicks > 60) {
            eatCooldownUntil = now + 600;
            finishEating(mc, p, "could not eat");
        }
    }

    private void finishEating(Minecraft mc, LocalPlayer p, String why) {
        eatRequested = false;
        settle("ate (food " + eatStartFood + " -> " + p.getFoodData().getFoodLevel() + "/20, " + why + ")");
    }

    private void stopEating(Minecraft mc, String why) {
        eatRequested = false;
        settle(why);
    }

    // ---- the food run (B3a): nothing to eat on board, so fetch some ----

    /** Slots of modded storage the run never takes from (upgrades, filters, display items). */
    private static final Pattern SKIP_SLOT = Pattern.compile("upgrade|filter|ghost|display|fake|setting", Pattern.CASE_INSENSITIVE);
    private static final Pattern STORAGE = Pattern.compile("chest|barrel|shulker|drawer|crate|backpack|storage");

    private boolean isFoodId(String id, float health) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return false;
        Item item = BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        return item != null && foodScore(new ItemStack(item), health) >= 0;
    }

    private List<FoodRun.Target> foodTargets(Minecraft mc, LocalPlayer p) {
        float hp = p.getHealth();
        return FoodRun.targets(knowledge.places(), knowledge.chests(), id -> isFoodId(id, hp), Guard.dimOf(mc.level), p.getX(), p.getY(), p.getZ());
    }

    private boolean startFetch(Minecraft mc, LocalPlayer p) {
        fetchTargets = foodTargets(mc, p);
        fetchCooldownUntil = now + 2400;            // whatever happens, the next run waits 2 minutes
        if (fetchTargets.isEmpty()) {
            fetchNote = "no chest I know holds food (place food at one, or scan the base)";
            return false;
        }
        fetchNote = null;
        fetchIdx = 0;
        fetchTaken = 0;
        reflex = Reflex.FETCHING;
        mc.options.keyShift.setDown(false);
        FoodRun.Target t = fetchTargets.get(0);
        events.push("reflex", "out of food: fetching some from " + t.why() + " at " + t.key(), null);
        beginTarget(mc, p);
        return true;
    }

    /** Walks to the current target (after a /home when it is far and the chest is near home). */
    private void beginTarget(Minecraft mc, LocalPlayer p) {
        FoodRun.Target t = fetchTargets.get(fetchIdx);
        Place h = home;
        double d = FoodRun.dist(p.getX(), p.getY(), p.getZ(), t.x(), t.y(), t.z());
        fetchStageAt = now;
        if (d > 64 && h != null && h.dim.equals(Guard.dimOf(mc.level)) && FoodRun.dist(h.x + 0.5, h.y, h.z + 0.5, t.x(), t.y(), t.z()) <= 32 && now - homeSentAt >= 1200
                && io.github.mojolowjo.entropybot.move.ServerCmds.allowed("the food run's teleport home")) {
            homeSentAt = now;
            p.connection.sendCommand("home");
            lastX = p.getX();
            lastY = p.getY();
            lastZ = p.getZ();
            engine.hold();
            fetchStage = Fetch.TP;
            return;
        }
        walkTo(t);
    }

    private void walkTo(FoodRun.Target t) {
        engine.override(new GoalGetToBlock(new BlockPos(t.x(), t.y(), t.z())));
        fetchStage = Fetch.WALK;
        fetchStageAt = now;
        fetchBest = Double.MAX_VALUE;
        fetchBestTick = now;
    }

    private void fetchStep(Minecraft mc, LocalPlayer p) {
        FoodRun.Target t = fetchTargets.get(fetchIdx);
        BlockPos pos = new BlockPos(t.x(), t.y(), t.z());
        Vec3 center = Vec3.atCenterOf(pos);
        long in = now - fetchStageAt;
        switch (fetchStage) {
            case TP -> {
                double jump = Math.abs(p.getX() - lastX) + Math.abs(p.getY() - lastY) + Math.abs(p.getZ() - lastZ);
                lastX = p.getX();
                lastY = p.getY();
                lastZ = p.getZ();
                if (jump > 8 || in > 200) walkTo(t);
            }
            case WALK -> {
                double d = p.getEyePosition().distanceTo(center);
                if (d <= 4.4) {
                    engine.hold();
                    if (!openable(mc.level.getBlockState(pos), mc, pos)) {
                        // a "food" mark made standing next to the chest: the chest within 2 blocks of it
                        BlockPos near = openableNear(mc, p, pos);
                        if (near == null) {
                            nextTarget(mc, p, "no chest at " + t.key() + " any more");
                            return;
                        }
                        pos = near;
                        center = Vec3.atCenterOf(pos);
                        fetchTargets.set(fetchIdx, t = new FoodRun.Target(pos.getX() + " " + pos.getY() + " " + pos.getZ(), pos.getX(), pos.getY(), pos.getZ(), t.why()));
                    }
                    try { p.lookAt(EntityAnchorArgument.Anchor.EYES, center); } catch (RuntimeException ignored) {}
                    mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(center, Direction.UP, pos, false));
                    p.swing(InteractionHand.MAIN_HAND);
                    fetchStage = Fetch.OPEN;
                    fetchStageAt = now;
                    return;
                }
                if (engine.mode() == EngineProcess.Mode.NONE) engine.override(new GoalGetToBlock(pos));    // a cancel dropped it
                if (d < fetchBest - 1) {
                    fetchBest = d;
                    fetchBestTick = now;
                }
                if (now - fetchBestTick > 400 || in > 2400) nextTarget(mc, p, "couldn't get to " + t.key());
            }
            case OPEN -> {
                if (p.containerMenu != p.inventoryMenu) {
                    if (in < 15) return;           // let the contents arrive
                    takeFood(mc, p);
                    fetchStage = Fetch.TAKE;
                    fetchStageAt = now;
                } else if (in > 60) {
                    nextTarget(mc, p, t.key() + " did not open");
                }
            }
            case TAKE -> {
                if (in < 10) return;               // the server confirms the clicks
                noteOpenChest(mc, p, t);
                p.closeContainer();
                int carried = carriedFood(p);
                if (carried > 0) {
                    events.push("reflex", "took " + carried + " food from " + t.key(), null);
                    noFood = false;
                    fetchCooldownUntil = now + 200;
                    settle("fetched " + carried + " food");      // the eat reflex takes over next tick
                } else {
                    nextTarget(mc, p, t.key() + " had no food I eat");
                }
            }
        }
    }

    private void nextTarget(Minecraft mc, LocalPlayer p, String why) {
        if (p.containerMenu != p.inventoryMenu) p.closeContainer();
        events.push("reflex", "food run: " + why, null);
        fetchIdx++;
        if (fetchIdx < fetchTargets.size()) {
            beginTarget(mc, p);
            return;
        }
        fetchNote = why;
        noFood = true;
        events.push("reflex", "out of food: the food run found nothing (" + why + ")", null);
        settle("food run found nothing");
    }

    /** A storage block with a block entity: a right-click opens it and never places the block in hand. */
    private static boolean openable(BlockState st, Minecraft mc, BlockPos pos) {
        if (!st.hasBlockEntity()) return false;
        String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
        return STORAGE.matcher(id).find() && !id.contains("refinedstorage") && !id.contains("extrastorage");
    }

    /** The nearest openable storage block within 2 of pos that the bot can reach from where it stands, or null. */
    private static BlockPos openableNear(Minecraft mc, LocalPlayer p, BlockPos pos) {
        BlockPos best = null;
        double bestD = 4.5;
        for (BlockPos q : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 2, 2))) {
            if (!openable(mc.level.getBlockState(q), mc, q)) continue;
            double d = p.getEyePosition().distanceTo(Vec3.atCenterOf(q));
            if (d < bestD) { bestD = d; best = q.immutable(); }
        }
        return best;
    }

    private static boolean takeable(Slot s, LocalPlayer p) {
        return s.container != p.getInventory() && !SKIP_SLOT.matcher(s.getClass().getName()).find();
    }

    /** Shift-clicks the best food stacks out of the open container until it carries FoodRun.TAKE or more. */
    private void takeFood(Minecraft mc, LocalPlayer p) {
        AbstractContainerMenu menu = p.containerMenu;
        float hp = p.getHealth();
        for (int clicks = 0; clicks < 4 && carriedFood(p) < FoodRun.TAKE; clicks++) {
            Slot best = null;
            int bestScore = -1;
            for (Slot s : menu.slots) {
                if (!takeable(s, p) || !s.hasItem() || !s.mayPickup(p)) continue;
                int sc = foodScore(s.getItem(), hp);
                if (sc > bestScore) { bestScore = sc; best = s; }
            }
            if (best == null) return;
            mc.gameMode.handleInventoryMouseClick(menu.containerId, best.index, 0, ClickType.QUICK_MOVE, p);
        }
    }

    private int carriedFood(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (foodScore(s, p.getHealth()) >= 0) n += s.getCount();
        }
        return n;
    }

    /** What the open chest holds now goes into the notes (chests.json). */
    private void noteOpenChest(Minecraft mc, LocalPlayer p, FoodRun.Target t) {
        JsonObject items = new JsonObject();
        for (Slot s : p.containerMenu.slots) {
            if (!takeable(s, p) || !s.hasItem()) continue;
            String id = BuiltInRegistries.ITEM.getKey(s.getItem().getItem()).toString();
            items.addProperty(id, (items.has(id) ? items.get(id).getAsInt() : 0) + s.getItem().getCount());
        }
        JsonObject note = new JsonObject();
        note.addProperty("dim", Guard.dimOf(mc.level));
        note.add("items", items);
        note.addProperty("seen", System.currentTimeMillis());
        JsonObject old = knowledge.chests().get(t.key());
        if (old != null && old.has("trusted")) note.add("trusted", old.get("trusted"));
        knowledge.noteChest(t.key(), note, now);
    }

    // ---- death and dimensions ----

    private void death(Minecraft mc, LocalPlayer p) {
        deadTicks++;
        if (deadTicks == 1) {
            if (reflex != Reflex.NONE) settle("died");
            io.github.mojolowjo.entropybot.summary.DaySummary.INSTANCE.death();      // 0.23.6
            events.push("reflex", "died at " + p.getBlockX() + " " + p.getBlockY() + " " + p.getBlockZ(), null);
        }
        // respawn after 2 seconds, and again every 10 s if the first click didn't take
        if (deadTicks == 40 || (deadTicks > 40 && (deadTicks - 40) % 200 == 0)) {
            p.respawn();
            mc.setScreen(null);
        }
    }

    private void dimensionWatch(Minecraft mc, LocalPlayer p) {
        String dim = Guard.dimOf(mc.level);
        if (!dim.equals(lastDim)) {
            lastDim = dim;
            if (GuardCore.DENIED_DIMS.contains(dim)) {
                deniedDim = dim;
                deniedAt = now;
                if (reflex != Reflex.NONE) settle("entered " + dim);
                try {
                    IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
                    if (b != null) b.getPathingBehavior().cancelEverything();
                } catch (Throwable ignored) {}
                events.push("reflex", "in " + dim + ", which is off limits: going home", null);
            } else {
                deniedDim = null;
            }
        }
        // /home 2 s after arriving, then once a minute until it works
        if (deniedDim != null && now - deniedAt >= 40 && (now - deniedAt - 40) % 1200 == 0
                && io.github.mojolowjo.entropybot.move.ServerCmds.allowed("leaving " + deniedDim + " by /home (no walk out of another dimension)")) p.connection.sendCommand("home");
    }
}
