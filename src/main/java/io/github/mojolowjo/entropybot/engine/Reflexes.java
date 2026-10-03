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
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import org.slf4j.Logger;

/**
 * The bot's reflexes in Java (B2, docs/BOT_PLAN.md 5.8): eat, fight monsters, run from creepers, retreat
 * home at low health, respawn, and leave a denied dimension. They run under every job; while one runs,
 * {@link #hold()} is true and the bridge's jobs hold still. Moving goes through {@link EngineProcess},
 * so a walk the bridge started keeps its goal and carries on afterwards.
 */
public final class Reflexes {
    private static final Logger LOG = LogUtils.getLogger();

    public enum Reflex { NONE, EATING, FIGHTING, FLEEING, RETREATING }

    /** A remembered spot from the bridge's notes (the base, the /home landing). */
    public record Place(int x, int y, int z, String dim) {
        static Place of(JsonObject o) {
            if (o == null || !o.has("x") || !o.has("y") || !o.has("z")) return null;
            return new Place(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                    o.has("dim") ? o.get("dim").getAsString() : "minecraft:overworld");
        }
    }

    private record Threat(Entity e, double d, String id, boolean creeper) {}

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
    private Goal fleeGoal, retreatGoal;
    // retreating
    private long retreatStart, calmSince, homeSentAt = -100000;
    private Place retreatTo;
    private int[] fleeTo;
    private double lastX, lastY, lastZ;
    // places the bridge tells us about
    private volatile Place base, home;
    // death
    private int deadTicks;
    // dimension
    private String lastDim, deniedDim;
    private long deniedAt;

    public Reflexes(EventRing events, EngineProcess engine) {
        this.events = events;
        this.engine = engine;
    }

    public boolean hold() { return reflex != Reflex.NONE; }

    public Reflex reflex() { return reflex; }

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
        if (bestFood(p) < 0) return "error: no food in inventory";
        eatRequested = true;
        eatCooldownUntil = 0;
        return "started: eating";
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
        o.addProperty("noFood", noFood);
        if (deniedDim != null) o.addProperty("deniedDim", deniedDim);
        o.addProperty("engine", engine.disabled() ? "off" : engine.mode().name().toLowerCase());
        return o;
    }

    private String statusText() {
        return switch (reflex) {
            case NONE -> "none";
            case EATING -> "eating";
            case FIGHTING -> "fighting " + target;
            case FLEEING -> "avoiding a " + target;
            case RETREATING -> "retreating from " + target + " (health " + Math.round(lastHealth) + ")";
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
        if (p.isDeadOrDying()) {
            death(mc, p);
            return;
        }
        deadTicks = 0;
        dimensionWatch(mc, p);
        float hp = p.getHealth();
        if (hp < lastHealth - 0.5f) hurtTick = now;
        lastHealth = hp;
        boolean hurt = now - hurtTick < ReflexRules.HURT_TICKS;

        if (reflex == Reflex.RETREATING) {
            retreat(mc, p, hurt);
            return;
        }
        Threat t = defence ? nearestThreat(mc, p, hurt) : null;
        if (t != null && t.creeper && t.d >= ReflexRules.CREEPER_RUN && now >= fleeUntil && !hurt) t = null;   // keep an eye on it, no more
        if (t != null) {
            if (reflex == Reflex.EATING) stopEating(mc, "interrupted by a " + t.id);
            target = t.id;
            targetDist = t.d;
            urgent = hurt || t.d < ReflexRules.URGENT;
            if (hp <= ReflexRules.RETREAT_AT) startRetreat(mc, p, t);
            else if (t.creeper) creeper(mc, p, t);
            else fight(mc, p, t);
            return;
        }
        if (reflex == Reflex.FIGHTING || reflex == Reflex.FLEEING) settle("no monsters left");
        if (reflex == Reflex.EATING) {
            eatStep(mc, p);
            return;
        }
        if (now >= eatCooldownUntil && mc.screen == null && p.containerMenu == p.inventoryMenu &&
                ((eatRequested && p.getFoodData().needsFood()) || ReflexRules.wantsMeal(p.getFoodData().getFoodLevel(), hp))) {
            startEating(mc, p);
        }
    }

    /** Every reflex ends here: keys up, Baritone back to whatever it was doing. */
    private void settle(String why) {
        Minecraft mc = Minecraft.getInstance();
        if (reflex == Reflex.EATING) mc.options.keyUse.setDown(false);
        if (reflex != Reflex.NONE) events.push("reflex", "done " + reflex.name().toLowerCase() + ": " + why, null);
        reflex = Reflex.NONE;
        target = null;
        urgent = false;
        engine.release();
    }

    // ---- fighting ----

    private Threat nearestThreat(Minecraft mc, LocalPlayer p, boolean hurt) {
        Threat best = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || !(e instanceof LivingEntity le) || !le.isAlive() || !(e instanceof Enemy)) continue;
            // endermen, zombified piglins...: left alone unless something just hit the bot
            if (e instanceof NeutralMob && !hurt) continue;
            double d = p.distanceTo(e);
            if (d > ReflexRules.lookRadius(hurt) || (best != null && d >= best.d)) continue;
            boolean seen = hurt || d <= 2.5 || p.hasLineOfSight(e);
            if (!ReflexRules.counts(d, hurt, seen)) continue;
            best = new Threat(e, d, BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), e instanceof Creeper);
        }
        return best;
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
            if (p.getAttackStrengthScale(0f) >= 0.9f) {
                mc.gameMode.attack(p, t.e);
                p.swing(InteractionHand.MAIN_HAND);
            }
        } else if (now % 20 == 0 || engine.mode() != EngineProcess.Mode.OVERRIDE) {
            // close the distance (skeletons shoot from range)
            engine.override(new GoalNear(t.e.blockPosition(), 1));
        }
    }

    private void creeper(Minecraft mc, LocalPlayer p, Threat t) {
        begin(Reflex.FLEEING, mc, "avoiding a " + t.id);
        // one escape spot for 3 seconds: picking a new one every tick makes Baritone re-plan non-stop
        if (t.d < ReflexRules.CREEPER_RUN && now >= fleeUntil) {
            int[] a = ReflexRules.awayFrom(p.getX(), p.getZ(), t.e.getX(), t.e.getZ(), ReflexRules.CREEPER_RUN_TO);
            fleeGoal = new GoalXZ(a[0], a[1]);
            engine.override(fleeGoal);
            fleeUntil = now + 60;
        } else if (now < fleeUntil && fleeGoal != null && engine.mode() == EngineProcess.Mode.NONE) {
            engine.override(fleeGoal);      // a Baritone cancel (the bridge stopping a job) dropped it
        }
        // one that is already right here gets knocked back
        if (t.d <= ReflexRules.REACH && p.getAttackStrengthScale(0f) >= 0.9f) {
            holdWeapon(mc, p);
            mc.gameMode.attack(p, t.e);
            p.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** The best sword (else axe) into the selected hotbar slot. */
    private static void holdWeapon(Minecraft mc, LocalPlayer p) {
        var inv = p.getInventory();
        int best = -1, bestRank = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            int rank = s.getItem() instanceof SwordItem ? 2 : s.getItem() instanceof AxeItem ? 1 : 0;
            if (rank > bestRank) { bestRank = rank; best = i; }
        }
        if (best < 0 || best == inv.selected) return;
        if (best < 9) inv.selected = best;
        else if (p.containerMenu == p.inventoryMenu) mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, best, inv.selected, ClickType.SWAP, p);
    }

    // ---- retreating ----

    private void startRetreat(Minecraft mc, LocalPlayer p, Threat t) {
        if (reflex == Reflex.EATING) stopEating(mc, "retreating");
        reflex = Reflex.RETREATING;
        mc.options.keyShift.setDown(false);
        retreatStart = now;
        calmSince = now;
        retreatTo = null;
        fleeTo = null;
        String dim = Guard.dimOf(mc.level);
        Place b = base;
        if (b != null && b.dim.equals(dim) && dist2(p, b) > 64) {
            retreatTo = b;
            retreatGoal = new GoalNear(new BlockPos(b.x, b.y, b.z), 2);
        } else {
            fleeTo = ReflexRules.awayFrom(p.getX(), p.getZ(), t.e.getX(), t.e.getZ(), 16);
            retreatGoal = new GoalXZ(fleeTo[0], fleeTo[1]);
        }
        engine.override(retreatGoal);
        // far from home: /home as well, and keep moving (the server may have a warm-up)
        Place h = home;
        boolean tp = h != null && now - homeSentAt >= 1200 && (!h.dim.equals(dim) || dist2(p, h) > 16 * 16);
        if (tp) {
            homeSentAt = now;
            p.connection.sendCommand("home");
        }
        lastX = p.getX();
        lastY = p.getY();
        lastZ = p.getZ();
        events.push("reflex", "retreating from " + t.id + " (health " + Math.round(p.getHealth()) + ")" +
                (retreatTo != null ? " to the base" : " away from it") + (tp ? ", sent /home" : ""), null);
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
            settle("at the base");
            return;
        }
        if (fleeTo != null && Math.abs(p.getX() - fleeTo[0]) + Math.abs(p.getZ() - fleeTo[1]) <= 3) {
            settle("got away");
            return;
        }
        // a Baritone cancel (the bridge stopping its job) dropped the goal: ask again
        if (engine.mode() == EngineProcess.Mode.NONE && retreatGoal != null) engine.override(retreatGoal);
        Threat t = nearestThreat(mc, p, true);
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

    private static int foodScore(ItemStack s, float health) {
        if (s.isEmpty()) return -1;
        FoodProperties f = s.get(DataComponents.FOOD);
        if (f == null) return -1;
        boolean harmful = false;
        for (FoodProperties.PossibleEffect pe : f.effects()) {
            if (pe.effect().getEffect().value().getCategory() == MobEffectCategory.HARMFUL) harmful = true;
        }
        return ReflexRules.foodScore(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), f.nutrition(), f.saturation(), harmful, health);
    }

    /** Puts the best food in the selected slot; false when there is none. */
    private boolean holdFood(Minecraft mc, LocalPlayer p) {
        var inv = p.getInventory();
        int slot = bestFood(p);
        if (slot < 0) return false;
        if (slot == inv.selected) return true;
        if (slot < 9) inv.selected = slot;
        else if (p.containerMenu == p.inventoryMenu) mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, slot, inv.selected, ClickType.SWAP, p);
        else return false;
        return true;
    }

    private void startEating(Minecraft mc, LocalPlayer p) {
        if (!holdFood(mc, p)) {
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

    // ---- death and dimensions ----

    private void death(Minecraft mc, LocalPlayer p) {
        deadTicks++;
        if (deadTicks == 1) {
            if (reflex != Reflex.NONE) settle("died");
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
        if (deniedDim != null && now - deniedAt >= 40 && (now - deniedAt - 40) % 1200 == 0) p.connection.sendCommand("home");
    }
}
