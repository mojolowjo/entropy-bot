package io.github.mojolowjo.entropybot.commands;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.commands.BridgeLink.Listener;
import io.github.mojolowjo.entropybot.commands.BridgeLink.Request;
import io.github.mojolowjo.entropybot.commands.Chains.Reply;
import io.github.mojolowjo.entropybot.engine.HotbarRules;
import io.github.mojolowjo.entropybot.engine.Reflexes;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.farm.FarmCommand;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import io.github.mojolowjo.entropybot.guard.Policy;
import io.github.mojolowjo.entropybot.io.BotFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The command core (B7a, docs/BOT_PLAN.md 5.10 and 8): PMs in and whispers out, the owner's and guests'
 * permissions, the dispatcher, cmd.json (bridge.ps1 and the dashboard) and state.json, chains, routines, rules,
 * the autominer and the death policy. The verbs the KubeJS bridge still does are handed to it through
 * {@link BridgeLink}; the rest is answered here, with the bridge's wording. The bridge's own PM handler stays in
 * the script for a rolled-back mod, and holds still while the mod lists "commands".
 */
public final class Commands implements Chains.Env {
    private static final Logger LOG = LogUtils.getLogger();
    static final String BRIDGE_DIR = "kubejs/bridge";

    private final Core core;
    public final BridgeLink bridge = new BridgeLink();
    private final JsonStore pmStore = new JsonStore("pm.json"), brainStore = new JsonStore("commands.json"), areaStore = new JsonStore("areas.json");
    private Chains chains;
    private PolicyCommands policy;
    private BotFiles bridgeFiles;
    private boolean ready;
    private final ArrayDeque<ChatParse.Pm> pmQueue = new ArrayDeque<>();
    private final ArrayDeque<String[]> outbox = new ArrayDeque<>();
    private final Set<String> refused = new HashSet<>();
    private String lastCmdId, lastResult, seenCmdId;
    private JsonObject restartOk;
    private long worldTicks;
    private boolean wasDead;
    private int errors;

    public final Jobs jobs;
    /** B7b part 2: the storage verbs (open/take/put/scan/deposit/where/trust/corpse/death/drop/rs/pots/go poi). */
    public final Storage storage;
    private String placesSent;

    public Commands(Core core) {
        this.core = core;
        this.jobs = new Jobs(core, this);
        this.storage = new Storage(core, this, jobs);
        this.crafting = new Crafting(core, this, jobs, storage);
        storage.crafting = crafting;
    }

    /** B7c: craft/smelt/get/need/recipe/kit/supplies/restock, farm and compact. */
    final Crafting crafting;

    /** What "restock" tops the bag up to: {id: n} (commands.json "supplies", moved over from memory.json once). */
    Map<String, Integer> suppliesMap() {
        Map<String, Integer> out = new LinkedHashMap<>();
        JsonObject b = brainStore.data();
        if (b.has("supplies") && b.get("supplies").isJsonObject()) {
            for (Map.Entry<String, com.google.gson.JsonElement> e : b.getAsJsonObject("supplies").entrySet()) out.put(e.getKey(), e.getValue().getAsInt());
        }
        return out;
    }

    void setSupplies(Map<String, Integer> s) {
        JsonObject o = new JsonObject();
        s.forEach(o::addProperty);
        brainStore.data().add("supplies", o);
        saved();
    }

    // ---- package B (2026-10-03): the hotbar layout and the tool policy, commands.json "hotbar" {"1":"pickaxe",...} and "toolOres" ----

    /** The layout {slot 1-9: kind or item id} (commands.json "hotbar"; the dashboard's settings page reads the same key). */
    Map<Integer, String> hotbarLayout() {
        Map<String, String> stored = new LinkedHashMap<>();
        JsonObject b = brainStore.data();
        if (b.has("hotbar") && b.get("hotbar").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : b.getAsJsonObject("hotbar").entrySet()) {
                if (e.getValue().isJsonPrimitive()) stored.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return HotbarRules.fromStrings(stored);
    }

    /** "iron" (the default) or "cheapest" (commands.json "toolOres"). */
    String toolOresSetting() {
        JsonObject b = brainStore.data();
        return HotbarRules.toolOres(b.has("toolOres") && b.get("toolOres").isJsonPrimitive() ? b.get("toolOres").getAsString() : null);
    }

    private void pushHotbar() {
        io.github.mojolowjo.entropybot.engine.Hotbar.set(hotbarLayout(), toolOresSetting());
    }

    private void setHotbar(Map<Integer, String> layout) {
        JsonObject o = new JsonObject();
        HotbarRules.toStrings(layout).forEach(o::addProperty);
        brainStore.data().add("hotbar", o);
        saved();
        pushHotbar();
    }

    /** An item name -> its id (any mod's namespace, minecraft's first), or null when no item is called that. */
    private static String resolveItemName(String q) {
        String exact = io.github.mojolowjo.entropybot.gui.GuiCore.normId(q);
        if (Storage.itemExists(exact)) return exact;
        List<String> ids = new ArrayList<>();
        for (net.minecraft.resources.ResourceLocation rl : net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet()) ids.add(rl.toString());
        String r = io.github.mojolowjo.entropybot.gui.GuiCore.resolve(q, ids);
        return Storage.itemExists(r) ? r : null;
    }

    /** "hotbar" | "hotbar set 1 pickaxe 2 sword 3 food 4 torch" | "hotbar clear <slot ...>|all". */
    String hotbarCommand(LocalPlayer p, String rest) {
        String t = rest == null ? "" : rest.trim().toLowerCase();
        Map<Integer, String> layout = new java.util.TreeMap<>(hotbarLayout());
        if (t.isEmpty()) return HotbarRules.show(layout, io.github.mojolowjo.entropybot.engine.Hotbar.items(p));
        if (t.equals("clear all")) {
            setHotbar(Map.of());
            return "ok: no hotbar layout any more - I leave the slots as they are";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^clear\\s+([1-9](?:[\\s,]+[1-9])*)$").matcher(t);
        if (m.find()) {
            for (String n : m.group(1).split("[\\s,]+")) layout.remove(Integer.parseInt(n));
            setHotbar(layout);
            return layout.isEmpty() ? "ok: no hotbar layout any more - I leave the slots as they are" : "ok: hotbar " + HotbarRules.describe(layout);
        }
        if (t.startsWith("set ")) {
            HotbarRules.Change c = HotbarRules.parseSet(t.substring(4), Commands::resolveItemName);
            if (c.err() != null) return c.err();
            layout.putAll(c.set());
            setHotbar(layout);
            return "ok: hotbar " + HotbarRules.describe(layout) + " - I put things in place when I'm idle";
        }
        return HotbarRules.USAGE;
    }

    /** "tools" | "tools ores iron|cheapest". */
    String toolsCommand(String rest) {
        String t = rest == null ? "" : rest.trim();
        if (t.isEmpty()) return HotbarRules.toolsText(toolOresSetting());
        String[] r = HotbarRules.toolsCommand(t);
        if (r[0] == null) return r[1];
        brainStore.data().addProperty("toolOres", r[0]);
        saved();
        pushHotbar();
        return r[1];
    }

    /** For BotAPI.toolPolicy (the bridge's tool choice and deposit): {hotbar, toolOres, supplies}. */
    public JsonObject toolPolicy() {
        JsonObject o = new JsonObject(), h = new JsonObject(), s = new JsonObject();
        HotbarRules.toStrings(hotbarLayout()).forEach(h::addProperty);
        suppliesMap().forEach(s::addProperty);
        o.add("hotbar", h);
        o.addProperty("toolOres", toolOresSetting());
        o.add("supplies", s);
        return o;
    }

    /** The hotbar keeper holds still while a job other than a walk or a wait runs (the mod's or the bridge's) or a reflex does. */
    private boolean hotbarBusy(long tick) {
        if (core.reflexes.hold()) return true;
        if (jobs.running() && !jobs.walking() && !"wait".equals(jobs.job.type)) return true;
        if (bridge.jobRunning(tick)) {
            String ty = BridgeLink.str(bridge.job(tick), "type");
            return !("travel".equals(ty) || "wait".equals(ty));
        }
        return false;
    }

    /** Where the bot last died: {x, y, z, dim, time}, or null. */
    JsonObject lastDeath() {
        JsonObject b = brainStore.data();
        return b.has("lastDeath") && b.get("lastDeath").isJsonObject() ? b.getAsJsonObject("lastDeath") : null;
    }

    public boolean ready() { return ready; }

    // ---- start ----

    /** Once, at the first tick in a world: the files, the move of the notes from the bridge's memory.json. */
    public String init(Minecraft mc, BotFiles files) {
        bridgeFiles = new BotFiles(mc.gameDirectory.toPath().resolve(BRIDGE_DIR));
        StringBuilder sb = new StringBuilder();
        sb.append(pmStore.load(files)).append("; ").append(brainStore.load(files)).append("; ").append(areaStore.load(files));
        JsonObject memory = null;
        if (!pmStore.existed() || !brainStore.existed() || !areaStore.existed()) memory = readBridgeJson(mc, "memory.json");
        if (!pmStore.existed()) {
            JsonObject old = readBridgeJson(mc, "pm.json");
            JsonObject d = pmStore.data();
            d.addProperty("owner", old != null && old.has("owner") ? old.get("owner").getAsString() : "mojolowjo");
            d.add("allowed", old != null && old.has("allowed") && old.get("allowed").isJsonArray() ? old.getAsJsonArray("allowed") : new JsonArray());
            d.addProperty("publicPrefix", old != null && old.has("publicPrefix") && !old.get("publicPrefix").isJsonNull() ? old.get("publicPrefix").getAsString() : "");
            pmStore.flush();
            sb.append("; pm.json moved over (owner ").append(d.get("owner").getAsString()).append(")");
        }
        if (!brainStore.existed() && memory != null) {
            int n = 0;
            for (String k : new String[]{"routines", "rules", "autominer", "run", "deaths", "parked", "deathPolicy", "reconnect", "lastDeath"}) {
                if (memory.has(k) && !memory.get(k).isJsonNull()) {
                    brainStore.data().add(k, memory.get(k).deepCopy());
                    n++;
                }
            }
            brainStore.flush();
            sb.append("; commands.json from memory.json (").append(n).append(" keys)");
        }
        if (!areaStore.existed() && memory != null && memory.has("policy") && memory.get("policy").isJsonObject()) {
            JsonObject p = memory.getAsJsonObject("policy");
            for (String k : new String[]{"areas", "protect", "strict"}) if (p.has(k)) areaStore.data().add(k, p.get(k).deepCopy());
            areaStore.flush();
            sb.append("; areas.json from memory.json");
        }
        // B7b: where /home lands is the mod's too (the bridge reads it through BotAPI.home)
        if (!brainStore.data().has("home")) {
            if (memory == null) memory = readBridgeJson(mc, "memory.json");
            if (memory != null && memory.has("home") && memory.get("home").isJsonObject()) {
                brainStore.data().add("home", memory.get("home").deepCopy());
                brainStore.flush();
                sb.append("; home from memory.json");
            }
        }
        // B7c: the supplies "restock" keeps are the mod's now
        if (!brainStore.data().has("supplies")) {
            if (memory == null) memory = readBridgeJson(mc, "memory.json");
            if (memory != null && memory.has("supplies") && memory.get("supplies").isJsonObject()) {
                brainStore.data().add("supplies", memory.get("supplies").deepCopy());
                brainStore.flush();
                sb.append("; supplies from memory.json");
            }
        }
        // package B: the hotbar layout and the ore-tool setting (the bridge's copies, moved over once)
        if (!brainStore.data().has("hotbar") || !brainStore.data().has("toolOres")) {
            if (memory == null) memory = readBridgeJson(mc, "memory.json");
            boolean moved = false;
            if (memory != null && !brainStore.data().has("hotbar") && memory.has("hotbar") && memory.get("hotbar").isJsonObject()) {
                brainStore.data().add("hotbar", memory.get("hotbar").deepCopy());
                moved = true;
            }
            if (memory != null && !brainStore.data().has("toolOres") && memory.has("toolOres") && memory.get("toolOres").isJsonPrimitive()) {
                brainStore.data().addProperty("toolOres", HotbarRules.toolOres(memory.get("toolOres").getAsString()));
                moved = true;
            }
            if (moved) {
                brainStore.flush();
                sb.append("; hotbar from memory.json");
            }
        }
        pushHotbar();
        sb.append("; hotbar ").append(HotbarRules.describe(hotbarLayout())).append(", ores with ").append(toolOresSetting());
        policy = new PolicyCommands(areaStore.data(), new GuardView(), () -> areaStore.changed(core.tick()));
        chains = new Chains(this, brainStore.data());
        sb.append("; policy: ").append(policy.apply());
        JsonObject b = brainStore.data();
        if (b.has("reconnect") && !b.get("reconnect").isJsonNull() && !b.get("reconnect").getAsBoolean()) core.reconnect.setOn(false);
        ready = true;
        return sb.toString();
    }

    private static JsonObject readBridgeJson(Minecraft mc, String name) {
        try {
            Path p = mc.gameDirectory.toPath().resolve(BRIDGE_DIR).resolve(name);
            if (!Files.exists(p)) return null;
            return JsonStore.parse(Files.readString(p, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    // ---- the tick ----

    /** Once per client tick (also outside a world: state.json then says so). Never throws. */
    public void tick(long tick) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!ready) return;
            if (mc.level == null || mc.player == null) {
                worldTicks = 0;
                if (tick % 20 == 0) writeState(mc, tick);
                return;
            }
            worldTicks++;
            LocalPlayer player = mc.player;
            bridge.tick(tick);
            boolean dead = player.isDeadOrDying();
            if (dead && !wasDead) noteDeath(mc, player);
            wasDead = dead;
            if (tick % 5 == 0) processPms();
            if (tick % 5 == 0) pollCommand(mc, tick);
            if (tick % 5 == 3) {
                try {
                    chains.stepChain();
                } catch (RuntimeException e) {
                    LOG.warn("[entropybot] chain: {}", e.toString());
                    chains.endChain("stopped: " + e);
                }
            }
            if (tick % 20 == 5) chains.deathTick(dead);
            if (!chains.resumeChecked() && worldTicks > 200 && (bridge.present(tick) || worldTicks > 600)) chains.resumeRun();
            if (tick % 100 == 55 && worldTicks > 400) {
                chains.rulesTick();
                chains.autominerTick();
            }
            if (tick % 25 == 0) sendOutbox(player);
            try {
                jobs.tick(player);
            } catch (RuntimeException e) {
                LOG.warn("[entropybot] job: {}", e.toString());
                jobs.finish("error: " + e);
            }
            if (tick % 10 == 7) {
                try {
                    String moved = io.github.mojolowjo.entropybot.engine.Hotbar.tick(mc, player, tick, hotbarBusy(tick));
                    if (moved != null) LOG.info("[entropybot] hotbar: {}", moved);
                } catch (RuntimeException e) {
                    LOG.warn("[entropybot] hotbar: {}", e.toString());
                }
            }
            if (tick % 20 == 10) {
                try {
                    storage.tick(player);
                } catch (RuntimeException e) {
                    LOG.warn("[entropybot] container notes: {}", e.toString());
                }
            }
            if (tick % 200 == 150) pushPlaces();
            if (tick % 20 == 0) writeState(mc, tick);
            brainStore.flushIfDue(tick);
            areaStore.flushIfDue(tick);
            pmStore.flushIfDue(tick);
        } catch (Throwable t) {
            errors++;
            if (errors <= 5 || errors % 1200 == 0) LOG.error("[entropybot] commands tick error #{}: {}", errors, t.toString());
        }
    }

    // ---- PMs ----

    /** From the chat event: a PM from an allowed player is queued; anyone else hears no once. */
    public void onChat(ClientChatReceivedEvent event) {
        try {
            if (!ready) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            String self = mc.player.getGameProfile().getName();
            String text = event.getMessage().getString();
            UUID uuid = event.getSender();
            String sender = null;
            if (uuid != null && !uuid.equals(new UUID(0, 0)) && mc.getConnection() != null) {
                PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
                if (info != null) sender = info.getProfile().getName();
            }
            String chatType = null;
            try {
                if (event.getBoundChatType() != null) chatType = event.getBoundChatType().chatType().unwrapKey().map(k -> k.location().toString()).orElse(null);
            } catch (RuntimeException ignored) {}
            String signed = null;
            if (event instanceof ClientChatReceivedEvent.Player pe) {
                try { signed = pe.getPlayerChatMessage().signedContent(); } catch (RuntimeException ignored) {}
            }
            ChatParse.Pm pm = ChatParse.parse(text, chatType, sender, signed, self, publicPrefix());
            if (pm == null) return;
            if (!isAllowed(pm.from())) {
                if (refused.add(pm.from().toLowerCase())) whisper(pm.from(), "Sorry, I only take orders from " + owner() + ".");
                LOG.info("[entropybot] ignored pm from {}: {}", pm.from(), pm.body());
                return;
            }
            synchronized (pmQueue) {
                pmQueue.add(pm);
            }
        } catch (Throwable t) {
            LOG.warn("[entropybot] chat handler failed: {}", t.toString());
        }
    }

    private void processPms() {
        ChatParse.Pm pm;
        synchronized (pmQueue) {
            pm = pmQueue.poll();
        }
        if (pm == null) return;
        LOG.info("[entropybot] pm from {}{}: {}", pm.from(), pm.verified() ? " (verified)" : " (name from the chat text)", pm.body());
        core.events.push("pm", pm.from() + ": " + pm.body(), null);
        Reply r;
        try {
            r = handle(pm.from(), pm.body(), false, pmListener(pm.from()));
        } catch (RuntimeException e) {
            r = Reply.now("error: " + e);
        }
        if (r.text() != null && !r.text().isEmpty()) whisper(pm.from(), r.text().replaceFirst("^ok: ", ""));
    }

    /** A forwarded PM: the bridge's answer is whispered, and so is the end of the job it started (as the bridge did). */
    private Listener pmListener(String to) {
        return new Listener() {
            @Override
            public void replied(Request r) {
                if (r.reply != null && !r.reply.isEmpty()) whisper(to, r.reply.replaceFirst("^ok: ", ""));
            }

            @Override
            public void finished(Request r) {
                if (!BridgeLink.quiet(r.doneMsg)) whisper(to, r.doneMsg.replaceFirst("^ok: ", ""));
            }
        };
    }

    public void whisper(String to, String text) {
        synchronized (outbox) {
            for (String part : Texts.whisperParts(text)) outbox.add(new String[]{to, part});
        }
    }

    private void sendOutbox(LocalPlayer player) {
        String[] m;
        synchronized (outbox) {
            m = outbox.poll();
        }
        if (m != null) player.connection.sendCommand("msg " + m[0] + " " + m[1]);
    }

    public String owner() {
        JsonObject d = pmStore.data();
        return d.has("owner") ? d.get("owner").getAsString() : "mojolowjo";
    }

    private String publicPrefix() {
        JsonObject d = pmStore.data();
        return d.has("publicPrefix") && !d.get("publicPrefix").isJsonNull() ? d.get("publicPrefix").getAsString() : "";
    }

    private List<String> allowed() {
        List<String> out = new ArrayList<>();
        JsonObject d = pmStore.data();
        if (d.has("allowed") && d.get("allowed").isJsonArray()) for (JsonElement e : d.getAsJsonArray("allowed")) out.add(e.getAsString());
        return out;
    }

    boolean isAllowed(String name) {
        if (name.equalsIgnoreCase(owner())) return true;
        for (String a : allowed()) if (a.equalsIgnoreCase(name)) return true;
        return false;
    }

    // ---- the dispatcher (the bridge's handlePm) ----

    /** Runs one command line. internal: a step of the running chain. */
    public Reply handle(String from, String message, boolean internal, Listener l) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return Reply.now(null);
        String[] vr = Texts.verbAndRest(message);
        String verb = vr[0], rest = vr[1], raw = vr[2];
        boolean isOwner = from.equalsIgnoreCase(owner());
        long tick = core.tick();
        if (!isOwner) {
            String r = Texts.guestRefusal(verb, rest, raw, owner());
            if (r != null) return Reply.now(r);
        }
        if (verb.equals("help") || verb.equals("?") || verb.isEmpty()) {
            if (!isOwner) return Reply.now("You can use: " + Texts.GUEST_HELP);
            for (String line : Texts.PM_HELP) whisper(from, line);
            return Reply.now(null);
        }
        if (verb.equals("memory")) return forward(from, raw, internal, l);
        if (verb.equals("status") || verb.equals("pos")) return Reply.now(statusLine(player, tick));
        if (verb.equals("inv") || verb.equals("inventory")) return Reply.now(inventorySummary(player));
        if (verb.equals("stop")) return Reply.now(stopAll());
        if (verb.equals("defend") || verb.equals("defense") || verb.equals("defence")) return Reply.now(setDefence(rest));
        if (verb.equals("routine") || verb.equals("routines")) return Reply.now(chains.routineCommand(rest));
        if (verb.equals("rule") || verb.equals("rules")) return Reply.now(chains.ruleCommand(verb.equals("rules") ? "list" : rest));
        if (verb.equals("autominer")) return Reply.now(chains.autominerCommand(rest));
        if (verb.equals("why")) return Reply.now(chains.whyCommand());
        if (verb.equals("resume")) return Reply.now(chains.resumeCommand());
        if (verb.equals("deaths") || (verb.equals("death") && rest.trim().toLowerCase().matches("^policy\\b.*"))) return Reply.now(chains.deathsCommand(rest));
        if (verb.equals("reconnect") && rest.trim().toLowerCase().matches("^(on|off)$")) return Reply.now(reconnectCommand(rest));
        if (verb.equals("queue")) return Reply.now(chains.chainStatus());
        if (verb.equals("ores")) return forward(from, raw, internal, l);
        if (verb.equals("stripmine") && rest.trim().toLowerCase().matches("^(status|ores( collect| list)?)$")) return forward(from, raw, internal, l);
        if (verb.equals("restart")) return Reply.now(restartCommand(from, rest));
        if (verb.equals("area") || verb.equals("protect") || verb.equals("unprotect") || verb.equals("guard")) {
            return Reply.now(policy.command(verb, rest, isOwner, owner(), hereOf(mc, from), posOf(mc, player)));
        }
        // chains ("a then b"), routines by name, repeat and run (never from inside a chain)
        if (verb.equals("repeat") || verb.equals("run") || chains.isRoutine(verb) || Texts.splitChain(raw).size() > 1) {
            if (internal) return Reply.now("error: a routine step cannot start another chain (routine names inside a chain are fine)");
            if (chains.running()) return Reply.now("busy: " + chains.chainStatus() + " (pm \"stop\" first)");
            JsonObject job = bridge.job(tick);
            if (bridge.jobRunning(tick)) {
                if (!"travel".equals(BridgeLink.str(job, "type"))) return Reply.now("busy: " + BridgeLink.str(job, "status") + " (pm \"stop\" first)");
                bridge.submit("endwalk", from, "", false, null, null, tick);
            }
            if (verb.equals("repeat")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+|forever)\\s+(.*)$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(rest);
                long rounds = Chains.FOREVER;
                String what = rest;
                if (m.find()) {
                    rounds = m.group(1).equalsIgnoreCase("forever") ? Chains.FOREVER : Math.max(1, Long.parseLong(m.group(1)));
                    what = m.group(2);
                }
                if (what.isEmpty()) return Reply.now("usage: repeat [times|forever] <routine or commands>");
                return Reply.now(chains.startChain(from, chains.isRoutine(what) ? what.toLowerCase() : "repeat", what, rounds));
            }
            if (verb.equals("run")) {
                if (!chains.isRoutine(rest)) return Reply.now("I have no routine called " + rest + " (PM \"routines\")");
                return Reply.now(chains.startChain(from, rest.toLowerCase(), rest, 1));
            }
            if (chains.isRoutine(verb) && Texts.splitChain(raw).size() == 1) return Reply.now(chains.startChain(from, verb, verb, 1));
            return Reply.now(chains.startChain(from, "chain", raw, 1));
        }
        // memory lookups and edits never interrupt a job (zone is still the bridge's)
        if (verb.matches("^(mark|setbase|sethome|forget|places)$")) return Reply.now(placeCommand(verb, rest, from, player));
        if (verb.equals("where")) return Reply.now(storage.where(player, rest));
        if (verb.equals("trust") || verb.equals("untrust")) return Reply.now(storage.trust(verb, rest));
        if (verb.equals("zone")) return forward(from, raw, internal, l);
        if (verb.equals("poi") || verb.equals("pois")) return Reply.now(poiCommand(rest, player, isOwner));
        if (verb.equals("caves")) return Reply.now(cavesCommand(rest));
        // B7c: lookups and settings that never interrupt a job
        if (verb.equals("need")) return Reply.now(crafting.need(player, rest));
        if (verb.equals("supplies")) return Reply.now(crafting.supplies(player, rest));
        if (verb.equals("recipe")) return Reply.now(crafting.recipe(player, rest));
        // package B: the hotbar layout and the tool policy (settings: instant, never "busy")
        if (verb.equals("hotbar")) return Reply.now(hotbarCommand(player, rest));
        if (verb.equals("tools")) return Reply.now(toolsCommand(rest));
        if (verb.equals("say")) {
            if (rest.isEmpty()) return Reply.now("say what?");
            String no = Texts.sayRefusal(rest, baritonePrefix());
            if (no != null) return Reply.now(no);
            player.connection.sendChat(rest);
            return Reply.now(null);
        }
        // eating never waits for a task: the reflex holds the task and it carries on afterwards
        if (verb.equals("eat")) return Reply.now(core.reflexes.eat());
        // a running routine owns the bot between its steps too
        if (!internal && chains.running() && Texts.isBuiltin(verb) && !verb.equals("find") && !verb.equals("recipe") && !verb.equals("close")) {
            return Reply.now("busy: " + chains.chainStatus() + " (pm \"stop\" first)");
        }
        if (verb.equals("allow") || verb.equals("deny") || verb.equals("allowed")) return Reply.now(allowCommand(verb, rest, isOwner));
        if ((verb.equals("b") || verb.equals("baritone")) && !isOwner) return Reply.now("only " + owner() + " can send raw Baritone commands");
        boolean known = Texts.MOD_JOB_VERBS.contains(verb) || Texts.MOD_VERBS.contains(verb) || Texts.BRIDGE_VERBS.contains(verb);
        if (!known) return Reply.now("unknown command \"" + verb + "\" - pm me: help");
        // "twerk" while twerking switches it off (a toggle, so not "busy"); farm settings are instant even mid-job
        if (verb.equals("twerk") && jobs.running() && jobs.job.type.equals("twerk")) return Reply.now(jobs.startTwerk(rest));
        if (verb.equals("farm") && FarmCommand.instant(rest)) return Reply.now(crafting.farm(player, rest));
        // everything below may replace a running walk, but not a running task (find, recipe and close never interrupt)
        boolean quiet = verb.equals("find") || verb.equals("recipe") || verb.equals("close");
        if (!quiet) {
            if (jobs.running() && !jobs.walking()) return Reply.now("busy: " + jobs.job.status + " (pm \"stop\" first)");
            JsonObject bj = bridge.job(tick);
            if (bridge.jobRunning(tick) && Texts.MOD_JOB_VERBS.contains(verb)) {
                if (!"travel".equals(BridgeLink.str(bj, "type"))) return Reply.now("busy: " + BridgeLink.str(bj, "status") + " (pm \"stop\" first)");
                bridge.submit("endwalk", from, "", false, null, null, tick);
            }
            jobs.replaceWalk();
        }
        if (Texts.MOD_JOB_VERBS.contains(verb) || Texts.MOD_VERBS.contains(verb)) {
            String r = modJob(verb, rest, from, player);
            return new Reply(r, jobs.attach("pm", from, raw, r, l));
        }
        return forward(from, raw, internal, l);
    }

    /** The jobs the mod runs itself (B7b part 1): walks, the teleport home, the bed, wait, twerk, find. */
    String modJob(String verb, String rest, String from, LocalPlayer player) {
        switch (verb) {
            case "come" -> {
                Player target = Jobs.findPlayer(from);
                if (target == null) return "I can't see you from here (I'm at " + Jobs.fmt(Jobs.here(player)) + "). PM me: goto x y z";
                int[] t = Jobs.here(target);
                String why = jobs.goalAllowed(t[0], t[1], t[2]);
                if (why != null) {
                    if (why.startsWith("next to a ")) return "you're " + why;
                    return "you're outside my areas (" + t[0] + " " + t[2] + ") - area add <name> here 30";
                }
                return jobs.startTravel("goto " + Jobs.fmt(t), "coming to " + from, null, null, false);
            }
            case "follow" -> {
                // the fence: the player has to be inside an area now, and the watch stops the follow when they leave
                String name = rest.isEmpty() ? from : rest;
                Player target = fenceOn() ? Jobs.findPlayer(name) : null;
                if (target != null) {
                    int[] t = Jobs.here(target);
                    if (jobs.goalAllowed(t[0], t[1], t[2]) != null) return name + " is outside my areas (" + t[0] + " " + t[2] + ") - area add <name> here 30";
                }
                String r = jobs.startTravel("follow player " + name, "following " + name, null, null, false);
                if (r.startsWith("ok") && fenceOn()) jobs.followWatch = new String[]{name, from};
                if (jobs.job != null) jobs.job.done = true;        // a follow never "arrives"; nobody waits for it
                return r;
            }
            case "goto" -> {
                if (!rest.matches("^-?\\d+ -?\\d+ -?\\d+$") && !rest.matches("^-?\\d+ -?\\d+$")) return "usage: goto x y z  (or goto x z)";
                return jobs.startTravel("goto " + rest, "going to " + rest, null, null, false);
            }
            case "spawn", "bed" -> { return jobs.startSetSpawn(player); }
            case "home" -> { return jobs.startHome(player); }
            case "go", "base" -> {
                if (verb.equals("go") && rest.trim().toLowerCase().matches("^poi\\s+\\d+$")) return storage.goPoi(player, Integer.parseInt(rest.trim().split("\\s+")[1]));
                String name = verb.equals("go") ? rest.toLowerCase() : "base";
                JsonObject pos = core.knowledge.places().get(name);
                if (pos == null) return "I have no place called " + name + " (see \"places\")";
                String dim = Jobs.dimOf(pos);
                int[] p = Jobs.pos(pos);
                if (!dim.equals(Guard.dimOf(player.level())) && !jobs.tpWorth(player, p, dim)) return name + " is in " + dim;
                return jobs.startTravel("goto " + Jobs.fmt(p), "going to " + name, p, dim, false);
            }
            case "wait" -> { return jobs.startWait(rest); }
            case "twerk" -> { return jobs.startTwerk(rest); }
            case "find" -> { return Jobs.findBlock(player, rest.isEmpty() ? "?" : rest); }
            // B7b part 2: the GUI toolkit and the storage errands
            case "open" -> { return storage.open(player, rest); }
            case "scan" -> { return storage.scan(player, rest); }
            case "deposit" -> { return storage.deposit(player, rest); }
            case "corpse" -> { return storage.corpse(); }
            case "death" -> { return storage.death(player); }
            case "rs" -> { return storage.rs(player, rest); }
            case "pots" -> { return storage.pots(player, rest); }
            case "take" -> { return Storage.transfer(player, rest, false); }
            case "put" -> { return Storage.transfer(player, rest, true); }
            case "close" -> { return Gui.closeVerb(player); }
            case "drop" -> { return Storage.drop(player, rest); }
            case "use" -> { return storage.use(player, rest); }
            case "wear", "equip" -> { return Gui.wearArmor(player); }
            case "where" -> { return storage.where(player, rest); }
            case "trust", "untrust" -> { return storage.trust(verb, rest); }
            // B7c: crafting, the furnace, fetching, the farm round and compact
            case "craft" -> { return crafting.craft(player, rest, null); }
            case "kit" -> { return crafting.kit(player, rest); }
            case "smelt" -> { return crafting.smelt(player, rest); }
            case "get" -> { return crafting.get(player, rest); }
            case "restock" -> { return crafting.restock(player); }
            case "farm" -> { return crafting.farm(player, rest); }
            case "compact" -> { return crafting.compact(player, rest, from); }
            case "recipe" -> { return crafting.recipe(player, rest); }
            case "need" -> { return crafting.need(player, rest); }
            case "supplies" -> { return crafting.supplies(player, rest); }
            default -> { return "unknown command \"" + verb + "\" - pm me: help"; }
        }
    }

    // ---- places (B7b: the mod's knowledge store; the bridge mirrors it into memory.json) ----

    static final java.util.Map<String, int[]> DIRS = java.util.Map.of("north", new int[]{0, -1}, "south", new int[]{0, 1}, "west", new int[]{-1, 0}, "east", new int[]{1, 0});

    /** Minecraft yaw: 0 = south, 90 = west, 180 = north, 270 = east. */
    static String dirFromYaw(float yaw) {
        double y = ((yaw % 360) + 360) % 360;
        return y >= 315 || y < 45 ? "south" : y < 135 ? "west" : y < 225 ? "north" : "east";
    }

    /** "x y z" -> those; else the sender when visible ("me" needs them visible: null), else the bot. */
    PolicyCommands.Pos resolvePos(Minecraft mc, String arg, String from) {
        String a = arg == null ? "" : arg.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(-?\\d+) (-?\\d+) (-?\\d+)$").matcher(a);
        if (m.find()) return new PolicyCommands.Pos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Guard.dimOf(mc.level));
        if (from != null) {
            Player p = Jobs.findPlayer(from);
            if (p != null) return posOf(mc, p);
            if (a.equals("me")) return null;
        }
        return posOf(mc, mc.player);
    }

    String placeCommand(String verb, String rest, String from, LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        List<String> parts = Texts.words(rest);
        java.util.Map<String, JsonObject> places = core.knowledge.places();
        if (verb.equals("mark") || verb.equals("setbase")) {
            String name = verb.equals("setbase") ? "base" : (parts.isEmpty() ? "" : parts.get(0).toLowerCase());
            if (!name.matches("^[a-z0-9_-]{1,24}$")) return "usage: mark <name> [x y z]";
            // a direction word makes any place a mine ("mark deepmine north": "mine strip ... at deepmine")
            List<String> args = new ArrayList<>();
            String dirWord = null;
            for (String a : parts.subList(Math.min(1, parts.size()), parts.size())) {
                if (DIRS.containsKey(a.toLowerCase())) dirWord = a.toLowerCase();
                else args.add(a);
            }
            PolicyCommands.Pos pos = resolvePos(mc, verb.equals("setbase") ? rest : String.join(" ", args), from);
            if (pos == null) return "I can't see you - come closer or give coordinates";
            String dir = null;
            if (name.equals("mine") || dirWord != null) {
                Player who = from != null ? Jobs.findPlayer(from) : null;
                dir = dirWord != null ? dirWord : dirFromYaw((who != null ? who : player).getYRot());
            }
            // "mark food" standing next to a chest: the chest itself (the food run opens it)
            int[] snapped = null;
            if (name.equals("food")) {
                snapped = nearestContainer(mc, pos, 2);
                if (snapped != null) pos = new PolicyCommands.Pos(snapped[0], snapped[1], snapped[2], pos.dim());
            }
            JsonObject o = new JsonObject();
            o.addProperty("x", pos.x());
            o.addProperty("y", pos.y());
            o.addProperty("z", pos.z());
            o.addProperty("dim", pos.dim());
            if (dir != null) o.addProperty("dir", dir);
            putPlace(name, o);
            return "remembered " + name + " at " + pos.x() + " " + pos.y() + " " + pos.z()
                    + (dir != null ? ", digging " + dir + (name.equals("mine") ? " (PM \"stripmine\" to start)" : " (\"mine strip <ores> at " + name + "\")") : "")
                    + (name.equals("food") ? (snapped != null ? " (the chest there): when I run out of food I fetch some from it" : " - no chest within 2 blocks of that spot, stand right next to it") : "");
        }
        if (verb.equals("forget")) {
            String name = rest.toLowerCase();
            if (!places.containsKey(name)) return "I have no place called " + name;
            putPlace(name, null);
            return "forgot " + name;
        }
        if (verb.equals("places")) {
            List<String> out = new ArrayList<>();
            places.forEach((k, v) -> out.add(k + " " + Jobs.fmt(Jobs.pos(v))));
            return out.isEmpty() ? "no places yet - PM \"mark base\" where you want my base" : String.join(" | ", out);
        }
        if (verb.equals("sethome")) {
            // the server's homes: "/sethome home" sets it, "/home" teleports there
            player.connection.sendCommand("sethome home");
            int[] me = Jobs.here(player);
            setHome(me, Guard.dimOf(player.level()));
            return "ok: set my home at " + Jobs.fmt(me) + " (/sethome home) - \"home\" teleports me here, and long trips back teleport first";
        }
        return "unknown command \"" + verb + "\"";
    }

    void putPlace(String name, JsonObject o) {
        JsonObject ch = new JsonObject(), pl = new JsonObject();
        pl.add(name, o == null ? JsonNull.INSTANCE : o);
        ch.add("places", pl);
        core.knowledge.put(ch.toString(), core.tick());
    }

    /** The nearest chest or barrel (not an ender chest) within r blocks (and 2 up or down) of pos, or null. */
    static int[] nearestContainer(Minecraft mc, PolicyCommands.Pos pos, int r) {
        int[] best = null;
        long bestD = Long.MAX_VALUE;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) for (int dy = -Math.min(r, 8); dy <= Math.min(r, 8); dy++) for (int dz = -r; dz <= r; dz++) {
            q.set(pos.x() + dx, pos.y() + dy, pos.z() + dz);
            String id = mc.level.getBlockState(q).getBlock().getDescriptionId();
            if (id.contains("ender_chest") || (!id.contains("chest") && !id.contains("barrel"))) continue;
            long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
            if (d < bestD) {
                bestD = d;
                best = new int[]{pos.x() + dx, pos.y() + dy, pos.z() + dz};
            }
        }
        return best;
    }

    // ---- home, the fence (for the jobs) ----

    /** Where /home lands: {x,y,z,dim}, or null. */
    public JsonObject home() {
        JsonObject b = brainStore.data();
        return b.has("home") && b.get("home").isJsonObject() ? b.getAsJsonObject("home") : null;
    }

    public void setHome(int[] p, String dim) {
        JsonObject h = new JsonObject();
        h.addProperty("x", p[0]);
        h.addProperty("y", p[1]);
        h.addProperty("z", p[2]);
        h.addProperty("dim", dim);
        brainStore.data().add("home", h);
        saved();
    }

    /** The fence is on: strict mode and at least one area. */
    public boolean fenceOn() {
        return policy != null && policy.strict() && !policy.areas().isEmpty();
    }

    /** Cells between x y z and the nearest area in this dimension (0 = inside one); 999 with none. */
    int areaGap(int x, int y, int z, String dim) {
        int best = 999;
        for (JsonElement e : policy.areas()) {
            JsonObject a = e.getAsJsonObject();
            if (!PolicyCommands.dimOf(a).equals(dim)) continue;
            best = Math.min(best, PolicyCommands.boxGap(a, x, y, z));
        }
        return best;
    }

    /** Hands a command line to the bridge script; its answer comes later. */
    private Reply forward(String from, String raw, boolean internal, Listener l) {
        long tick = core.tick();
        if (!bridge.present(tick)) {
            return Reply.now("error: \"" + Texts.verbAndRest(raw)[0] + "\" still needs the bridge script (KubeJS), and it is not running");
        }
        return new Reply(null, bridge.submit("pm", from, raw, internal, null, l, tick));
    }

    String statusLine(LocalPlayer p, long tick) {
        String s = (int) Math.floor(p.getX()) + " " + (int) Math.floor(p.getY()) + " " + (int) Math.floor(p.getZ())
                + " | health " + Math.round(p.getHealth()) + "/20 | food " + p.getFoodData().getFoodLevel() + "/20";
        JsonObject job = bridge.job(tick);
        if (jobs.running()) s += " | " + jobs.job.status;
        else if (bridge.jobRunning(tick)) s += " | " + BridgeLink.str(job, "status");
        JsonObject rep = bridge.report(tick);
        JsonObject mem = rep != null && rep.has("memory") && rep.get("memory").isJsonObject() ? rep.getAsJsonObject("memory") : null;
        if (mem != null && "readonly".equals(BridgeLink.str(mem, "state"))) s += " | notes READ-ONLY (PM memory)";
        return s;
    }

    static String inventorySummary(LocalPlayer p) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            counts.merge(Texts.shortId(itemId(st)), st.getCount(), Integer::sum);
        }
        if (counts.isEmpty()) return "empty";
        List<String> parts = new ArrayList<>();
        counts.forEach((k, v) -> parts.add(v + " " + k));
        return String.join(", ", parts);
    }

    static String itemId(ItemStack st) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
    }

    String stopAll() {
        long tick = core.tick();
        String routine = chains.clear();
        bridge.dropQueued();
        jobs.followWatch = null;
        if (jobs.running()) jobs.finish("stopped");
        IBaritone mb = Jobs.baritone();
        if (mb != null) Jobs.cancel(mb);
        if (bridge.present(tick)) {
            bridge.submit("stop", owner(), "stop", false, null, null, tick);
        } else {
            try {
                IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (b != null) b.getPathingBehavior().cancelEverything();
            } catch (Throwable ignored) {}
            core.guard.core.releaseAll(core.token);
        }
        Minecraft mc = Minecraft.getInstance();
        mc.options.keyUse.setDown(false);
        mc.options.keyShift.setDown(false);
        return "ok: stopped everything" + (routine != null ? " (including " + routine + ")" : "") + ", breaking off";
    }

    String setDefence(String text) {
        String a = text == null ? "" : text.trim().toLowerCase();
        if (a.equals("on") || a.equals("off")) core.reflexes.setDefence(a.equals("on"));
        return "self-defence is " + (core.reflexes.defence() ? "ON (fights monsters, avoids creepers, retreats under 6 health)" : "OFF");
    }

    String reconnectCommand(String rest) {
        boolean on = rest.toLowerCase().contains("on");
        brainStore.data().addProperty("reconnect", on);
        saved();
        core.reconnect.setOn(on);
        return "ok: reconnecting after a kick is " + (on ? "on (after 1, 5, 15 min; 3 an hour at most)" : "off");
    }

    String restartCommand(String from, String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase();
        if (r.equals("ok")) {
            restartOk = new JsonObject();
            restartOk.addProperty("by", from);
            restartOk.addProperty("at", System.currentTimeMillis());
            return "ok: restart confirmed by " + from + ", valid for 15 minutes";
        }
        if (r.equals("no")) {
            restartOk = null;
            return "ok: restart confirmation cleared";
        }
        return "usage: restart ok (I may be closed for an update within 15 minutes) | restart no (take it back)";
    }

    String allowCommand(String verb, String rest, boolean isOwner) {
        if (!isOwner) return "only " + owner() + " can change who I listen to";
        List<String> list = allowed();
        if (verb.equals("allowed")) return "I take orders from: " + owner() + (list.isEmpty() ? "" : ", " + String.join(", ", list));
        if (!rest.matches("^\\w{3,16}$")) return "usage: " + verb + " <player name>";
        JsonArray kept = new JsonArray();
        for (String a : list) if (!a.equalsIgnoreCase(rest)) kept.add(a);
        if (verb.equals("allow")) kept.add(rest);
        pmStore.data().add("allowed", kept);
        pmStore.changed(core.tick());
        mirrorPmConfig();
        refused.remove(rest.toLowerCase());
        return verb.equals("allow") ? "ok, I now take orders from " + rest : "ok, I no longer take orders from " + rest;
    }

    /** The bridge's own pm.json follows (it reads the owner from it, and a rolled-back mod leaves it in charge). */
    private void mirrorPmConfig() {
        if (bridgeFiles != null) bridgeFiles.writeJson("pm.json", pmStore.data().toString());
    }

    String poiCommand(String rest, LocalPlayer player, boolean isOwner) {
        List<String> w = Texts.words(rest == null ? "" : rest.toLowerCase());
        JsonArray list = core.pois.toJson().getAsJsonArray("pois");
        String dim = Guard.dimOf(player.level());
        int mx = (int) Math.floor(player.getX()), my = (int) Math.floor(player.getY()), mz = (int) Math.floor(player.getZ());
        long now = System.currentTimeMillis();
        if (!w.isEmpty() && (w.get(0).equals("show") || w.get(0).equals("forget"))) {
            String idText = w.size() > 1 ? w.get(1) : "";
            int id = (int) Chains.leadingInt(idText);
            JsonObject p = null;
            for (JsonElement e : list) if (e.getAsJsonObject().get("id").getAsInt() == id && !idText.isEmpty()) p = e.getAsJsonObject();
            if (p == null) return "error: no poi " + idText + " (\"poi\" lists them)";
            if (w.get(0).equals("show")) {
                return poiText(p, dim, mx, my, mz) + ", first seen " + Texts.ago(p.get("first").getAsLong(), now) + ", last " + Texts.ago(p.get("last").getAsLong(), now)
                        + " - \"go poi " + id + "\" takes me there";
            }
            if (!isOwner) return "sorry, only " + owner() + " can forget points of interest";
            return core.pois.forget(id, core.tick()) ? "ok: forgot poi " + id : "error: no poi " + id;
        }
        int n = 5;
        if (!w.isEmpty() && w.get(w.size() - 1).matches("^\\d+$")) n = Math.min(Integer.parseInt(w.remove(w.size() - 1)), 12);
        if (!w.isEmpty() && w.get(0).equals("near")) w.remove(0);
        String kind = String.join(" ", w);
        List<JsonObject> hits = new ArrayList<>();
        for (JsonElement e : list) {
            JsonObject q = e.getAsJsonObject();
            String k = q.get("kind").getAsString();
            if (q.get("dim").getAsString().equals(dim) && (kind.isEmpty() || k.contains(kind) || kind.contains(k))) hits.add(q);
        }
        if (hits.isEmpty()) return !kind.isEmpty() ? "no " + kind + " seen in " + dim + " yet" : "no points of interest seen here yet (I note them as I walk around)";
        hits.sort((a, b) -> Long.compare(distSq(a, mx, my, mz), distSq(b, mx, my, mz)));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, hits.size()); i++) out.add(poiText(hits.get(i), dim, mx, my, mz));
        return String.join(" | ", out) + (hits.size() > n ? " (+" + (hits.size() - n) + " more)" : "");
    }

    static long distSq(JsonObject p, int x, int y, int z) {
        long dx = p.get("x").getAsInt() - x, dy = p.get("y").getAsInt() - y, dz = p.get("z").getAsInt() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    static String poiText(JsonObject p, String dim, int x, int y, int z) {
        String pd = p.get("dim").getAsString();
        return "#" + p.get("id").getAsInt() + " " + p.get("kind").getAsString() + " at " + p.get("x").getAsInt() + " " + p.get("y").getAsInt() + " " + p.get("z").getAsInt()
                + (pd.equals(dim) ? " (" + Math.round(Math.sqrt(distSq(p, x, y, z))) + "m)" : " (" + pd + ")");
    }

    String cavesCommand(String rest) {
        List<String> w = Texts.words(rest);
        if (!w.isEmpty() && w.get(0).equals("rename") && w.size() == 3) {
            if (!w.get(2).matches("^[a-z0-9_-]{1,24}$")) return "error: a cave name is letters, digits, _ and -";
            return core.caves.rename(w.get(1), w.get(2), core.tick());
        }
        JsonObject o = core.caves.toJson(false).getAsJsonObject("caves");
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            JsonObject v = e.getValue().getAsJsonObject(), en = v.getAsJsonObject("entrance");
            out.add(e.getKey() + " at " + en.get("x").getAsInt() + " " + en.get("y").getAsInt() + " " + en.get("z").getAsInt() + " ("
                    + (v.get("frontierLeft").getAsBoolean() ? "got " + v.get("furthest").getAsInt() + " blocks in" : "finished") + ")");
        }
        return out.isEmpty() ? "no caves yet - \"mine cave <ores>\" in one" : String.join(" | ", out);
    }

    /** "here" for a PM: the sender's spot when the bot can see them, else the bot's. */
    PolicyCommands.Pos hereOf(Minecraft mc, String from) {
        if (from != null && mc.level != null) {
            for (Player p : mc.level.players()) {
                if (p.getGameProfile().getName().equalsIgnoreCase(from)) return posOf(mc, p);
            }
        }
        return posOf(mc, mc.player);
    }

    static PolicyCommands.Pos posOf(Minecraft mc, Player p) {
        return new PolicyCommands.Pos((int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()), Guard.dimOf(p.level()));
    }

    private static String baritonePrefix() {
        try { return String.valueOf(BaritoneAPI.getSettings().prefix.value); } catch (Throwable t) { return "#"; }
    }

    // ---- deaths ----

    private void noteDeath(Minecraft mc, LocalPlayer p) {
        chains.noteDeath();
        jobs.finish("stopped: the bot died");
        JsonObject d = new JsonObject();
        d.addProperty("x", (int) Math.floor(p.getX()));
        d.addProperty("y", (int) Math.floor(p.getY()));
        d.addProperty("z", (int) Math.floor(p.getZ()));
        d.addProperty("dim", Guard.dimOf(p.level()));
        d.addProperty("time", System.currentTimeMillis());
        brainStore.data().add("lastDeath", d);
        saved();
        JsonObject r = core.reflexes.status();
        String target = r.has("target") ? r.get("target").getAsString() : null;
        whisper(owner(), "I died at " + d.get("x").getAsInt() + " " + d.get("y").getAsInt() + " " + d.get("z").getAsInt() + (target != null ? " fighting " + target : "")
                + ". Respawning - PM \"death\" and I will go back for my stuff.");
    }

    // ---- cmd.json (bridge.ps1, the dashboard) ----

    private void pollCommand(Minecraft mc, long tick) {
        JsonObject cmd = readBridgeJson(mc, "cmd.json");
        if (cmd == null || !cmd.has("id") || cmd.get("id").isJsonNull()) return;
        String id = cmd.get("id").getAsString();
        if (id.equals(seenCmdId)) return;
        seenCmdId = id;
        // ids are send times in ms: never run a leftover command after a restart
        long sent = Chains.parseLong(id, 0);
        if (System.currentTimeMillis() - sent > 30000) return;
        String type = cmd.has("type") ? cmd.get("type").getAsString() : "";
        String text = cmd.has("text") && !cmd.get("text").isJsonNull() ? cmd.get("text").getAsString() : "";
        String from = cmd.has("from") && !cmd.get("from").isJsonNull() && !cmd.get("from").getAsString().isEmpty() ? cmd.get("from").getAsString() : null;
        String notify = cmd.has("notify") && !cmd.get("notify").isJsonNull() && !cmd.get("notify").getAsString().isEmpty() ? cmd.get("notify").getAsString() : null;
        Reply r;
        try {
            r = runCommand(mc, cmd, type, text, from, notify, id);
        } catch (RuntimeException e) {
            r = Reply.now("error: " + e);
        }
        // a bridge request answers later (its listener); the mod's own job answered already
        if (r.pending() == null || r.pending().local) cmdResult(id, type, text, r.text());
    }

    private void cmdResult(String id, String type, String text, String result) {
        lastCmdId = id;
        lastResult = result;
        LOG.info("[entropybot] cmd {} ({} {}) -> {}", id, type, text, result);
    }

    private Reply runCommand(Minecraft mc, JsonObject cmd, String type, String text, String from, String notify, String id) {
        LocalPlayer player = mc.player;
        switch (type) {
            case "chat" -> {
                String no = Texts.sayRefusal(text, baritonePrefix());
                if (no != null) return Reply.now(no);
                player.connection.sendChat(text);
                return Reply.now("ok");
            }
            case "command" -> {
                player.connection.sendCommand(text.replaceFirst("^/", ""));
                return Reply.now("ok");
            }
            case "stop" -> { return Reply.now(stopAll()); }
            case "pm" -> {
                Reply r = handle(owner(), text, false, new Listener() {
                    @Override
                    public void replied(Request q) { cmdResult(id, type, text, q.reply == null || q.reply.isEmpty() ? "ok" : q.reply); }

                    @Override
                    public void finished(Request q) {
                        if (!BridgeLink.quiet(q.doneMsg)) whisper(owner(), q.doneMsg.replaceFirst("^ok: ", ""));
                    }
                });
                return r.pending() != null ? r : Reply.now(r.text() == null ? "ok" : r.text());
            }
            case "restart" -> { return Reply.now(restartCommand(from != null ? from : owner(), text)); }
            case "reload" -> {
                player.connection.sendCommand("kubejs reload client-scripts");
                return Reply.now("ok: reloading");
            }
            case "eat" -> { return Reply.now(core.reflexes.eat()); }
            case "defend" -> { return Reply.now(setDefence(text)); }
            case "area", "protect", "unprotect", "guard" -> {
                return Reply.now(policy.command(type, text, true, owner(), hereOf(mc, from), posOf(mc, player)));
            }
            case "poi", "pois" -> { return Reply.now(poiCommand(text, player, true)); }
            case "caves" -> { return Reply.now(cavesCommand(text)); }
            // package B: the hotbar layout and the tool policy (settings, never busy)
            case "hotbar" -> { return Reply.now(hotbarCommand(player, text)); }
            case "tools" -> { return Reply.now(toolsCommand(text)); }
            // B7b part 1: the places and the walks the mod does (cmd.from = whose spot "mark" uses, as before)
            case "mark", "setbase", "sethome", "forget", "places" -> { return Reply.now(placeCommand(type, text, from, player)); }
            // B7b part 2: the instant GUI and storage verbs never wait for a job (as the bridge's runCommand)
            case "take", "put", "close", "drop", "use", "wear", "equip", "where", "trust", "untrust", "recipe", "need", "supplies" -> { return Reply.now(modJob(type, text, owner(), player)); }
            case "spawn", "home", "base", "twerk", "find", "go", "open", "scan", "deposit", "corpse", "death", "rs", "pots",
                 "craft", "kit", "smelt", "get", "restock", "farm", "compact" -> {
                if (type.equals("farm") && FarmCommand.instant(text)) return Reply.now(crafting.farm(player, text));
                // as the bridge's runCommand: a task makes these busy (a walk is replaced; twerk toggles; find never waits)
                if (type.equals("twerk") && jobs.running() && jobs.job.type.equals("twerk")) return Reply.now(jobs.startTwerk(text));
                if (!type.equals("find")) {
                    long tick = core.tick();
                    JsonObject bj = bridge.job(tick);
                    if (jobs.running() && !jobs.walking()) return Reply.now("error: busy with \"" + jobs.job.status + "\" - send stop first");
                    if (bridge.jobRunning(tick)) {
                        if (!"travel".equals(BridgeLink.str(bj, "type"))) return Reply.now("error: busy with \"" + BridgeLink.str(bj, "status") + "\" - send stop first");
                        bridge.submit("endwalk", owner(), "", false, null, null, tick);
                    }
                    jobs.replaceWalk();
                }
                String r = modJob(type, text, owner(), player);
                Request q = jobs.attach("cmd", owner(), type + " " + text, r, notify == null ? null : new Listener() {
                    @Override public void replied(Request x) {}

                    @Override public void finished(Request x) {
                        if (!BridgeLink.quiet(x.doneMsg)) whisper(notify, x.doneMsg.replaceFirst("^ok: ", ""));
                    }
                });
                return new Reply(r, q);
            }
            default -> { return forwardCmd(cmd, type, text, from, notify, id); }
        }
    }

    /** A cmd.json type the bridge still does: its runCommand answers. */
    private Reply forwardCmd(JsonObject cmd, String type, String text, String from, String notify, String id) {
        long tick = core.tick();
        if (!bridge.present(tick)) return Reply.now("error: \"" + type + "\" still needs the bridge script (KubeJS), and it is not running");
        return new Reply(null, bridge.submit("cmd", from != null ? from : owner(), text, false, cmd.deepCopy(), new Listener() {
            @Override
            public void replied(Request q) { cmdResult(id, type, text, q.reply); }

            @Override
            public void finished(Request q) {
                if (notify != null && !BridgeLink.quiet(q.doneMsg)) whisper(notify, q.doneMsg.replaceFirst("^ok: ", ""));
            }
        }, tick));
    }

    /** Where the reflexes retreat to: the base and the /home landing (when they change). */
    private void pushPlaces() {
        JsonObject o = new JsonObject();
        JsonObject base = core.knowledge.places().get("base");
        o.add("base", base == null ? JsonNull.INSTANCE : base);
        JsonObject h = home();
        o.add("home", h == null ? JsonNull.INSTANCE : h);
        String j = o.toString();
        if (j.equals(placesSent)) return;
        placesSent = j;
        try { LOG.info("[entropybot] reflex places: {}", core.reflexes.setPlaces(j)); } catch (RuntimeException e) { LOG.warn("[entropybot] reflex places: {}", e.toString()); }
    }

    // ---- state.json (bridge.ps1 and the dashboard read it; the same fields the bridge wrote) ----

    private void writeState(Minecraft mc, long tick) {
        if (bridgeFiles == null) return;
        JsonObject s = new JsonObject();
        s.addProperty("time", System.currentTimeMillis());
        s.addProperty("lastCmdId", lastCmdId);
        s.addProperty("lastResult", lastResult);
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            s.addProperty("inWorld", false);
            bridgeFiles.writeJson("state.json", s.toString());
            return;
        }
        s.addProperty("inWorld", true);
        JsonArray errs = new JsonArray();
        s.add("errors", errs);
        JsonObject rep = bridge.report(tick);
        s.add("memory", rep != null && rep.has("memory") ? rep.get("memory") : JsonNull.INSTANCE);
        try {
            s.addProperty("name", p.getGameProfile().getName());
            s.addProperty("x", Math.round(p.getX() * 10) / 10.0);
            s.addProperty("y", Math.round(p.getY() * 10) / 10.0);
            s.addProperty("z", Math.round(p.getZ() * 10) / 10.0);
            s.addProperty("health", Math.round(p.getHealth()));
            s.addProperty("food", p.getFoodData().getFoodLevel());
            s.addProperty("dead", p.isDeadOrDying());
            s.addProperty("dimension", Guard.dimOf(mc.level));
        } catch (RuntimeException e) {
            errs.add("basics: " + e);
        }
        JsonArray inv = new JsonArray();
        try {
            s.addProperty("selectedSlot", p.getInventory().selected);
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (st.isEmpty()) continue;
                JsonObject o = new JsonObject();
                o.addProperty("slot", i);
                o.addProperty("id", itemId(st));
                o.addProperty("count", st.getCount());
                inv.add(o);
            }
        } catch (RuntimeException e) {
            errs.add("inventory: " + e);
        }
        s.add("inventory", inv);
        JsonArray players = new JsonArray();
        try {
            for (Player o : mc.level.players()) {
                if (o == p) continue;
                JsonObject q = new JsonObject();
                q.addProperty("name", o.getGameProfile().getName());
                q.addProperty("x", (int) Math.floor(o.getX()));
                q.addProperty("y", (int) Math.floor(o.getY()));
                q.addProperty("z", (int) Math.floor(o.getZ()));
                q.addProperty("distance", Math.round(o.distanceTo(p)));
                players.add(q);
            }
        } catch (RuntimeException e) {
            errs.add("players: " + e);
        }
        s.add("players", players);
        try {
            HitResult hit = mc.hitResult;
            if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
                JsonObject l = new JsonObject();
                l.addProperty("x", bh.getBlockPos().getX());
                l.addProperty("y", bh.getBlockPos().getY());
                l.addProperty("z", bh.getBlockPos().getZ());
                l.addProperty("block", mc.level.getBlockState(bh.getBlockPos()).getBlock().getDescriptionId());
                s.add("lookingAt", l);
            }
        } catch (RuntimeException e) {
            errs.add("lookingAt: " + e);
        }
        s.add("screen", mc.screen == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(mc.screen.getClass().getSimpleName()));
        // the open container, as the mod's GUI toolkit reads it (upgrade and filter slots left out)
        Map<String, Integer> open = null;
        try { open = Gui.containerContents(p); } catch (RuntimeException e) { errs.add("container: " + e); }
        JsonObject held = null;
        if (open != null) {
            held = new JsonObject();
            for (Map.Entry<String, Integer> e : open.entrySet()) held.addProperty(e.getKey(), e.getValue());
        }
        s.add("container", held == null ? JsonNull.INSTANCE : held);
        // the job: the mod's while it runs (or when the bridge has none to show), else the bridge's
        if (jobs.job != null && (jobs.running() || !bridge.jobRunning(tick))) s.add("job", jobs.stateJson());
        else if (rep != null && rep.has("job")) s.add("job", rep.get("job"));
        JsonObject rx = core.reflexes.status();
        JsonObject def = new JsonObject();
        def.addProperty("on", core.reflexes.defence());
        String kind = rx.get("reflex").getAsString();
        def.add("status", kind.equals("none") ? JsonNull.INSTANCE : rx.get("status"));
        s.add("defence", def);
        s.addProperty("reflex", kind);
        s.add("chain", chains.running() ? new com.google.gson.JsonPrimitive(chains.chainStatus()) : JsonNull.INSTANCE);
        JsonObject pm = new JsonObject();
        pm.addProperty("listening", true);
        pm.addProperty("owner", owner());
        JsonArray al = new JsonArray();
        for (String a : allowed()) al.add(a);
        pm.add("allowed", al);
        synchronized (pmQueue) {
            pm.addProperty("queued", pmQueue.size());
        }
        synchronized (outbox) {
            pm.addProperty("outbox", outbox.size());
        }
        pm.addProperty("bridge", bridge.present(tick));
        s.add("pm", pm);
        s.add("restartOk", restartOk == null ? JsonNull.INSTANCE : restartOk);
        JsonObject mod = new JsonObject();
        mod.addProperty("version", core.version());
        mod.add("features", core.features());
        s.add("mod", mod);
        try {
            JsonObject g = core.guardStatus(), gs = new JsonObject();
            gs.add("mode", g.get("mode"));
            gs.addProperty("strict", policy.strict());
            JsonArray an = new JsonArray(), pn = new JsonArray();
            for (JsonElement e : g.getAsJsonArray("areas")) an.add(e.getAsJsonObject().has("name") ? e.getAsJsonObject().get("name").getAsString() : "box");
            for (JsonElement e : g.getAsJsonArray("protect")) pn.add(e.getAsJsonObject().has("name") ? e.getAsJsonObject().get("name").getAsString() : "box");
            gs.add("areas", an);
            gs.add("protect", pn);
            gs.addProperty("leases", g.getAsJsonArray("leases").size());
            gs.add("vetoes", g.get("vetoes"));
            gs.add("wouldVetoes", g.get("wouldVetoes"));
            s.add("guard", gs);
        } catch (RuntimeException e) {
            errs.add("guard: " + e);
        }
        try {
            JsonObject b = new JsonObject();
            IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
            b.addProperty("pathing", bar.getPathingBehavior().isPathing());
            bar.getPathingControlManager().mostRecentInControl().ifPresent(pr -> b.addProperty("process", pr.displayName()));
            if (bar.getCustomGoalProcess().getGoal() != null) b.addProperty("goal", bar.getCustomGoalProcess().getGoal().toString());
            b.addProperty("allowBreak", BaritoneAPI.getSettings().allowBreak.value);
            s.add("baritone", b);
        } catch (Throwable t) {
            JsonObject b = new JsonObject();
            b.addProperty("error", t.toString());
            s.add("baritone", b);
        }
        if (rep != null && rep.has("errors") && rep.get("errors").isJsonArray()) for (JsonElement e : rep.getAsJsonArray("errors")) errs.add("bridge " + e.getAsString());
        String w = bridgeFiles.writeJson("state.json", s.toString());
        if (!w.startsWith("ok") && errors++ < 5) LOG.warn("[entropybot] state write failed: {}", w);
    }

    // ---- what the bridge reads (BotAPI) ----

    /** The policy as the bridge's policyOf() wants it: {areas, protect, strict, corner1}. */
    public String policyJson() {
        return policy == null ? "{}" : areaStore.data().toString();
    }

    // ---- Chains.Env ----

    @Override public long tick() { return core.tick(); }

    @Override public long now() { return System.currentTimeMillis(); }

    @Override public ZoneId zone() { return ZoneId.systemDefault(); }

    @Override public void log(String line) {
        LOG.info("[entropybot] {}", line);
        core.events.push("job", line, null);
    }

    @Override public Reply dispatch(String from, String text, boolean internal, Listener l) { return handle(from, text, internal, l); }

    @Override public boolean alive() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p != null && !p.isDeadOrDying();
    }

    @Override public boolean holding() { return core.reflexes.hold(); }

    @Override public boolean fighting() {
        Reflexes.Reflex r = core.reflexes.reflex();
        return r == Reflexes.Reflex.FIGHTING || r == Reflexes.Reflex.FLEEING || r == Reflexes.Reflex.RETREATING;
    }

    @Override public float health() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? 0 : p.getHealth();
    }

    @Override public boolean busy() { return bridge.busy(core.tick()); }

    @Override public int freeSlots() {
        LocalPlayer p = Minecraft.getInstance().player;
        int n = 0;
        if (p != null) for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) n++;
        return n;
    }

    @Override public int bagRoom() {
        JsonObject rep = bridge.report(core.tick());
        if (rep != null && rep.has("bagRoom")) return rep.get("bagRoom").getAsInt();
        return freeSlots();
    }

    @Override public Map<String, Integer> inventory() {
        Map<String, Integer> out = new LinkedHashMap<>();
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty()) out.merge(itemId(st), st.getCount(), Integer::sum);
        }
        return out;
    }

    @Override public JsonObject supplies() {
        JsonObject b = brainStore.data();
        return b.has("supplies") && b.get("supplies").isJsonObject() ? b.getAsJsonObject("supplies") : null;
    }

    @Override public String orePrefer() {
        JsonObject rep = bridge.report(core.tick());
        return rep != null && rep.has("orePrefer") && !rep.get("orePrefer").isJsonNull() ? rep.get("orePrefer").getAsString() : null;
    }

    @Override public JsonObject minePlace() { return core.knowledge.places().get("mine"); }

    @Override public boolean inAreas(String dim, int x, int z) { return policy.inAreas(dim, x, z); }

    @Override public String dim() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? "" : Guard.dimOf(mc.level);
    }

    @Override public void saved() { brainStore.changed(core.tick()); }

    /** The guard as the policy commands see it. */
    private final class GuardView implements PolicyCommands.Guard {
        @Override
        public String[] apply(JsonObject p, boolean strict) {
            String r, m;
            try {
                Policy pol = Policy.parse(p.toString());
                r = core.guard.core.setPolicy(pol);
            } catch (IllegalArgumentException e) {
                r = "error: " + e.getMessage();
            }
            m = core.guard.core.setMode(strict ? GuardCore.Mode.STRICT : GuardCore.Mode.LOG);
            core.events.push("guard", "policy: " + r + "; " + m, null);
            LOG.info("[entropybot] guard: policy -> {}; mode -> {}", r, m);
            return new String[]{r, m};
        }

        @Override
        public JsonObject status() { return core.guardStatus(); }

        @Override
        public String vetoes(int max) { return core.guard.core.log().recent(max); }

        @Override
        public String check(String dim, int x, int y, int z, String action) {
            return io.github.mojolowjo.entropybot.api.BotAPI.check(dim, x, y, z, action);
        }
    }

    @SuppressWarnings("unused")
    private static JsonObject parse(String s) { return JsonParser.parseString(s).getAsJsonObject(); }
}
