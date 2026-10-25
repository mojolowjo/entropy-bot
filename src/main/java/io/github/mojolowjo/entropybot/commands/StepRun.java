package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.brain.Tuning;

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
    public static int animalRange() { return Tuning.i("stepRun.animalRange"); }

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
    String start(String key, List<Step> s, String label) { return start(key, s, label, null); }

    /** 0.39.z30: as above, an order's run re-planned through {@code replan} when a step fails on a changed fact. */
    String start(String key, List<Step> s, String label, Steps.Replanner replan) { return start(key, s, label, replan, null); }

    /** 0.39.z61: as above, with the plan's own counts per step (Chain#uses; null: none known). */
    String start(String key, List<Step> s, String label, Steps.Replanner replan, List<java.util.Map<String, Integer>> uses) {
        if (steps.running()) return "busy: " + status();
        this.label = label;
        driver = io.github.mojolowjo.entropybot.aa.door.Door.current();
        req = null;
        reportTo = null;
        Steps.Phase ph = steps.start(key, s, uses, Core.INSTANCE.tick(), replan);
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
        // 0.39.z24: an aa order's end is whispered to whoever gave it (one line) and logged
        String to = reportTo;
        reportTo = null;
        if (to != null) {
            var kind = io.github.mojolowjo.entropybot.screen.OrderEnd.kind(done, cancelling, why);
            String line = io.github.mojolowjo.entropybot.screen.OrderEnd.line(label, kind, why);
            LOG.info("[entropybot] order end to {}: {}", to, line);
            c.whisperEnd(to, line, !done);
        }
    }

    /** 0.39.z24: who hears this run's end (an aa order: its sender); null = AA's own run, no whisper. */
    String reportTo;
    private boolean cancelling;

    /** Ends the run (the owner's stop, AA's switch, a death); its name or null when none ran. */
    String stop(String why) {
        if (!steps.running()) return null;
        steps.cancel(why);
        cancelling = true;
        try { ended(); } finally { cancelling = false; }
        return label;
    }

    String status() {
        return steps.running() ? label + ": " + steps.why() : "no steps run";
    }

    // ---- the owner's verbs: thin wrappers over the same steps ----

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

    /** The animals a hunt may take within {@link #ANIMAL_RANGE} (short kind -> count): food mobs in the animals kind, no pets, named ones or babies. */
    Map<String, Integer> animalsNear(LocalPlayer p) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        Minecraft mc = Minecraft.getInstance();
        if (p == null || mc.level == null) return out;
        List<String> allowed = c.vocab.expand("animals");
        var food = io.github.mojolowjo.entropybot.aa.brain.ItemWorth.mobFood();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || !e.isAlive() || p.distanceTo(e) > animalRange()) continue;
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            if (!food.containsKey(type) || !allowed.contains(type)) continue;
            if (io.github.mojolowjo.entropybot.engine.Hostility.protectedMob(e) || e.hasCustomName() || (e instanceof AgeableMob am && am.isBaby())) continue;
            out.merge(type.substring(type.indexOf(':') + 1), 1, Integer::sum);
        }
        return out;
    }
}
