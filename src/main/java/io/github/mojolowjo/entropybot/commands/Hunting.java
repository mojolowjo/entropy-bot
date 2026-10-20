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
        int noneLooks;
        String why;
        boolean out, saidOut;
        int[] start;
        int targetId = -1;
        int[] dropAt;
        /** AA_ROUND3 5: "at x z": where the animals were seen ({x, z}); arrived there; the visit noted in Sightings. */
        int[] at;
        boolean arrived, visited, triedHerd;
        long walkAt = -1;
        JobRequests.Request req;
    }

    /** AA_ROUND3 5: the visit's outcome for the sighting at the hunt's place (wanted type only). Never throws. */
    private static void visit(Run r, boolean found, int count) {
        if (r.at == null || r.visited) return;
        r.visited = true;
        if (r.want == null) return;
        try {
            var sg = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.sightings();
            var lv = Minecraft.getInstance().level;
            String dim = io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.dim();
            if (sg == null || lv == null || dim == null) return;
            var rec = sg.visit(dim, r.want, r.at[0], r.at[1], found, count, lv.getGameTime() * 50);
            LOG.info("[entropybot] hunt: the {} seen at {} {}: {}{}", r.want.replaceFirst("^minecraft:", ""), r.at[0], r.at[1],
                    found ? count + " there" : "none there (confidence " + io.github.mojolowjo.entropybot.sense.Sightings.MISS_CONF + ")", rec == null ? " (no sighting there)" : "");
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] hunt: sighting visit: {}", e.toString());
        }
    }

    private Run run;

    Hunting(Commands c) { this.c = c; }

    /**
     * The owner's setting; aa-food (live aaep1: 16 animals near, never hunted): never set (the default) counts as on
     * while AA plays, so its food options may hunt. The owner's explicit "hunting off" always wins.
     */
    boolean on() {
        JsonObject b = c.brainData();
        if (b.has("hunting")) return b.get("hunting").getAsBoolean();
        return c.aaRuntime != null && c.aaRuntime.on();
    }

    /** The owner said "hunting off" (AA's hunt option says so instead of hunting). */
    boolean ownerOff() {
        JsonObject b = c.brainData();
        return b.has("hunting") && !b.get("hunting").getAsBoolean();
    }

    /** AA_CLEANUP 2: who started the run (the door scope then; null = none). */
    io.github.mojolowjo.entropybot.aa.door.Driver driver;

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
            boolean inAreas = c.inAreas(dim, e.getBlockX(), e.getBlockZ());
            HuntRules.Target t = new HuntRules.Target(type, pet, e.hasCustomName(), pet, false, false, e instanceof AgeableMob am && am.isBaby(), inAreas);
            if (HuntRules.mayTake(t, allowed, null)) out.merge(type.substring(10), 1, Integer::sum);
        }
        return out;
    }

    /** "hunting [on|off]". */
    String setting(String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase();
        if (r.isEmpty()) return "hunting is " + (on() ? "on" : "off") + (c.brainData().has("hunting") ? "" : " (not set: on while aa plays)") + " - hunting on|off (the animals: kinds animals)";
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
            r.at = HuntRules.at(rest);
            r.started = Core.INSTANCE.tick();
            String reply = "started: hunting " + mob.replaceFirst("^minecraft:", "") + " until I have " + r.itemWant + " " + r.item.replaceFirst("^minecraft:", "") + " (hunt status)";
            r.req = c.requests.local("hunt", from, raw, reply, l);
            run = r;
            driver = io.github.mojolowjo.entropybot.aa.door.Door.current();     // AA_CLEANUP 2: its ticks run as its starter
            LOG.info("[entropybot] hunt: {} {} for {} x{} ({})", r.n, mob, r.item, r.itemWant, from);
            return new Chains.Reply(reply, r.req);
        }
        Object[] a = HuntRules.parse(rest);
        if (a == null) return Chains.Reply.now("usage: hunt <n> [cow|pig|chicken|sheep] [at x z] [out] | hunt <n> <mob> for <item> [count] [at x z] | hunt status");
        Run r = new Run();
        r.from = from;
        r.n = (Integer) a[0];
        r.want = (String) a[1];
        r.started = Core.INSTANCE.tick();
        r.out = HuntRules.outWord(HuntRules.withoutAt(rest));
        r.at = HuntRules.at(rest);
        r.start = p == null ? null : Jobs.here(p);
        String reply = "started: hunting until I have " + r.n + " raw " + (r.want == null ? "meat" : HuntRules.MEAT.get(r.want).substring(10)) + " (hunt status)";
        r.req = c.requests.local("hunt", from, raw, reply, l);
        run = r;
        driver = io.github.mojolowjo.entropybot.aa.door.Door.current();     // AA_CLEANUP 2: its ticks run as its starter
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
            // AA_ROUND3 5: an "at" hunt takes only animals within 16 of the place it was told (never "the exact cell")
            if (r.at != null && !HuntRules.atOk(r.at, e.getBlockX(), e.getBlockZ())) continue;
            double d = p.distanceTo(e);
            boolean pet = io.github.mojolowjo.entropybot.engine.Hostility.protectedMob(e);
            boolean inAreas = c.inAreas(dim, e.getBlockX(), e.getBlockZ());
            // the director (2026-10-19): an urgent "out" hunt may take animals up to 48 from its start, outside my areas
            if (!inAreas && r.start != null && HuntRules.outOk(r.out, Math.sqrt(Jobs.distSq(r.start, new int[]{e.getBlockX(), e.getBlockY(), e.getBlockZ()})))) {
                inAreas = true;
                if (!r.saidOut && HuntRules.MEAT.containsKey(type)) {
                    r.saidOut = true;
                    LOG.info("[entropybot] hunt: hunting outside my areas (up to {} from {})", HuntRules.OUT_R, Jobs.fmt(r.start));
                }
            }
            if (!inAreas && r.at != null) inAreas = true;            // the place's 16 are its own out-rule (as hunt out)
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
        if (r.at != null) {
            int[] me = Jobs.here(p);
            long dx = me[0] - r.at[0], dz = me[2] - r.at[1];
            if (!r.arrived && dx * dx + dz * dz <= (long) HuntRules.AT_ARRIVED * HuntRules.AT_ARRIVED) r.arrived = true;
            if (best != null) visit(r, true, seen);
            else if (r.visited) { /* some were there and taken: the usual idle rule below */ }
            else if (r.arrived) {
                visit(r, false, 0);
                end(r, "stopped: hunt: " + have(r, bag) + "/" + goal(r) + " - " + HuntRules.AT_NONE + " (" + r.at[0] + " " + r.at[1] + ")");
                return;
            } else {
                // walk to the place first (the goto is re-issued every 10 s while it is on the way)
                if (r.walkAt < 0 || now - r.walkAt >= 200) {
                    r.walkAt = now;
                    c.handle(r.from, "goto " + r.at[0] + " " + r.at[1], true, null);
                }
                r.why = "walking to " + r.at[0] + " " + r.at[1] + " where I saw them";
                r.idleSince = -1;
                r.noneLooks = 0;
                r.nextAt = now + 20;
                return;
            }
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
            // aa395: nothing of the kind in view at all, 3 looks in a row: the step ends at once (the 40 s watchdog came first)
            boolean none = outside == null && seen == 0;
            r.noneLooks = none ? r.noneLooks + 1 : 0;
            r.nextAt = now + (none ? 40 : 100);
            boolean ending = now - r.idleSince >= IDLE_END || io.github.mojolowjo.entropybot.vocab.HuntRules.noneEnds(r.noneLooks);
            // aa-r2fix (round 2: 3x "hunt 3 sheep for white_wool: I see no sheep" gave up with a herd remembered): before giving up,
            // go to the best herd of this kind Sightings remembers (the "go hunt <type> at x z" option), once
            if (ending && none && r.at == null && r.want != null && !r.triedHerd) {
                r.triedHerd = true;
                int[] herd = AaRuntime.herdsSeen(mc.level, p).get(r.want);
                if (herd != null) {
                    r.at = new int[]{herd[0], herd[1]};
                    r.arrived = false;
                    r.visited = false;
                    r.walkAt = -1;
                    r.idleSince = -1;
                    r.noneLooks = 0;
                    r.nextAt = now + 20;
                    LOG.info("[entropybot] hunt: no {} in view - going to the herd I saw at {} {} ({} away, confidence {}%)",
                            what, herd[0], herd[1], herd[2], herd[3]);
                    return;
                }
            }
            if (ending) {
                end(r, "stopped: hunt: " + have(r, bag) + "/" + goal(r) + " - " + r.why);
                return;
            }
            if ((now - r.idleSince) % 600 < 100) LOG.info("[entropybot] hunt: nothing to take: {}", r.why);
            return;
        }
        r.idleSince = -1;
        r.noneLooks = 0;
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
