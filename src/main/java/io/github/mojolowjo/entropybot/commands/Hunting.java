package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.vocab.HuntRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * 0.24.3 {@code hunting on|off} (commands.json "hunting", default off) and {@code hunt <n> [animal]}: with hunting on it
 * kills the allowed farm animals (the "animals" kind; HuntRules' filter: never tamed, named, owned, babies, villagers,
 * players) within 32 blocks inside the areas or the near-me zone, one at a time through the reflexes' forced attack
 * (the threat test still comes first: hostiles are fought before), walks over the drop spot, and stops at n raw meat
 * (or 10 minutes, or stop). Loader notes: vanilla entities (TamableAnimal/OwnableEntity via Hostility, AgeableMob,
 * AbstractVillager), nothing loader-specific.
 */
final class Hunting {
    private static final Logger LOG = LogUtils.getLogger();
    static final long MAX_TICKS = 10 * 60 * 20;
    /** 0.33.0: a minute with nothing I may take ends the hunt with the reason (it sat idle 6 min before). */
    static final long IDLE_END = 60 * 20;

    private final Commands c;

    private static final class Run {
        String from, want;
        /** Loot data ("hunt n mob for item [want]"): the item to stop at, its count; null = the raw-meat hunt. */
        String item;
        int itemWant;
        int n;
        long started, nextAt, idleSince = -1;
        String why;
        int targetId = -1;
        int[] dropAt;
        JobRequests.Request req;
    }

    private Run run;

    Hunting(Commands c) { this.c = c; }

    boolean on() {
        JsonObject b = c.brainData();
        return b.has("hunting") && b.get("hunting").getAsBoolean();
    }

    boolean running() { return run != null; }

    /** 0.30.5: the animals hunt may take within RANGE now (short kind -> count), the same filter as the hunt's pick. */
    Map<String, Integer> animalsNear(LocalPlayer p) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        Minecraft mc = Minecraft.getInstance();
        if (p == null || mc.level == null) return out;
        List<String> allowed = c.vocab.expand("animals");
        String dim = Storage.dim();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || !e.isAlive() || p.distanceTo(e) > HuntRules.RANGE) continue;
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            if (!HuntRules.MEAT.containsKey(type)) continue;
            boolean pet = io.github.mojolowjo.entropybot.engine.Hostility.protectedMob(e);
            boolean inAreas = c.inAreas(dim, e.getBlockX(), e.getBlockZ())
                    || (Core.INSTANCE.guard.core.nearBox() != null && Core.INSTANCE.guard.core.nearBox().contains(dim, e.getBlockX(), e.getBlockY(), e.getBlockZ()));
            HuntRules.Target t = new HuntRules.Target(type, pet, e.hasCustomName(), pet, false, false, e instanceof AgeableMob am && am.isBaby(), inAreas);
            if (HuntRules.mayTake(t, allowed, null)) out.merge(type.substring(10), 1, Integer::sum);
        }
        return out;
    }

    /** "hunting [on|off]". */
    String setting(String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase();
        if (r.isEmpty()) return "hunting is " + (on() ? "on" : "off") + " - hunting on|off (the animals: kinds animals)";
        if (!r.equals("on") && !r.equals("off")) return "usage: hunting on|off";
        c.brainData().addProperty("hunting", r.equals("on"));
        c.saved();
        return "ok: hunting " + r + (r.equals("on") ? " - hunt <n> [animal] (cows, pigs, chickens, sheep; never pets or named ones); gather food may hunt now" : "");
    }

    Chains.Reply command(String from, String rest, String raw, JobRequests.Listener l, LocalPlayer p) {
        if (rest != null && rest.trim().equalsIgnoreCase("status")) return Chains.Reply.now(run == null ? "hunt: not running; hunting is " + (on() ? "on" : "off") : status(p));
        if (!on()) return Chains.Reply.now(Hints.next("error: hunting is off", "hunting on (then hunt <n> [animal])"));
        Object[] f = rest != null && rest.contains(" for ") ? HuntRules.parseFor(rest) : null;
        if (rest != null && rest.contains(" for ") && f == null) return Chains.Reply.now("usage: hunt <n> <mob> for <item> [count]");
        if (run != null) return Chains.Reply.now("busy: " + status(p));
        if (f != null) {
            String mob = (String) f[1];
            var type = BuiltInRegistries.ENTITY_TYPE.getOptional(net.minecraft.resources.ResourceLocation.tryParse(mob)).orElse(null);
            if (type == null) return Chains.Reply.now("error: no mob " + mob);
            boolean friendly = type.getCategory().isFriendly();
            if (!friendly && !c.vocab.expand("animals").contains(mob))
                return Chains.Reply.now(Hints.next("error: " + mob.replaceFirst("^minecraft:", "") + " is hostile and not in the animals kind", "kinds animals add " + mob.replaceFirst("^minecraft:", "")));
            Run r = new Run();
            r.from = from;
            r.n = (Integer) f[0];
            r.want = mob;
            r.item = (String) f[2];
            r.itemWant = (Integer) f[3];
            r.started = Core.INSTANCE.tick();
            String reply = "started: hunting " + mob.replaceFirst("^minecraft:", "") + " until I have " + r.itemWant + " " + r.item.replaceFirst("^minecraft:", "") + " (hunt status)";
            r.req = c.requests.local("hunt", from, raw, reply, l);
            run = r;
            LOG.info("[entropybot] hunt: {} {} for {} x{} ({})", r.n, mob, r.item, r.itemWant, from);
            return new Chains.Reply(reply, r.req);
        }
        Object[] a = HuntRules.parse(rest);
        if (a == null) return Chains.Reply.now("usage: hunt <n> [cow|pig|chicken|sheep] | hunt <n> <mob> for <item> [count] | hunt status");
        Run r = new Run();
        r.from = from;
        r.n = (Integer) a[0];
        r.want = (String) a[1];
        r.started = Core.INSTANCE.tick();
        String reply = "started: hunting until I have " + r.n + " raw " + (r.want == null ? "meat" : HuntRules.MEAT.get(r.want).substring(10)) + " (hunt status)";
        r.req = c.requests.local("hunt", from, raw, reply, l);
        run = r;
        LOG.info("[entropybot] hunt: {} {} for {}", r.n, r.want, from);
        return new Chains.Reply(reply, r.req);
    }

    /** 0.33.0: for /wait's busy line; null when no hunt runs. */
    String statusText(LocalPlayer p) { return run == null ? null : status(p); }

    private String status(LocalPlayer p) {
        Run r = run;
        int have = p == null ? 0 : have(r, Gui.inventory(p));
        return "hunt: " + have + "/" + goal(r) + " " + what(r) + (r.why != null ? " - waiting: " + r.why : "");
    }

    /** What the run counts: the loot item, else the raw meat. */
    private static int have(Run r, Map<String, Integer> bag) {
        return r.item != null ? bag.getOrDefault(r.item, 0) : HuntRules.meat(bag, r.want);
    }

    private static int goal(Run r) { return r.item != null ? r.itemWant : r.n; }

    private static String what(Run r) {
        return r.item != null ? r.item.replaceFirst("^minecraft:", "") + " from " + r.want.replaceFirst("^minecraft:", "") : "raw " + (r.want == null ? "meat" : HuntRules.MEAT.get(r.want).substring(10));
    }

    String stop(String why) {
        Run r = run;
        if (r == null) return null;
        end(r, "stopped: hunt - " + why);
        return "hunt";
    }

    private void end(Run r, String text) {
        if (run != r) return;
        run = null;
        LOG.info("[entropybot] hunt: {}", text);
        c.requests.done(r.req.id, text);
    }

    /** Every 10 ticks. */
    void tick(LocalPlayer p) {
        Run r = run;
        if (r == null) return;
        long now = Core.INSTANCE.tick();
        Map<String, Integer> bag = Gui.inventory(p);
        if (have(r, bag) >= goal(r)) {
            end(r, "ok: hunted " + have(r, bag) + " " + what(r) + (r.item == null ? " - next: cook beef " + r.n : ""));
            return;
        }
        if (!on()) { end(r, "stopped: hunting was switched off"); return; }
        if (now - r.started > MAX_TICKS) { end(r, "stopped: hunt: " + have(r, bag) + "/" + goal(r) + " after 10 min (no more animals I may take?)"); return; }
        if (now < r.nextAt || p.isDeadOrDying()) return;
        var refl = Core.INSTANCE.reflexes;
        if (refl.forcing()) {                                         // the fight on the animal still runs: follow its spot
            Entity tg = r.targetId >= 0 ? Minecraft.getInstance().level.getEntity(r.targetId) : null;
            if (tg != null) r.dropAt = new int[]{tg.getBlockX(), tg.getBlockY(), tg.getBlockZ()};
            return;
        }
        // loot hunt of a hostile mob (owner-allowed): the fight rule holds for the whole run, not only at the plan
        if (r.item != null && !BuiltInRegistries.ENTITY_TYPE.getOptional(net.minecraft.resources.ResourceLocation.tryParse(r.want)).map(t -> t.getCategory().isFriendly()).orElse(true)
                && p.getHealth() < 14) { end(r, "stopped: hunt: health " + Math.round(p.getHealth()) + " - too low to fight " + r.want.replaceFirst("^minecraft:", "")); return; }
        if (refl.hold() || refl.reflex() == io.github.mojolowjo.entropybot.engine.Reflexes.Reflex.FIGHTING) return;   // hostiles first
        if (c.jobs.running()) return;                                 // the walk to the drops
        // 0.30.6: too hungry to sprint (food 6 or less) with something to eat in the bag (the last kill's raw beef, say):
        // eat first, then the next animal; the reflexes' meal holds the hunt until it is done
        if (p.getFoodData().getFoodLevel() <= 6 && refl.canEat(p)) {
            String ate = refl.eat();
            LOG.info("[entropybot] hunt: food {}/20, eating before the next animal: {}", p.getFoodData().getFoodLevel(), ate);
            if (!ate.startsWith("error: busy") && !ate.startsWith("error: already")) {      // (review) a held reflex: hunt on
                r.nextAt = now + (ate.startsWith("started") ? 40 : 100);
                return;
            }
        }
        Minecraft mc = Minecraft.getInstance();
        if (r.targetId >= 0) {
            // the last one died (or got away): walk over where it was for the drops, once
            Entity old = mc.level.getEntity(r.targetId);
            r.targetId = -1;
            if ((old == null || !old.isAlive()) && r.dropAt != null) {
                c.handle(r.from, "goto " + r.dropAt[0] + " " + r.dropAt[1] + " " + r.dropAt[2], true, null);
                r.dropAt = null;
                r.nextAt = now + 20;
                return;
            }
        }
        List<String> allowed = c.vocab.expand("animals");
        Entity best = null, outside = null;
        double bestD = Double.MAX_VALUE, outD = Double.MAX_VALUE;
        int seen = 0;
        String dim = Storage.dim();
        // 0.33.0 (Haiku run 2026-10-16: "hunt 3 cow" sat at 0/3 for 6 min, the cows just outside the small grove area):
        // every animal the client sees counts (no 32-block cap: the bot walks to it), and the nearest one I could take
        // but for the areas is kept for the reason
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || !e.isAlive()) continue;
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            boolean target = r.item != null ? type.equals(r.want) : HuntRules.MEAT.containsKey(type);
            if (!target && !(e instanceof Player) && !(e instanceof AbstractVillager)) continue;
            double d = p.distanceTo(e);
            boolean pet = io.github.mojolowjo.entropybot.engine.Hostility.protectedMob(e);
            boolean inAreas = c.inAreas(dim, e.getBlockX(), e.getBlockZ())
                    || (Core.INSTANCE.guard.core.nearBox() != null && Core.INSTANCE.guard.core.nearBox().contains(dim, e.getBlockX(), e.getBlockY(), e.getBlockZ()));
            HuntRules.Target t = new HuntRules.Target(type, pet, e.hasCustomName(), pet, e instanceof Player, e instanceof AbstractVillager,
                    e instanceof AgeableMob am && am.isBaby(), inAreas);
            if (target && (r.want == null || r.want.equals(type))) seen++;
            boolean friendly = e.getType().getCategory().isFriendly();
            if (!(r.item != null ? HuntRules.mayTakeFor(t, friendly, allowed, r.want) : HuntRules.mayTake(t, allowed, r.want))) {
                HuntRules.Target in = new HuntRules.Target(t.type(), t.tamed(), t.named(), t.owned(), t.player(), t.villager(), t.baby(), true);
                boolean couldIn = r.item != null ? HuntRules.mayTakeFor(in, friendly, allowed, r.want) : HuntRules.mayTake(in, allowed, r.want);
                if (!inAreas && couldIn && d < outD) { outD = d; outside = e; }
                continue;
            }
            if (d < bestD) { bestD = d; best = e; }
        }
        if (best == null) {
            String what = r.want == null ? "animal" : r.want.substring(10);
            int[] me = Jobs.here(p);
            r.why = outside != null
                    ? "the nearest " + BuiltInRegistries.ENTITY_TYPE.getKey(outside.getType()).getPath() + " I could take is at " + outside.getBlockX() + " " + outside.getBlockY() + " "
                      + outside.getBlockZ() + ", outside my areas - " + io.github.mojolowjo.entropybot.chop.ChopRules.areaCover(me[0], me[2], outside.getBlockX(), outside.getBlockZ(), 8, "pasture", c.areaNames()) + " takes it in"
                    : seen > 0 ? "I see " + seen + " " + what + (seen > 1 ? "s" : "") + " but none I may take (babies, named or tamed ones, or not in the animals kind)"
                    : "I see no " + what + " (I see about " + Minecraft.getInstance().options.getEffectiveRenderDistance() * 16 + " blocks around me) - walk me to some";
            if (r.idleSince < 0) r.idleSince = now;
            r.nextAt = now + 100;
            if (now - r.idleSince >= IDLE_END) {
                end(r, "stopped: hunt: " + have(r, bag) + "/" + goal(r) + " - " + r.why);
                return;
            }
            if ((now - r.idleSince) % 600 < 100) LOG.info("[entropybot] hunt: nothing to take: {}", r.why);
            return;
        }
        r.idleSince = -1;
        r.why = null;
        String name = BuiltInRegistries.ENTITY_TYPE.getKey(best.getType()).getPath();
        if (bestD > 20) {
            c.handle(r.from, "goto " + best.getBlockX() + " " + best.getBlockY() + " " + best.getBlockZ(), true, null);
            r.nextAt = now + 40;
            return;
        }
        String res = refl.forceAttack(best, name);
        r.targetId = best.getId();
        r.dropAt = new int[]{best.getBlockX(), best.getBlockY(), best.getBlockZ()};
        r.nextAt = now + 10;
        LOG.info("[entropybot] hunt: {}", res);
    }
}
