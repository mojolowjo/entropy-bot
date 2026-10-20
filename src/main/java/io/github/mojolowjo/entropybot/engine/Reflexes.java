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
import io.github.mojolowjo.entropybot.motor.Motor;
import io.github.mojolowjo.entropybot.threat.ThreatFacts;
import io.github.mojolowjo.entropybot.threat.ThreatRuntime;

import java.util.List;
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
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import org.slf4j.Logger;

/**
 * The bot's reflexes in Java (B2, docs/BOT_PLAN.md 5.8), AA_CLEANUP 4: only the true reflexes. Air (swim up, the
 * shore), lava and fire ({@link FireReflex}), the creeper flee (sprint, the duel only with the owner's creeper setting),
 * the hit response (hit and the attacker in reach: face it and strike back), eating from the bag, respawn and a denied
 * dimension; plus the hold and override of the engine. With AA off the old self-defence still fights what counts (no
 * retreat rules any more). Fight, flee, home, cover, dig in and pillar are AA's danger desire ({@code aa.DangerOptions}),
 * run through the verbs of {@link DangerMoves}. While a reflex or a move runs {@link #hold()} is true and the jobs hold
 * still. Moving goes through {@link EngineProcess}, so a walk the bridge started keeps its goal and carries on afterwards.
 */
public final class Reflexes {
    private static final Logger LOG = LogUtils.getLogger();

    /** RETREATING: a danger move runs (DangerMoves: flee, cover, pillar, burrow). */
    public enum Reflex { NONE, EATING, FIGHTING, FLEEING, RETREATING }

    /** A remembered spot from the bridge's notes (the base, the /home landing). */
    public record Place(int x, int y, int z, String dim) {
        static Place of(JsonObject o) {
            if (o == null || !o.has("x") || !o.has("y") || !o.has("z")) return null;
            return new Place(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                    o.has("dim") ? o.get("dim").getAsString() : "minecraft:overworld");
        }
    }

    /** kind: why it counts (HostileRules). */
    private record Threat(Entity e, double d, String id, boolean creeper, HostileRules.Kind kind) {}

    private final EventRing events;
    private final EngineProcess engine;
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
    private Goal fleeGoal;
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
    /** AA_CLEANUP 4: the lava/fire reflex (Survival's fire part; the milk and potions went). */
    public final FireReflex fire;
    /** AA_CLEANUP 4: the danger moves AA's danger desire (or the owner) starts: flee, cover, pillar, burrow. */
    public final DangerMoves moves;

    public Reflexes(EventRing events, EngineProcess engine, Knowledge knowledge) {
        this.events = events;
        this.engine = engine;
        this.duel = new CreeperDuel(events);
        this.fire = new FireReflex(events, engine);
        this.moves = new DangerMoves(events, engine);
        Motor.INSTANCE.bind(engine);        // 0.27.0: the motor pauses the job's Baritone through the same process
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
        moves.stop("stopped by the owner");       // AA_CLEANUP 4
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
        return new Threat(e, p.distanceTo(e), forcedName, e instanceof Creeper, HostileRules.Kind.ENEMY);
    }

    private synchronized void whisper(String s) { whispers.add(s); }

    /** What the reflexes want whispered to the owner (an explosion near the bot), taken once. */
    public synchronized List<String> takeWhispers() {
        if (whispers.isEmpty()) return List.of();
        List<String> out = List.copyOf(whispers);
        whispers.clear();
        return out;
    }

    public boolean hold() { return reflex != Reflex.NONE || fire.active() || moves.active(); }

    /** AA_CLEANUP 4: RETREATING while a danger move runs (the jobs hold; AA does not count it as its danger pause). */
    public Reflex reflex() { return reflex == Reflex.NONE && moves.active() ? Reflex.RETREATING : reflex; }

    /** 0.24.3: a forced attack (attack / hunt) is still on. */
    public boolean forcing() { return forcedId >= 0; }

    /** 0.30.6: something in the bag the bot would eat now (raw beef counts; raw chicken does not). */
    public boolean canEat(LocalPlayer p) { return p != null && bestFood(p) >= 0; }

    public void setDefence(boolean on) {
        defence = on;
        if (!on && (reflex == Reflex.FIGHTING || reflex == Reflex.FLEEING)) settle("self-defence off");
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
        eatRequested = false;
        // AA_CLEANUP 4: the food run went (aa-food fetches food under AA)
        return "error: no food in my inventory - bring me food, or aa on (aa-food gets some)";
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
            case NONE -> fire.active() ? fire.doing() : moves.active() ? moves.doing() : "none";
            case EATING -> "eating";
            case FIGHTING -> duel.active() ? duel.describe() : "fighting " + target;
            case FLEEING -> "avoiding a " + target;
            case RETREATING -> moves.doing();
        };
    }

    /** Once per client tick. Never throws. */
    public void tick(long tick) {
        try {
            now = tick;
            moves.time(tick);      // 0.38.b9: the verbs start moves on the current clock
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
            releaseSwim(mc);
            if (reflex != Reflex.NONE) settle("left the world");
            moves.stop("left the world");
            return;
        }
        io.github.mojolowjo.entropybot.threat.HitWatch.INSTANCE.observe(p);     // 0.24.4 damage log
        if (p.isDeadOrDying()) {
            releaseSwim(mc);
            death(mc, p);
            return;
        }
        deadTicks = 0;
        dimensionWatch(mc, p);
        airWatch(mc, p);
        Hostility.INSTANCE.ensureLoaded();             // 0.19.1: defence.json, once (never throws)
        float hp = p.getHealth();
        if (hp < lastHealth - 0.5f) hurtTick = now;
        if (Hostility.INSTANCE.observe(p, now)) hurtTick = now;    // a hit by a mob, even one absorption took
        lastHealth = hp;
        boolean hurt = now - hurtTick < ReflexRules.HURT_TICKS;
        io.github.mojolowjo.entropybot.threat.ThreatRuntime.INSTANCE.tick(mc, p, now, hurt);     // B2: 1 Hz reach search
        Motor.INSTANCE.tick(mc, p, now, reflex != Reflex.NONE);      // 0.27.0: the running short move (never throws)
        // AA_CLEANUP 4: the lava/fire reflex is a true reflex: it runs under AA too (the milk and potions went)
        if (reflex != Reflex.EATING && fire.tick(mc, p, now, reflex == Reflex.FIGHTING || duel.active())) {     // 0.23.6
            if (moves.active()) mc.options.keyJump.setDown(false);      // review: the fire reflex drives this tick
            return;
        }
        // AA_CLEANUP 4: a danger move (AA's or the owner's) runs; only a creeper this close takes over (the creeper flee)
        if (moves.active()) {
            Threat c = defence ? nearestCreeper(mc, p, CreeperSprint.START) : null;
            if (c == null || !p.hasLineOfSight(c.e)) {
                moves.tick(mc, p, now);
                return;
            }
            moves.stop("a creeper within " + (int) CreeperSprint.START + ": the creeper flee");
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
        // 0.32.1 (P1, the owner's creeper rule; Haiku run: cover from a spider walked the bot into a creeper 13 NE): a
        // creeper within CREEPER_OVERRIDE beats any other target, cover, high ground or job: it is the target now
        Threat cr = defence ? nearestCreeper(mc, p, CREEPER_OVERRIDE) : null;
        // review 4: a creeper behind a wall or in a pit (no sight) or one that hasn't come closer in 8 s doesn't override
        // (the flee loop); within 4 it always does. The flee itself keeps off drops, lava and fire (sprint check, Baritone).
        if (cr != null && cr.d > 4 && (!p.hasLineOfSight(cr.e) || !creeperClosing(cr))) cr = null;
        if (cr != null && (t == null || !t.creeper)) {
            t = cr;
            if (cr.d < ReflexRules.CREEPER_RUN_TO) forceFleeUntil = Math.max(forceFleeUntil, now + 20);
        }
        if (creeperProbe && cr != null) LOG.info("[entropybot] creeper probe: tick {} creeper {} at {} | target {} {} | reflex {} | hp {}",
                now, cr.e.getId(), Math.round(cr.d * 10) / 10.0, t == null ? "none" : t.id, t == null ? "" : Math.round(t.d * 10) / 10.0,
                reflex, Math.round(hp));
        // C7 escort: what threatens the guarded player comes first, unless something is at the bot's own throat
        // (or the owner pointed at a target with `attack`, C1)
        if (escort.active()) escort.watch(mc);
        Entity ge = ft == null && defence && escort.active() ? escort.threat(mc, p) : null;
        if (ge != null && (t == null || t.d > ReflexRules.REACH || t.e == ge)) {
            escortFight(mc, p, threatOf(p, ge, hurt), hurt);
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
        // AA_CLEANUP 4: the duel is the owner's creeper setting (a verb), never under AA
        if (t != null && t.creeper && hp > ReflexRules.RETREAT_AT && now >= forceFleeUntil && !io.github.mojolowjo.entropybot.aa.door.Door.aaOn()
                && CreeperSprint.duelAfter(duel.mode(), bigCreeperMargin(p))
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
            target = t.id;
            targetDist = t.d;
            urgent = hurt || t.d < ReflexRules.URGENT;
            // AA_CLEANUP 4: no hand-rule retreat, cover, lit spot or high ground: a creeper is fled, a forced target
            // (the owner's or AA's attack) fought; under AA anything else gets only the hit response (AA's danger desire
            // prices fight vs flee vs home vs cover vs dig in vs pillar); with AA off the old self-defence fights
            if (t.creeper) creeper(mc, p, t);
            else if (ft == null && !io.github.mojolowjo.entropybot.aa.door.Door.aaOn() && hp <= ReflexRules.RETREAT_AT && aaOffMove(mc, p, hurt)) return;
            else if (ft != null || !io.github.mojolowjo.entropybot.aa.door.Door.aaOn()) fight(mc, p, t);
            else hitResponse(mc, p, t, hurt);
            return;
        }
        if (reflex == Reflex.FIGHTING || reflex == Reflex.FLEEING) settle("no monsters left");
        if (reflex == Reflex.EATING) {
            eatStep(mc, p);
            return;
        }
        if (escort.active()) escort.idle(mc, p, engine, now);
        if (now >= eatCooldownUntil && mc.screen == null && p.containerMenu == p.inventoryMenu &&
                ((eatRequested && p.getFoodData().needsFood()) || ReflexRules.wantsMeal(p.getFoodData().getFoodLevel(), hp, bestNutrition(p)))) {
            startEating(mc, p);
        }
    }

    /** Every reflex ends here: keys up, Baritone back to whatever it was doing. */
    /** 0.33.1: holding jump because of the air reflex (released when it ends). */
    private boolean swimming;
    private long swimLogAt = -1000;
    /** 0.36.a1: the air reflex took over from Baritone (engine hold); deferred to it (logged once per dive). */
    private boolean airHeld, airDeferred;

    /** 0.33.1: head under water and sinking with nothing driving, or the air low: hold jump to reach the surface. */
    private void airWatch(Minecraft mc, LocalPlayer p) {
        // a screen open (chest, inventory): no input of ours; let go of a held jump
        if (mc.screen != null) { releaseSwim(mc); shore.reset(); return; }
        IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
        boolean drives = reflex != Reflex.NONE || (b != null && (b.getPathingBehavior().isPathing() || b.getPathingControlManager().mostRecentInControl().isPresent()));
        // review: an underwater dig/build/chest job sits between Baritone legs for a tick: it drives too (a plain walk doesn't)
        if (!drives) {
            try {
                var cmds = io.github.mojolowjo.entropybot.Core.INSTANCE.commands;
                drives = cmds != null && (cmds.chainRunning() || cmds.jobs.running() && !"travel".equals(cmds.jobs.job.type));
            } catch (RuntimeException e) {
                drives = false;
            }
        }
        // riding (a boat sunk, a mount under water): isUnderWater is false then; get off when the eyes are in water
        if (p.isPassenger()) {
            if (p.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) && p.getAirSupply() < p.getMaxAirSupply()) {
                LOG.info("[entropybot] air reflex: riding with my head under water (air {}/{}) - getting off", p.getAirSupply(), p.getMaxAirSupply());
                p.stopRiding();
            }
            releaseSwim(mc);
            shore.reset();
            return;
        }
        // 0.36.a1: our own EngineProcess hold (the takeover) counts as driving, so the takeover is kept until it ends
        ReflexRules.Air a = ReflexRules.air(p.isUnderWater(), p.isInWater(), p.getAirSupply(), p.getMaxAirSupply(), drives, airHeld);
        if (a == ReflexRules.Air.DEFER) {
            if (!airDeferred) LOG.info("[entropybot] air reflex: head under water at {} {} {}, air {}/{} - Baritone drives, leaving jump to it until air < {}",
                    (int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()), p.getAirSupply(), p.getMaxAirSupply(), p.getMaxAirSupply() / 3);
            airDeferred = true;
        } else if (!p.isUnderWater()) airDeferred = false;
        if (a == ReflexRules.Air.SWIM || a == ReflexRules.Air.TAKEOVER) {
            boolean take = a == ReflexRules.Air.TAKEOVER;
            if (take && !airHeld) {
                LOG.info("[entropybot] air reflex: air {}/{} at {} {} {} - taking over: Baritone paused, swimming up until the air is full",
                        p.getAirSupply(), p.getMaxAirSupply(), (int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()));
            } else if (!take && !swimming && now - swimLogAt > 200 && (swimLogAt = now) > 0) LOG.info("[entropybot] air reflex: head under water at {} {} {}, air {}/{} - swimming up",
                    (int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()), p.getAirSupply(), p.getMaxAirSupply());
            if (take && reflex == Reflex.NONE) engine.hold();   // Baritone stops digging/walking: no fight over jump or look
            airHeld = take;
            swimming = true;
            mc.options.keyJump.setDown(true);
        } else releaseSwim(mc);
        shore.tick(p, b, drives, now);
    }

    /** AA_CLEANUP 4: the shore watch lives in its own class (afloat and idle: swim to the nearest shore). */
    private final ShoreWatch shore = new ShoreWatch(this::whisper);

    /** Lets go of the air reflex's jump (every way out: the conditions gone, a screen, death, leaving, settle). */
    private void releaseSwim(Minecraft mc) {
        if (airHeld) {
            airHeld = false;
            // 0.36.a1: our takeover's hold only (a reflex that holds/overrides Baritone keeps its own)
            if (reflex == Reflex.NONE && engine.mode() == EngineProcess.Mode.HOLD) engine.release();
            LOG.info("[entropybot] air reflex: air back ({}) - Baritone resumes", mc != null && mc.player != null ? mc.player.getAirSupply() : -1);
        }
        if (!swimming) return;
        swimming = false;
        if (mc != null) mc.options.keyJump.setDown(false);
    }

    /** 0.36.a1: the air reflex holds jump (AA leaves the key alone then). */
    public boolean airSwimming() { return swimming; }

    private void settle(String why) {
        Minecraft mc = Minecraft.getInstance();
        releaseSwim(mc);
        duel.abort(why);
        cspr.abort(why);
        if (reflex == Reflex.FLEEING) stopMoving(mc);     // 0.23.2: safe, stop sprinting
        if (reflex == Reflex.EATING) mc.options.keyUse.setDown(false);
        if (reflex != Reflex.NONE) events.push("reflex", "done " + reflex.name().toLowerCase() + ": " + why, null);
        reflex = Reflex.NONE;
        target = null;
        urgent = false;
        Motor.INSTANCE.stop("reflex ended: " + why, now);     // 0.27.0
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
            best = new Threat(e, d, BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), e instanceof Creeper, k);
        }
        return best;
    }

    /** 0.32.1: a creeper this close is the target whatever else is near (the owner's rule). */
    static final double CREEPER_OVERRIDE = 10;
    /** 0.32.1: "debug creeper probe on|off": a log line every tick a creeper is within CREEPER_OVERRIDE. */
    public volatile boolean creeperProbe;

    /** The nearest live creeper within r (any line of sight: it hisses through walls too), or null. */
    private Threat nearestCreeper(Minecraft mc, LocalPlayer p, double r) {
        Threat best = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Creeper c) || !c.isAlive()) continue;
            double d = p.distanceTo(e);
            if (d > r || (best != null && d >= best.d)) continue;
            best = new Threat(e, d, "creeper", true, HostileRules.Kind.ENEMY);
        }
        return best;
    }

    private int watchCreeper = -1;
    private double watchCreeperBest;
    private long watchCreeperAt;

    /** review 4: the creeper came at least 0.5 closer within the last 8 s (a new one counts as closing). */
    private boolean creeperClosing(Threat cr) {
        if (cr.e.getId() != watchCreeper) {
            watchCreeper = cr.e.getId();
            watchCreeperBest = cr.d;
            watchCreeperAt = now;
            return true;
        }
        if (cr.d < watchCreeperBest - 0.5) {
            watchCreeperBest = cr.d;
            watchCreeperAt = now;
        }
        return now - watchCreeperAt <= 160;
    }

    private Threat threatOf(LocalPlayer p, Entity e, boolean hurt) {
        HostileRules.Kind k = Hostility.INSTANCE.kind(e, hurt);
        return new Threat(e, p.distanceTo(e), BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), e instanceof Creeper, k);
    }

    private void startDuel(Minecraft mc, LocalPlayer p, Threat t) {
        if (reflex == Reflex.EATING) stopEating(mc, "interrupted by a creeper");
        duel.start(mc, p, (Creeper) t.e, now);
        reflex = Reflex.FIGHTING;
        mc.options.keyShift.setDown(false);
        target = "creeper";
        targetDist = t.d;
        urgent = false;
        engine.hold();              // the duel drives the keys; Baritone stands by with its goal
    }

    /**
     * C7: a fight for the escorted player (an owner verb; refused under AA). A creeper gets the bow duel when
     * "defend creepers bow" (and the duel's own checks agree), else the bot stands between it and the player and
     * knocks it back; anything else is the ordinary fight (AA_CLEANUP 4: the retreat under 6 health went).
     */
    private void escortFight(Minecraft mc, LocalPlayer p, Threat t, boolean hurt) {
        escort.interrupted();
        if (reflex == Reflex.EATING) stopEating(mc, "interrupted by a " + t.id + " near " + escort.name());
        if (!escortFighting) {
            escortFighting = true;
            escort.countFight();
        }
        target = t.id;
        targetDist = t.d;
        urgent = hurt || t.d < ReflexRules.URGENT;
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
        if (duel.active()) duel.abort("creeper within " + (int) CreeperSprint.START + ": sprint");
        double ax = p.getX() - t.e.getX(), az = p.getZ() - t.e.getZ();
        net.minecraft.world.phys.Vec3 v = p.getDeltaMovement();
        boolean away = CreeperSprint.sprintingAway(p.isSprinting(), v.x, v.z, ax, az);
        boolean mayHit = duel.mode() != CreeperRules.Mode.FLEE && t.d <= ReflexRules.REACH && (Hostility.mayAttack(t.e) || forcedAllows(t.e));
        double[] esc = creeperEscape(mc, p, t.e);         // brain-b1: weighs every hostile, not just this creeper
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
            double[] esc = creeperEscape(mc, p, c);
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
        boolean sprint = ThreatFacts.sprint(true, p.getFoodData().getFoodLevel());
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

    /** 0.23.2: the blast a melee creeper duel should expect (a hit-and-back-off that fails at about 3 blocks). */
    static final double CREEPER_BLAST = 12;

    /** 0.23.2: a melee duel with a lone creeper only with a big margin: health above the retreat line after that blast (armour counted) is at least the buffer. */
    static boolean creeperDuelOk(double health, double maxHealth, int armor) {
        return health - ReflexRules.RETREAT_AT - ThreatFacts.taken(CREEPER_BLAST, armor) >= Math.max(4, 0.2 * maxHealth);
    }

    private static boolean bigCreeperMargin(LocalPlayer p) {
        return creeperDuelOk(p.getHealth() + p.getAbsorptionAmount(), p.getMaxHealth(), p.getArmorValue());
    }

    private void creeper(Minecraft mc, LocalPlayer p, Threat t) {
        if (t.d < CreeperSprint.SAFE) {         // 0.23.2: sprint, not a Baritone walk
            startCreeperSprint(mc, p, t);
            return;
        }
        begin(Reflex.FLEEING, mc, "avoiding a " + t.id);
        // one escape spot for 3 seconds: picking a new one every tick makes Baritone re-plan non-stop
        if ((t.d < ReflexRules.CREEPER_RUN || now < forceFleeUntil) && now >= fleeUntil) {
            int[] a = fleeHeading(mc, p, t.e, ReflexRules.CREEPER_RUN_TO);
            if (a == null) a = ReflexRules.awayFrom(p.getX(), p.getZ(), t.e.getX(), t.e.getZ(), ReflexRules.CREEPER_RUN_TO);   // cornered: straight away as before
            fleeGoal = new GoalXZ(a[0], a[1]);
            engine.override(fleeGoal);
            fleeUntil = now + 60;
        } else if (now < fleeUntil && fleeGoal != null && engine.mode() == EngineProcess.Mode.NONE) {
            engine.override(fleeGoal);      // a Baritone cancel (the bridge stopping a job) dropped it
        }
        if (ThreatFacts.sprint(p.getDeltaMovement().horizontalDistance() > 0.05, p.getFoodData().getFoodLevel()) && !p.isSprinting()) p.setSprinting(true);
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

    /**
     * The hit response, the true reflex under AA: hit and the attacker in reach = face it and strike back (fight). Anything
     * else is AA's danger desire (AA_CLEANUP 4: DangerOptions prices fight, flee, home, cover, dig in, pillar).
     */
    private void hitResponse(Minecraft mc, LocalPlayer p, Threat t, boolean hurt) {
        if (hurt && t.d <= ReflexRules.REACH) {
            fight(mc, p, t);
            return;
        }
        if (reflex == Reflex.FIGHTING || reflex == Reflex.FLEEING) settle("not hit and out of reach: AA's danger desire decides");
    }

    /** Live aa376: the run end on the heading with the least threat over every nearby hostile (FleeHeading), null = cornered. Never throws. */
    private int[] fleeHeading(Minecraft mc, LocalPlayer p, Entity from, double dist) {
        try {
            return io.github.mojolowjo.entropybot.threat.FleeHeading.choose(p.getX(), p.getZ(), fleeMobs(mc, p, from), dist);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] threat: flee heading failed, straight away instead: {}", e.toString());
            return from == null ? null : ReflexRules.awayFrom(p.getX(), p.getZ(), from.getX(), from.getZ(), dist);
        }
    }

    /** Every counted hostile near (creepers weigh 2), {@code from} always included. */
    private List<io.github.mojolowjo.entropybot.threat.FleeHeading.Mob> fleeMobs(Minecraft mc, LocalPlayer p, Entity from) {
        List<io.github.mojolowjo.entropybot.threat.FleeHeading.Mob> mobs = new java.util.ArrayList<>();
        Hostility h = Hostility.INSTANCE;
        boolean seenFrom = false;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || !(e instanceof LivingEntity le) || !le.isAlive()) continue;
            if (e != from && (p.distanceTo(e) > ReflexRules.lookRadius(true) || !h.kind(e, true).counts())) continue;
            seenFrom |= e == from;
            mobs.add(new io.github.mojolowjo.entropybot.threat.FleeHeading.Mob(e.getX(), e.getZ(), e instanceof Creeper ? 2 : 1));
        }
        if (!seenFrom && from != null) mobs.add(new io.github.mojolowjo.entropybot.threat.FleeHeading.Mob(from.getX(), from.getZ(), from instanceof Creeper ? 2 : 1));
        return mobs;
    }

    /**
     * brain-b1: the creeper sprint's heading: EscapeDir's walkable run around FleeHeading's least-threat heading over
     * every hostile (not straight away from the creeper alone), and never a run that passes a mob. Cornered or a
     * failure: straight away from the creeper as before. {dirX, dirZ, run}. Never throws.
     */
    private double[] creeperEscape(Minecraft mc, LocalPlayer p, Entity c) {
        try {
            var mobs = fleeMobs(mc, p, c);
            double[] dir = io.github.mojolowjo.entropybot.threat.FleeHeading.direction(p.getX(), p.getZ(), mobs, ReflexRules.CREEPER_RUN_TO);
            if (dir == null) return c == null ? ThreatRuntime.INSTANCE.escape(p, 0, 0) : ThreatRuntime.INSTANCE.escape(p, c);
            double[] esc = ThreatRuntime.INSTANCE.escape(p, dir[0], dir[1]);
            if (io.github.mojolowjo.entropybot.threat.FleeHeading.blocked(p.getX(), p.getZ(), mobs, esc[0], esc[1], ReflexRules.CREEPER_RUN_TO))
                return new double[]{dir[0], dir[1], esc[2]};
            return esc;
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] threat: creeper heading failed, straight away instead: {}", e.toString());
            return c == null ? ThreatRuntime.INSTANCE.escape(p, 0, 0) : ThreatRuntime.INSTANCE.escape(p, c);
        }
    }

    private long aaOffPricedAt = -1000;

    /**
     * 0.38.b9 (review: with AA off a low-health bot fought to the death): at or under the old retreat health, the same
     * priced pick AA uses (DangerOptions on DangerMoves.facts) runs flee, cover, pillar or burrow; home, fight or stay:
     * false (the fight goes on). Once a second at most; the pick is logged. Never throws.
     */
    private boolean aaOffMove(Minecraft mc, LocalPlayer p, boolean hurt) {
        if (now - aaOffPricedAt < 20) return false;
        aaOffPricedAt = now;
        try {
            Place b = base;
            var c = io.github.mojolowjo.entropybot.aa.DangerOptions.choose(moves.facts(mc, p, b == null ? null : new int[]{b.x, b.y, b.z}, b == null ? null : b.dim, hurt));
            if (c.score() < 0 || c.best() == null) return false;
            String r = switch (c.best().way()) {
                case FLEE -> moves.flee();
                case COVER -> moves.cover();
                case PILLAR -> moves.pillar();
                case DIG_IN -> moves.burrow();
                default -> null;
            };
            LOG.info("[entropybot] low health, aa off: {} -> {}", c.terms(), r == null ? "fight on" : r);
            if (r == null || !r.startsWith("ok")) return false;
            if (reflex == Reflex.FIGHTING) settle("low health: " + c.best().way().name().toLowerCase());
            return true;
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] low health, aa off: the danger pick failed, fighting: {}", e.toString());
            return false;
        }
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

    /** aa-food: the nutrition of the food the eat reflex would pick (0: none). */
    private int bestNutrition(LocalPlayer p) {
        int slot = bestFood(p);
        if (slot < 0) return 0;
        FoodProperties f = p.getInventory().getItem(slot).get(DataComponents.FOOD);
        return f == null ? 0 : f.nutrition();
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
            // nothing to eat on board: tell the owner (once) and look again in 10 s (AA_CLEANUP 4: the food run went; aa-food fetches)
            if (!noFood) events.push("reflex", "out of food (food " + p.getFoodData().getFoodLevel() + "/20)", null);
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
            // aa-food: the next bite only while it is not wasted (or the owner asked)
            if (p.getFoodData().needsFood() && bites < 10 && (eatRequested || ReflexRules.wantsMeal(p.getFoodData().getFoodLevel(), p.getHealth(), bestNutrition(p)))) {
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

    // ---- death and dimensions ----

    private void death(Minecraft mc, LocalPlayer p) {
        deadTicks++;
        if (deadTicks == 1) {
            if (reflex != Reflex.NONE) settle("died");
            moves.stop("died");
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
                moves.stop("entered " + dim);
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