package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * rm-execution: the one runner of typed steps in the game ({@link Steps} over {@link StepHands}). AA's options run on
 * it (AaRuntime), and the owner's verbs {@code cut}, {@code gather} and {@code hunt} are thin wrappers that build the
 * same steps and run them here, their end going to the verb's request. Also the owner's {@code hunting on|off} setting
 * and the animals in view the food facts read. Client thread only. Never throws.
 */
public final class StepRun {
    private static final Logger LOG = LogUtils.getLogger();
    /** Blocks round me the food facts count animals in (game constant: the client sees about two chunks of mobs this way). */
    public static final int ANIMAL_RANGE = 32;

    private final Commands c;
    final Steps steps;
    private JobRequests.Request req;
    private String label;
    /** Who started the run (the door scope its ticks run in). */
    io.github.mojolowjo.entropybot.aa.door.Driver driver;

    StepRun(Commands c) {
        this.c = c;
        this.steps = new Steps(new StepHands(c));
    }

    boolean running() { return steps.running(); }

    /** Starts typed steps for {@code key} (Failures' key under AA); null when they run, else why not. */
    String start(String key, List<Step> s, String label) {
        if (steps.running()) return "busy: " + status();
        this.label = label;
        driver = io.github.mojolowjo.entropybot.aa.door.Door.current();
        req = null;
        Steps.Phase ph = steps.start(key, s, Core.INSTANCE.tick());
        LOG.info("[entropybot] steps: {} - {}", label, Step.label(s));
        return ph == Steps.Phase.FAILED ? "error: " + steps.why() : null;
    }

    /** Every tick: follows the run; an owner verb's end goes to its request. */
    void tick() {
        if (!steps.running()) return;
        try (var d = io.github.mojolowjo.entropybot.aa.door.Door.scope(driver)) {
            Steps.Phase ph = steps.tick(Core.INSTANCE.tick());
            if (ph != Steps.Phase.RUNNING) ended();
        }
    }

    private void ended() {
        String why = steps.why();
        LOG.info("[entropybot] steps: {} ended: {}", label, why);
        JobRequests.Request r = req;
        req = null;
        boolean done = steps.phase() == Steps.Phase.DONE;
        if (r != null) c.requests.done(r.id, done ? "ok: " + label + " - " + why : "error: " + label + ": " + why, !done);
    }

    /** Ends the run (the owner's stop, AA's switch, a death); its name or null when none ran. */
    String stop(String why) {
        if (!steps.running()) return null;
        steps.cancel(why);
        ended();
        return label;
    }

    String status() {
        return steps.running() ? label + ": " + steps.why() : "no steps run";
    }

    // ---- the owner's verbs: thin wrappers over the same steps ----

    /** "cut <n> [log block]": Baritone's tree mining until n more logs are in the bag. */
    Chains.Reply cut(String from, String rest, String raw, JobRequests.Listener l) {
        String[] w = rest == null ? new String[0] : rest.trim().split("\\s+");
        int n;
        try {
            if (w.length == 0 || w[0].isEmpty()) return Chains.Reply.now("usage: cut <n> [log block, e.g. oak_log]");
            n = Integer.parseInt(w[0]);
        } catch (NumberFormatException e) {
            return Chains.Reply.now("usage: cut <n> [log block, e.g. oak_log]");
        }
        String log = null;
        if (w.length > 1 && !w[1].equalsIgnoreCase("logs") && !w[1].equalsIgnoreCase("wood")) {
            log = logBlock(w[1]);
            if (log == null) return Chains.Reply.now("error: " + w[1] + " is no block the game tags as a log");
        }
        return verb(from, raw, l, "cut", List.of(new Step.ChopTree(null, n, log)), "cut " + n + (log == null ? " logs" : " " + log.replaceFirst("^minecraft:", "")));
    }

    /** "gather <item> <n>" (or "<n> <item>"): logs -> cut, a fuel no block gives -> cut + smelt, a mob drop -> hunt, else collect. */
    Chains.Reply gather(String from, String rest, String raw, JobRequests.Listener l, LocalPlayer p) {
        String[] w = rest == null ? new String[0] : rest.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (w.length != 2) return Chains.Reply.now("usage: gather <item> <n>");
        String item;
        int n;
        try {
            n = Integer.parseInt(w[1]);
            item = w[0];
        } catch (NumberFormatException e) {
            try {
                n = Integer.parseInt(w[0]);
                item = w[1];
            } catch (NumberFormatException e2) {
                return Chains.Reply.now("usage: gather <item> <n>");
            }
        }
        String id = item.contains(":") ? item : "minecraft:" + item;
        List<Step> s = new ArrayList<>();
        var it = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(id)).orElse(null);
        if (item.equals("logs") || item.equals("wood") || (it != null && it.builtInRegistryHolder().is(ItemTags.LOGS)))
            s.add(new Step.ChopTree(null, n, item.equals("logs") || item.equals("wood") ? null : logBlock(item)));
        else if (it == null) return Chains.Reply.now("error: no item " + item);
        else if (it.builtInRegistryHolder().is(ItemTags.COALS) && noBlockGives(id)) {
            // a fuel no block gives (charcoal): logs, then the furnace (the smelt verb takes the output's name)
            s.add(new Step.ChopTree(null, n, null));
            s.add(new Step.Smelt(item.replaceFirst("^minecraft:", ""), n, false));
        } else {
            String mob = mobDropping(id);
            if (mob != null) s.add(new Step.HuntAt(mob, n, null, id, n));
            else s.add(new Step.Collect(id, n));
        }
        return verb(from, raw, l, "gather", s, "gather " + n + " " + item);
    }

    /** "hunt <n> [animal] [for <item> <count>] [at x z]". */
    Chains.Reply hunt(String from, String rest, String raw, JobRequests.Listener l, LocalPlayer p) {
        if (!on()) return Chains.Reply.now(Hints.next("error: hunting is off", "hunting on (then hunt <n> [animal])"));
        String r = rest == null ? "" : rest.trim();
        String[] w = r.split("\\s+");
        if (w.length == 1 && !w[0].isEmpty()) {
            // no animal named: the one with the most head in view
            String most = null;
            int best = 0;
            for (var e : animalsNear(p).entrySet()) if (e.getValue() > best) { best = e.getValue(); most = e.getKey(); }
            if (most == null) return Chains.Reply.now("error: no animal I may take in view - walk me to some");
            r = r + " " + most;
        }
        Step s = PlanLines.one("hunt " + r);
        if (!(s instanceof Step.HuntAt h)) return Chains.Reply.now("usage: hunt <n> [animal] [for <item> <count>] [at x z]");
        return verb(from, raw, l, "hunt", List.of(h), h.text());
    }

    private Chains.Reply verb(String from, String raw, JobRequests.Listener l, String kind, List<Step> s, String label) {
        if (steps.running()) return Chains.Reply.now("busy: " + status() + " (pm \"stop\" first)");
        String err = start(kind + " " + from, s, label);
        if (err != null) return Chains.Reply.now(err);
        String reply = "started: " + label + " (" + Step.label(s) + ")";
        req = c.requests.local(kind, from, raw, reply, l);
        return new Chains.Reply(reply, req);
    }

    /** An item the game tags as a log (minecraft:logs). */
    static boolean isLogItem(String id) {
        var it = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(id)).orElse(null);
        return it != null && it.builtInRegistryHolder().is(ItemTags.LOGS);
    }

    /** The block id of a log word ("oak", "oak_log", "minecraft:oak_log"), or null when the game tags none as a log. */
    static String logBlock(String word) {
        String w = word.contains(":") ? word : "minecraft:" + word;
        for (String id : new String[]{w, w + "_log"}) {
            var b = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(id)).orElse(null);
            if (b != null && b.defaultBlockState().is(BlockTags.LOGS)) return id;
        }
        return null;
    }

    /** No block's loot gives the item (the loot book read for this world; none read: true). */
    private boolean noBlockGives(String id) {
        var book = c.aaRuntime == null ? null : c.aaRuntime.currentLootBook();
        return book == null || book.blocksDropping(id).isEmpty();
    }

    /** The mob whose loot gives the item (ItemWorth.mobFood: the food mobs), or null. */
    static String mobDropping(String item) {
        for (var e : io.github.mojolowjo.entropybot.aa.brain.ItemWorth.mobFood().entrySet()) if (e.getValue().equals(item)) return e.getKey();
        return null;
    }

    // ---- the owner's hunting setting and the animals in view ----

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

    /** "hunting [on|off]". */
    String setting(String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        if (r.isEmpty()) return "hunting is " + (on() ? "on" : "off") + (c.brainData().has("hunting") ? "" : " (not set: on while aa plays)") + " - hunting on|off (the animals: kinds animals)";
        if (!r.equals("on") && !r.equals("off")) return "usage: hunting on|off";
        c.brainData().addProperty("hunting", r.equals("on"));
        c.saved();
        return "ok: hunting " + r + (r.equals("on") ? " - hunt <n> [animal] (never pets, named ones or babies)" : "");
    }

    /** The animals a hunt may take within {@link #ANIMAL_RANGE} (short kind -> count): food mobs in the animals kind, no pets, named ones or babies. */
    Map<String, Integer> animalsNear(LocalPlayer p) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        Minecraft mc = Minecraft.getInstance();
        if (p == null || mc.level == null) return out;
        List<String> allowed = c.vocab.expand("animals");
        var food = io.github.mojolowjo.entropybot.aa.brain.ItemWorth.mobFood();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || !e.isAlive() || p.distanceTo(e) > ANIMAL_RANGE) continue;
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            if (!food.containsKey(type) || !allowed.contains(type)) continue;
            if (io.github.mojolowjo.entropybot.engine.Hostility.protectedMob(e) || e.hasCustomName() || (e instanceof AgeableMob am && am.isBaby())) continue;
            out.merge(type.substring(type.indexOf(':') + 1), 1, Integer::sum);
        }
        return out;
    }
}
