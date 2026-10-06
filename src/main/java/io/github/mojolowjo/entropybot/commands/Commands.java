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
import io.github.mojolowjo.entropybot.commands.Chains.Reply;
import io.github.mojolowjo.entropybot.commands.JobRequests.Listener;
import io.github.mojolowjo.entropybot.commands.JobRequests.Request;
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
 * the autominer and the death policy. Every verb is the mod's (B7e: the KubeJS bridge script is gone); a job's end
 * reaches its requester through {@link JobRequests}.
 */
public final class Commands implements Chains.Env {
    private static final Logger LOG = LogUtils.getLogger();
    /** Where state.json and cmd.json live (bridge.ps1 and the dashboard read and write them there); the folder keeps its old name. */
    static final String BRIDGE_DIR = "kubejs/bridge";

    private final Core core;
    public final JobRequests requests = new JobRequests();
    private final JsonStore pmStore = new JsonStore("pm.json"), brainStore = new JsonStore("commands.json"), areaStore = new JsonStore("areas.json");
    private Chains chains;
    private PolicyCommands policy;
    /** The kubejs/bridge folder: state.json out, cmd.json in. */
    private BotFiles stateFiles;
    private boolean ready;
    private final ArrayDeque<ChatParse.Pm> pmQueue = new ArrayDeque<>();
    private final Outbox outbox = new Outbox();
    private final Set<String> refused = new HashSet<>();
    private String lastCmdId, lastResult, seenCmdId;
    private JsonObject restartOk;
    private long worldTicks;
    private boolean wasDead;
    private int errors;

    public final Jobs jobs;
    /** The owner's position from the companion mod's owner.json, for when the bot cannot see them (see OwnerFix). */
    private final OwnerFix ownerFix = new OwnerFix(() -> Core.INSTANCE.files() == null ? null : Core.INSTANCE.files().root().resolve("owner.json"));
    /** B7b part 2: the storage verbs (open/take/put/scan/deposit/where/trust/corpse/death/drop/rs/pots/go poi). */
    public final Storage storage;
    /** B7e N: the "check" self-test (and its idle check), and the confirm question for big verbs. */
    final SelfCheckLive selfCheck = new SelfCheckLive(this);
    final ConfirmGate confirmGate = new ConfirmGate(selfCheck);
    private String placesSent;

    public Commands(Core core) {
        this.core = core;
        this.jobs = new Jobs(core, this);
        this.storage = new Storage(core, this, jobs);
        this.crafting = new Crafting(core, this, jobs, storage);
        storage.crafting = crafting;
        this.gathering = new Gathering(core, this);
    }

    /** P2: "gather &lt;item&gt; [n]" - runs other verbs as its steps. */
    final Gathering gathering;

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

    /** The commands store's object (commands.json): supplies, routines, rules, run, and package D's furnace jobs and smelt mode. */
    JsonObject brainData() { return brainStore.data(); }

    /** B7e (E1): the three stores, for "memory". */
    JsonStore[] stores() { return new JsonStore[]{pmStore, brainStore, areaStore}; }

    /** Package E: commands.json written now (the altar notes made right before a click). */
    void brainFlush() { brainStore.flush(); }

    // ---- package D: furnace pickups ----

    /** A "stop" holds the pickups this long (like the autominer's hold). */
    static final long PICKUP_HOLD_MS = 10 * 60_000L;
    private long lastStop = -1;

    private boolean pickupHeld(long now) { return lastStop > 0 && now - lastStop < PICKUP_HOLD_MS; }

    /** A chain step boundary may detour for a pickup (Chains.Env). */
    @Override public boolean furnaceDue() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !ready) return false;
        long now = System.currentTimeMillis();
        return !pickupHeld(now) && mc.player != null && !crafting.furnaces().due(now, Storage.dim()).isEmpty() && pickupFromHere(mc.player, now);
    }

    /** Every second: forget furnace jobs long past due (the owner hears it), and pick up due output while the bot is idle. */
    private void furnaceTick(LocalPlayer player, long tick) {
        io.github.mojolowjo.entropybot.craft.FurnaceJobs fj = crafting.furnaces();
        if (fj.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (io.github.mojolowjo.entropybot.craft.FurnaceJobs.Job j : fj.expire(now, crafting.sessionStart)) {
            String t = io.github.mojolowjo.entropybot.craft.FurnaceJobs.forgotten(j);
            LOG.info("[entropybot] {}", t);
            whisper(owner(), t);
        }
        if (fj.due(now, Storage.dim()).isEmpty() || pickupHeld(now)) return;
        if (jobs.running() || requests.busy() || chains.running() || chains.parked() || core.reflexes.hold() || player.isDeadOrDying()) return;
        if (!pickupFromHere(player, now)) return;
        Reply r = handle(owner(), Chains.PICKUP_STEP, false, true, pmListener(owner()));     // auto: never cancels the owner's pending confirm
        LOG.info("[entropybot] furnace pickup: {}", r.text());
    }

    /** Blocks from the base within which the bot picks furnace output up by itself. */
    static final int PICKUP_NEAR_BASE = 48;

    /**
     * An idle pickup only when the bot is near the base or inside its areas, and wasn't sent somewhere (come, goto,
     * follow, go) in the last 10 minutes: the owner's errand comes first.
     */
    private boolean pickupFromHere(LocalPlayer player, long now) {
        if (jobs.lastTravelAt > 0 && now - jobs.lastTravelAt < PICKUP_HOLD_MS) return false;
        int[] me = Jobs.here(player);
        JsonObject b = core.knowledge.places().get("base");
        if (b != null && Jobs.dimOf(b).equals(Storage.dim())) {
            int[] bp = Jobs.pos(b);
            if (Math.abs(me[0] - bp[0]) <= PICKUP_NEAR_BASE && Math.abs(me[2] - bp[2]) <= PICKUP_NEAR_BASE && Math.abs(me[1] - bp[1]) <= 24) return true;
        }
        return fenceOn() && inAreas(Storage.dim(), me[0], me[2]);
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

    /**
     * Routing stage 1 (R3): the route settings in commands.json, {"on": true, "mode": "goal"} until changed with
     * {@code route on|off} and {@code route mode goal|legs}. Never null.
     */
    JsonObject routeSettings() {
        JsonObject b = brainStore.data();
        if (!b.has("route") || !b.get("route").isJsonObject()) {
            JsonObject r = new JsonObject();
            r.addProperty("on", true);
            r.addProperty("mode", "goal");
            return r;          // not stored until changed
        }
        return b.getAsJsonObject("route");
    }

    /** Stores the route settings (route on|off, route mode). */
    void setRouteSettings(JsonObject r) {
        brainStore.data().add("route", r);
        saved();
        RouteCommand.syncRuntime(this);
    }

    /** The hotbar keeper holds still while a job other than a walk or a wait runs, or a reflex does. */
    private boolean hotbarBusy() {
        return core.reflexes.hold() || hotbarJob() != null;
    }

    /** The running job for the hotbar keeper's start window (round 2); null when there is none, or it is a walk or a wait. */
    private Object hotbarJob() {
        return jobs.running() && !jobs.walking() && !"wait".equals(jobs.job.type) ? jobs.job : null;
    }

    /** Where the bot last died: {x, y, z, dim, time}, or null. */
    JsonObject lastDeath() {
        JsonObject b = brainStore.data();
        return b.has("lastDeath") && b.get("lastDeath").isJsonObject() ? b.getAsJsonObject("lastDeath") : null;
    }

    public boolean ready() { return ready; }

    /** Routing (R2): nothing runs (no job, chain or open request), so the route map may do its idle work. */
    public boolean idleForRoutes() {
        return ready && !jobs.running() && !requests.busy() && (chains == null || !chains.running());
    }

    // ---- start ----

    /** Once, at the first tick in a world: the files, the move of the notes from the bridge's memory.json. */
    public String init(Minecraft mc, BotFiles files) {
        stateFiles = new BotFiles(mc.gameDirectory.toPath().resolve(BRIDGE_DIR));
        StringBuilder sb = new StringBuilder();
        sb.append(pmStore.load(files)).append("; ").append(brainStore.load(files)).append("; ").append(areaStore.load(files));
        sb.append("; ").append(RestoreLive.INSTANCE.load(this, files));          // P1: the restore ledger
        RouteCommand.syncRuntime(this);     // routing review M1: a stored "route off" keeps the map builder off too
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
        // B7b: where /home lands is the mod's too (one-time move from memory.json)
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
        core.reflexes.setCreeperMode(io.github.mojolowjo.entropybot.engine.CreeperSetting.load(b));     // B7e C
        // package G: the fast channel starts once bridge.ps1 has written entropybot/fast.json (the key's path)
        fast = new io.github.mojolowjo.entropybot.fast.FastChannel(files.root(), new FastHandler(), m -> LOG.info("{}", m));
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
            // package G: the fast channel's requests run here, on the game thread (also outside a world: it says so)
            if (fast != null) {
                try {
                    fast.tick(tick);
                } catch (RuntimeException e) {
                    if (errors++ < 5) LOG.warn("[entropybot] fast channel: {}", e.toString());
                }
            }
            if (mc.level == null || mc.player == null) {
                // B7d review: a job other than a walk (the Baritone mine with breaking on, a clear holding leases, a
                // craft with a menu open) ends on a disconnect, so it can't carry on by itself from wherever the bot
                // respawns after the reconnect
                if (worldTicks > 0 && jobs.running() && !jobs.walking()) jobs.finish("stopped: I was disconnected");
                worldTicks = 0;
                if (tick % 20 == 0) writeState(mc, tick);
                return;
            }
            worldTicks++;
            LocalPlayer player = mc.player;
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
            if (tick % 5 == 4) {
                try {
                    gathering.tick(player);                    // P2: the next gather step once the last one ended
                } catch (RuntimeException e) {
                    LOG.warn("[entropybot] gather: {}", e.toString());
                    gathering.stop("error: " + e);
                }
            }
            if (tick % 20 == 5) chains.deathTick(dead);
            if (!chains.resumeChecked() && worldTicks > 200) chains.resumeRun();
            if (tick % 100 == 55 && worldTicks > 400) {
                chains.rulesTick();
                chains.autominerTick();
            }
            // B7e N: the idle self-check (whispers only what changed, at most every 30 min)
            if (tick % 200 == 77 && worldTicks > 1200) selfCheck.idleTick(player, !jobs.running() && !chains.running() && !requests.busy(), System.currentTimeMillis());
            if (tick % 25 == 0) sendOutbox(player);
            try {
                jobs.tick(player);
            } catch (RuntimeException e) {
                LOG.warn("[entropybot] job: {}", e.toString());
                jobs.finish("error: " + e);
            }
            try {
                RestoreLive.INSTANCE.tick(player, tick);       // P1: pending breaks settled, restore.json written
            } catch (RuntimeException e) {
                LOG.warn("[entropybot] restore: {}", e.toString());
            }
            {
                // package B round 2: every tick, so a refill finds the gap between two breaks (HotbarKeeper spaces the swaps)
                try {
                    String moved = io.github.mojolowjo.entropybot.engine.Hotbar.tick(mc, player, tick, hotbarBusy(),
                            core.reflexes.hold(), hotbarJob());
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
            if (tick % 20 == 15 && worldTicks > 400) {
                try {
                    furnaceTick(player, tick);
                } catch (RuntimeException e) {
                    LOG.warn("[entropybot] furnace pickup: {}", e.toString());
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

    /** A PM's job: its end is whispered to the sender (the answer itself was whispered when it started). */
    private Listener pmListener(String to) {
        return notifyListener(to);
    }

    /** Whispers a job's end to {@code to} (nothing for a quiet end: a walk replaced, a twerk toggled off). */
    private Listener notifyListener(String to) {
        return r -> {
            if (!JobRequests.quiet(r.doneMsg)) whisper(to, r.doneMsg.replaceFirst("^ok: ", ""));
        };
    }

    public void whisper(String to, String text) { whisperSent(to, text); }

    /** Queues a whisper; false when the outbox dropped part of it (package H's caps). */
    boolean whisperSent(String to, String text) {
        // package H: the caps live in Outbox (one answer, the queue; one-line messages to the owner still get in)
        boolean all = true;
        for (String part : outbox.add(to, text, owner())) {
            all = false;
            LOG.info("[entropybot] whisper dropped (outbox full) to {}: {}", to, part);
        }
        return all;
    }

    private void sendOutbox(LocalPlayer player) {
        String[] m = outbox.poll();
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
    public Reply handle(String from, String message, boolean internal, Listener l) { return handle(from, message, internal, false, l); }

    /** auto: a line the bot sends itself (furnace pickup, corpse fetch): it skips the confirm gate, so it never cancels the owner's question. */
    Reply handle(String from, String message, boolean internal, boolean auto, Listener l) {
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
        // B7e N: big or destructive verbs ask first ("confirm" runs them; chain steps and a trailing "confirm" pass; a busy bot answers busy)
        if (!auto) {
            ConfirmGate.Gate gate = confirmGate.gate(from, raw, internal, System.currentTimeMillis(), chainBusyText(), jobBusyText());
            if (gate.reply() != null) return Reply.now(gate.reply());
            if (!gate.line().equals(raw)) {
                vr = Texts.verbAndRest(gate.line());
                verb = vr[0];
                rest = vr[1];
                raw = vr[2];
            }
        }
        if (verb.equals("help") || verb.equals("?") || verb.isEmpty()) return Reply.now(HelpCommand.answer(rest, isOwner, owner()));
        if (verb.equals("check")) return Reply.now(selfCheck.command(player));
        if (verb.equals("memory")) return Reply.now(MemoryCommand.command(core, this, rest));
        if (verb.equals("debug")) return Reply.now(DebugVerbs.handle(core, rest, DebugRules.Source.PM, isOwner, owner()));
        if (verb.equals("watch")) return Reply.now(io.github.mojolowjo.entropybot.engine.WatchCamera.INSTANCE.command(rest));           // camera v1: never busy
        if (verb.equals("surface")) return Reply.now(io.github.mojolowjo.entropybot.surface.SurfaceExport.INSTANCE.command(rest));     // 0.19.3: never busy
        if (verb.equals("mouse")) return Reply.now(io.github.mojolowjo.entropybot.engine.WindowCare.INSTANCE.mouseCommand(rest));     // B7e E1: never busy
        if (verb.equals("status") || verb.equals("pos")) return Reply.now(statusLine(player));
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
        // B7d: the listed ores (D3) and the preferred ores (D2); the strip mine's status and ore mode never wait
        if (verb.equals("ores")) return Reply.now(rest.trim().toLowerCase().matches("^prefer\\b.*") ? StripMine.get().oresPrefer(rest) : Mining.get().ores(player, rest));
        if (verb.equals("stripmine") && rest.trim().toLowerCase().matches("^(status|ores( collect| list)?)$")) return Reply.now(StripMine.get().command(player, rest));
        if (verb.equals("restart")) return Reply.now(restartCommand(from, rest));
        if (verb.equals("area") || verb.equals("protect") || verb.equals("unprotect") || verb.equals("guard")) {
            return Reply.now(policy.command(verb, rest, isOwner, owner(), hereOf(mc, from), posOf(mc, player)));
        }
        if (verb.equals("recorder")) return Reply.now(io.github.mojolowjo.entropybot.recorder.RecorderCommand.handle(core.recorder, rest, isOwner, owner()));     // B7e E5
        // chains ("a then b"), routines by name, repeat and run (never from inside a chain)
        if (verb.equals("repeat") || verb.equals("run") || chains.isRoutine(verb) || Texts.splitChain(raw).size() > 1) {
            if (internal) return Reply.now("error: a routine step cannot start another chain (routine names inside a chain are fine)");
            if (chains.running()) return Reply.now("busy: " + chains.chainStatus() + " (pm \"stop\" first)");
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
        // memory lookups and edits never interrupt a job
        if (verb.matches("^(mark|setbase|sethome|forget|places)$")) return Reply.now(placeCommand(verb, rest, from, player));
        if (verb.equals("where")) return Reply.now(storage.where(player, rest));
        if (verb.equals("trust") || verb.equals("untrust")) return Reply.now(storage.trust(verb, rest));
        if (verb.equals("zone")) return Reply.now(DigCommands.zone(this, player, rest, from));        // B7d D1
        if (verb.equals("poi") || verb.equals("pois")) return Reply.now(poiCommand(rest, player, isOwner));
        if (verb.equals("caves")) return Reply.now(cavesCommand(rest));
        // B7c: lookups and settings that never interrupt a job
        if (verb.equals("need")) return Reply.now(crafting.need(player, rest));
        if (verb.equals("supplies")) return Reply.now(crafting.supplies(player, rest));
        if (verb.equals("recipe")) return Reply.now(crafting.recipe(player, rest));
        if (verb.equals("smelt") && Crafting.smeltInstant(rest)) return Reply.now(crafting.smelt(player, rest));   // package D: smelt jobs|mode|forget never wait
        // package B: the hotbar layout and the tool policy (settings: instant, never "busy")
        if (verb.equals("hotbar")) return Reply.now(hotbarCommand(player, rest));
        if (verb.equals("tools")) return Reply.now(toolsCommand(rest));
        // routing stage 1: route status|on|off|mode|build|dump are instant settings; "route test" is a job (below)
        if (verb.equals("route") && !RouteRules.isTest(rest)) return Reply.now(RouteCommand.instant(this, player, rest));
        // P1: restore status|forget|ignore|mode are instant; "restore now" is a job (below)
        if (verb.equals("restore") && !io.github.mojolowjo.entropybot.restore.RestoreArgs.isJob(rest)) return Reply.now(RestoreLive.INSTANCE.command(player, rest));
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
        // P2: gather (its status and sources answer at once; a running gather owns the bot between its steps too)
        if (verb.equals("gather")) return gathering.command(from, rest, raw, l, player);
        if (!internal && gathering.running() && Texts.isBuiltin(verb) && !verb.equals("find") && !verb.equals("recipe") && !verb.equals("close")) {
            return Reply.now("busy: " + gathering.statusText() + " (pm \"stop\" first)");
        }
        if (verb.equals("allow") || verb.equals("deny") || verb.equals("allowed")) return Reply.now(allowCommand(verb, rest, isOwner));
        if (verb.equals("b") || verb.equals("baritone")) return Reply.now(BaritoneVerb.run(rest, isOwner, owner()));
        boolean known = Texts.MOD_JOB_VERBS.contains(verb) || Texts.MOD_VERBS.contains(verb);
        if (!known) return Reply.now(Hints.unknown(verb));
        // "twerk" while twerking switches it off (a toggle, so not "busy"); farm settings are instant even mid-job
        if (verb.equals("twerk") && jobs.running() && jobs.job.type.equals("twerk")) return Reply.now(jobs.startTwerk(rest));
        if (verb.equals("farm") && FarmCommand.instant(rest)) return Reply.now(crafting.farm(player, rest));
        if (verb.equals("chop") && Chopping.instant(rest)) return Reply.now(Chopping.get().command(player, rest));    // P3: chop status
        // everything below may replace a running walk, but not a running task (find, recipe and close never interrupt)
        boolean quiet = verb.equals("find") || verb.equals("recipe") || verb.equals("close");
        if (!quiet) {
            if (jobs.running() && !jobs.walking()) return Reply.now("busy: " + jobs.job.status + " (pm \"stop\" first)");
            jobs.replaceWalk();
        }
        String r = modJob(verb, rest, from, player);
        return new Reply(r, jobs.attach("pm", from, raw, r, l));
    }

    /** The busy answers handle gives a big verb (null: not busy), so the confirm gate answers busy instead of asking. */
    private String chainBusyText() { return chains.running() ? "busy: " + chains.chainStatus() + " (pm \"stop\" first)" : null; }

    private String jobBusyText() { return jobs.running() && !jobs.walking() ? "busy: " + jobs.job.status + " (pm \"stop\" first)" : null; }

    /** The jobs the mod runs itself (B7b part 1): walks, the teleport home, the bed, wait, twerk, find. */
    String modJob(String verb, String rest, String from, LocalPlayer player) {
        switch (verb) {
            case "come" -> {
                Player target = Jobs.findPlayer(from);
                // in view, else the position the owner's companion mod sent in the last 10 s (same dimension), else ask
                int[] t = target != null ? Jobs.here(target) : ownerFixPos(from);
                if (t == null) return "I can't see you from here (I'm at " + Jobs.fmt(Jobs.here(player)) + "). PM me: goto x y z";
                String why = jobs.goalAllowed(t[0], t[1], t[2]);
                if (why != null) return FenceRules.comeRefusal(why, t[0], t[2]);
                return jobs.startTravel("goto " + Jobs.fmt(t), "coming to " + from, null, null, false);
            }
            case "follow" -> {
                // the fence: the player has to be inside an area now, and the watch stops the follow when they leave
                String name = rest.isEmpty() ? from : rest;
                // not in view but the owner's companion mod sends a fresh position: walk to it, again as it moves
                // (only for the sender themselves: another player can't make the bot walk to the owner)
                if (name.equalsIgnoreCase(from) && Jobs.findPlayer(name) == null) {
                    int[] fix = ownerFixPos(name);
                    if (fix != null) return jobs.startFollowFix(name, from, fix);
                }
                Player target = fenceOn() ? Jobs.findPlayer(name) : null;
                if (target != null) {
                    int[] t = Jobs.here(target);
                    if (jobs.goalAllowed(t[0], t[1], t[2]) != null) return FenceRules.followRefusal(name, t[0], t[2]);
                }
                String r = jobs.startTravel("follow player " + name, "following " + name, null, null, false);
                if (r.startsWith("ok") && fenceOn()) jobs.followWatch = new String[]{name, from};
                if (jobs.job != null) jobs.job.done = true;        // a follow never "arrives"; nobody waits for it
                return r;
            }
            case "goto" -> {
                // S1: "goto me" (or the sender's own name) = "come" from that sender
                if (Texts.gotoMeansCome(rest, from)) return modJob("come", "", from, player);
                if (!rest.matches("^-?\\d+ -?\\d+ -?\\d+$") && !rest.matches("^-?\\d+ -?\\d+$")) return "usage: goto x y z  (or goto x z)";
                return jobs.startTravel("goto " + rest, "going to " + rest, null, null, false);
            }
            case "spawn", "bed" -> { return jobs.startSetSpawn(player); }
            case "home" -> { return jobs.startHome(player); }
            case "go", "base" -> {
                if (verb.equals("go") && rest.trim().toLowerCase().matches("^poi\\s+\\d+$")) return storage.goPoi(player, Integer.parseInt(rest.trim().split("\\s+")[1]));
                String name = verb.equals("go") ? rest.toLowerCase() : "base";
                JsonObject pos = core.knowledge.places().get(name);
                if (pos == null) return "I have no place called " + name + " - next: " + Hints.placeFix(name);
                String dim = Jobs.dimOf(pos);
                int[] p = Jobs.pos(pos);
                if (!dim.equals(Guard.dimOf(player.level())) && !jobs.tpWorth(player, p, dim)) return name + " is in " + dim;
                return jobs.startTravel("goto " + Jobs.fmt(p), "going to " + name, p, dim, false);
            }
            case "route" -> { return RouteTestRun.start(this, jobs, storage, player, rest); }       // routing stage 1: route test
            case "restore" -> { return RestoreLive.INSTANCE.startNow(player, rest); }              // P1: restore now [r]
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
            case "cook" -> {                                                       // P5: cook beef 8 = smelt cooked_beef 8
                String t = io.github.mojolowjo.entropybot.farm.Cooking.smeltText(rest);
                return t == null ? io.github.mojolowjo.entropybot.farm.Cooking.USAGE : crafting.smelt(player, t);
            }
            case "get" -> { return crafting.get(player, rest); }
            case "restock" -> { return crafting.restock(player); }
            case "farm" -> { return crafting.farm(player, rest, from); }
            case "compact" -> { return crafting.compact(player, rest, from); }
            case "infuse" -> { return crafting.infuse(player, rest); }           // package E: the infusion altar
            // B7d: digging and mining (D1 dig/build/place, D2 the strip mine, D3 mine/caves/explore)
            case "dig" -> { return DigCommands.dig(this, player, rest, from); }
            case "build" -> { return DigCommands.build(this, player, rest, from); }
            case "place" -> { return DigCommands.place(this, player, rest, from); }
            case "stripmine" -> { return StripMine.get().command(player, rest); }
            case "mine" -> {
                if (rest.trim().toLowerCase().matches("^strip\\b.*")) return StripMine.get().mineStrip(player, rest);
                return Mining.get().mine(player, rest, s -> null);
            }
            case "explore" -> { return Mining.get().explore(player, rest); }
            case "chop" -> { return Chopping.get().command(player, rest); }             // P3: fell trees, pick up, replant
            case "upgrade" -> { return crafting.upgrade(player, rest); }         // package E: the essence tiers
            case "recipe" -> { return crafting.recipe(player, rest); }
            case "need" -> { return crafting.need(player, rest); }
            case "supplies" -> { return crafting.supplies(player, rest); }
            default -> { return Hints.unknown(verb); }
        }
    }

    // ---- places (B7b: the mod's knowledge store, places.json) ----

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
            int[] fix = ownerFixPos(from);
            if (fix != null) return new PolicyCommands.Pos(fix[0], fix[1], fix[2], Guard.dimOf(mc.level));
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
            // package H: at most Limits.PLACES named places (a known name may always move)
            String full = io.github.mojolowjo.entropybot.memory.Limits.full(places.containsKey(name), places.size(), io.github.mojolowjo.entropybot.memory.Limits.PLACES,
                    "places", "forget one first (forget <name>; \"places\" lists them)");
            if (full != null) return full;
            // a direction word makes any place a mine ("mark deepmine north": "mine strip ... at deepmine")
            List<String> args = new ArrayList<>();
            String dirWord = null;
            for (String a : parts.subList(Math.min(1, parts.size()), parts.size())) {
                if (DIRS.containsKey(a.toLowerCase())) dirWord = a.toLowerCase();
                else args.add(a);
            }
            PolicyCommands.Pos pos = resolvePos(mc, verb.equals("setbase") ? rest : String.join(" ", args), from);
            if (pos == null) return "I can't see you - come closer or give coordinates - next: mark " + name + " x y z";
            String dir = null;
            if (name.equals("mine") || dirWord != null) {
                Player who = from != null ? Jobs.findPlayer(from) : null;
                // the position came from the companion mod, which doesn't know which way they face: they must say it
                if (dirWord == null && who == null && from != null && ownerFixPos(from) != null
                        && !String.join(" ", args).trim().matches("^-?\\d+ -?\\d+ -?\\d+$"))
                    return "error: I can't see which way you're facing - say it: mark " + name + " north|south|east|west";
                dir = dirWord != null ? dirWord : dirFromYaw((who != null ? who : player).getYRot());
                // package A: a mine starts where I can stand and may walk to (solid rock, given by coordinates, can't be reached)
                String notHere = mineSpotReason(mc, pos);
                if (notHere != null) return MineSpot.refusal(pos.x() + " " + pos.y() + " " + pos.z(), notHere);
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

    /** Null when a mine may start at pos (a loaded spot I can stand in, inside the fence), else why not; unloaded: null (can't look). */
    String mineSpotReason(Minecraft mc, PolicyCommands.Pos pos) {
        if (mc.level == null || !pos.dim().equals(Guard.dimOf(mc.level))) return null;
        BlockPos feet = new BlockPos(pos.x(), pos.y(), pos.z());
        if (!mc.level.isLoaded(feet)) return null;
        MineSpot.Cell[] c = new MineSpot.Cell[2];
        for (int y = 0; y < 2; y++) {
            BlockPos q = feet.above(y);
            net.minecraft.world.level.block.state.BlockState st = mc.level.getBlockState(q);
            net.minecraft.world.phys.shapes.VoxelShape shape = st.getCollisionShape(mc.level, q);
            // a carpet or a rail is walked over, as in the bridge's thinBlock
            boolean solid = !shape.isEmpty() && shape.max(net.minecraft.core.Direction.Axis.Y) > 0.1875;
            c[y] = new MineSpot.Cell(MineSpot.blockName(st.getBlock().getDescriptionId()), solid, !st.getFluidState().isEmpty());
        }
        BlockPos below = feet.below();
        String r = MineSpot.standReason(c[0], c[1], !mc.level.getBlockState(below).getCollisionShape(mc.level, below).isEmpty(), pos.x() + " " + pos.y() + " " + pos.z());
        return r != null ? r : jobs.goalAllowed(pos.x(), pos.y(), pos.z());
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
        return FenceRules.areaGap(policy.areas(), x, y, z, dim);
    }

    String statusLine(LocalPlayer p) {
        String s = (int) Math.floor(p.getX()) + " " + (int) Math.floor(p.getY()) + " " + (int) Math.floor(p.getZ())
                + " | health " + Math.round(p.getHealth()) + "/20 | food " + p.getFoodData().getFoodLevel() + "/20";
        if (jobs.running()) s += " | " + jobs.job.status;
        if (gathering.running()) s += " | " + gathering.statusText();
        JsonObject mem = memoryBlock();
        if (mem.has("state") && "broken".equals(mem.get("state").getAsString())) s += " | a note file was broken at start (PM memory)";
        return s;
    }

    /** B7e: state.json's "memory" block (the stores' health), refreshed at most every 20 s (it reads ~14 files' times). */
    private JsonObject memoryBlock;
    private long memoryBlockAt = -1;
    static final long MEMORY_BLOCK_MS = 20_000;

    JsonObject memoryBlock() {
        long now = System.currentTimeMillis();
        if (memoryBlock == null || now - memoryBlockAt >= MEMORY_BLOCK_MS) {
            try {
                memoryBlock = MemoryCommand.stateBlock(core, this);
            } catch (RuntimeException e) {
                memoryBlock = new JsonObject();
                memoryBlock.addProperty("state", "ok");
                memoryBlock.addProperty("problem", "couldn't look: " + e);
            }
            memoryBlockAt = now;
        }
        return memoryBlock;
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
        lastStop = System.currentTimeMillis();         // package D: no furnace pickup for a while after "stop"
        String routine = chains.clear();
        String gathered = gathering.stop("stopped by \"stop\"");
        if (routine == null && gathered != null) routine = "gather " + gathered;
        jobs.followWatch = null;
        jobs.followFix = null;
        if (jobs.running()) jobs.finish("stopped");
        IBaritone mb = Jobs.baritone();
        if (mb != null) io.github.mojolowjo.entropybot.baritone.SafetyNet.cancel(mb);     // also ends a raw "b pause"
        io.github.mojolowjo.entropybot.baritone.SafetyNet.INSTANCE.restore();
        try {
            IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (b != null) b.getPathingBehavior().cancelEverything();
        } catch (Throwable ignored) {}
        core.guard.core.releaseAll(core.token);
        Minecraft mc = Minecraft.getInstance();
        mc.options.keyUse.setDown(false);
        mc.options.keyShift.setDown(false);
        return "ok: stopped everything" + (routine != null ? " (including " + routine + ")" : "") + ", breaking off" + chains.holdAutominer();
    }

    String setDefence(String text) {
        String a = text == null ? "" : text.trim().toLowerCase();
        if (a.matches("creepers?(\\s.*)?")) return io.github.mojolowjo.entropybot.engine.CreeperSetting.command(a.replaceFirst("^creepers?", ""), brainStore.data(), () -> brainStore.changed(core.tick()), core.reflexes::creeperMode, core.reflexes::setCreeperMode);     // B7e C
        if (a.matches("hostile(\\s.*)?")) return io.github.mojolowjo.entropybot.engine.Hostility.INSTANCE.command(a.replaceFirst("^hostile", ""));    // 0.19.1
        if (a.equals("on") || a.equals("off")) core.reflexes.setDefence(a.equals("on"));
        return "self-defence is " + (core.reflexes.defence() ? "ON (fights monsters and the " + io.github.mojolowjo.entropybot.engine.Hostility.INSTANCE.ids().size()
                + " mobs on the hostile list, hits back at whatever hits me, never pets or players, avoids creepers, retreats under 6 health)" : "OFF");
    }

    String reconnectCommand(String rest) {
        boolean on = rest.toLowerCase().contains("on");
        brainStore.data().addProperty("reconnect", on);
        saved();
        core.reconnect.setOn(on);
        return "ok: reconnecting after a kick is " + (on ? "on (after 1, 5, 15 min, then every 30 min for 24 h; 3 an hour at most)" : "off");
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
        // package H: the allow list holds at most Limits.ALLOWED players
        String full = verb.equals("allow") ? io.github.mojolowjo.entropybot.memory.Limits.full(kept.size() < list.size(), kept.size(),
                io.github.mojolowjo.entropybot.memory.Limits.ALLOWED, "players on my allow list", "deny one first (\"allowed\" lists them)") : null;
        if (full != null) return full;
        if (verb.equals("allow")) kept.add(rest);
        pmStore.data().add("allowed", kept);
        pmStore.changed(core.tick());
        refused.remove(rest.toLowerCase());
        return verb.equals("allow") ? "ok, I now take orders from " + rest : "ok, I no longer take orders from " + rest;
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
            int[] fix = ownerFixPos(from);         // out of view: the position their companion mod sent
            if (fix != null) return new PolicyCommands.Pos(fix[0], fix[1], fix[2], Guard.dimOf(mc.level));
        }
        return posOf(mc, mc.player);
    }

    /**
     * The block position the companion mod last sent for {@code who} (the owner), or null: not that player, older than
     * 10 s, another dimension than the bot's, or no owner.json. Never moves the bot on its own: callers check the fence.
     */
    int[] ownerFixPos(String who) {
        Minecraft mc = Minecraft.getInstance();
        return who == null || mc.level == null ? null : ownerFix.pos(who, Guard.dimOf(mc.level));
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
        gathering.stop("the bot died");                    // P2: a chain set aside by the death runs the gather again after the corpse
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
        execCmd(mc, cmd, id);
    }

    /** One cmd.json command (from the file, or from the fast channel): run it, and its answer goes to cmdResult. */
    private void execCmd(Minecraft mc, JsonObject cmd, String id) {
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
        cmdResult(id, type, text, r.text());
    }

    private void cmdResult(String id, String type, String text, String result) {
        LOG.info("[entropybot] cmd {} ({} {}) -> {}", id, type, text, Texts.cmdLogged(type, text, result));
        // package G: a fast-channel command answers its caller only; state.json's lastCmdId/lastResult stay the
        // cmd.json file's (the dashboard polls them for its own command)
        java.util.function.Consumer<String> fastReply = fastReplies.remove(id);
        if (fastReply != null) {
            fastReply.accept(result);
            return;
        }
        lastCmdId = id;
        lastResult = result;
    }

    // ---- package G: the fast channel (fast/FastServer: 127.0.0.1 only, the dashboard's key) ----

    private io.github.mojolowjo.entropybot.fast.FastChannel fast;
    private long fastSeq;
    /**
     * The fast channel's callers waiting for a command's answer, by a token the mod makes per request ("fast#N", never
     * the caller's id, so no caller collides with another or with a cmd.json id); the oldest go when it overflows.
     */
    private final Map<String, java.util.function.Consumer<String>> fastReplies = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, java.util.function.Consumer<String>> e) { return size() > 64; }
    };

    /** The game side of the fast channel; FastServer calls these on the game thread only. */
    private final class FastHandler implements io.github.mojolowjo.entropybot.fast.FastServer.Handler {
        @Override
        public void command(JsonObject cmd, java.util.function.Consumer<String> reply) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                reply.accept("error: not in a world (title screen or disconnected)");
                return;
            }
            String token = "fast#" + (++fastSeq);
            fastReplies.put(token, reply);
            execCmd(mc, cmd, token);
        }

        @Override
        public String busy(boolean withChain) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return null;
            if (jobs.running()) return "job " + jobs.job.status;
            if (withChain && chains != null && chains.running()) return "chain " + chains.chainStatus();
            if (withChain && gathering.running()) return "chain " + gathering.statusText();
            baritone.api.IBaritone b = Jobs.baritone();
            if (b == null || Jobs.idle(b)) return null;
            return "baritone " + b.getPathingControlManager().mostRecentInControl().map(pr -> pr.displayName()).orElse("pathing");
        }

        @Override
        public JsonObject ping() {
            Minecraft mc = Minecraft.getInstance();
            JsonObject o = new JsonObject();
            o.addProperty("version", core.version());
            o.addProperty("inWorld", mc.level != null && mc.player != null);
            return o;
        }

        @Override
        public JsonObject state() {
            return stateJson(Minecraft.getInstance());
        }

        @Override
        public void beforeIdleAnswer() {
            writeState(Minecraft.getInstance(), core.tick());
        }

        @Override
        public JsonObject block(int x, int y, int z) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return null;
            return blockJson(mc.level, x, y, z);
        }

        @Override
        public JsonObject column(int x, int z) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return null;
            var level = mc.level;
            JsonObject o = new JsonObject();
            o.addProperty("x", x);
            o.addProperty("z", z);
            net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(x, level.getMinBuildHeight(), z);
            if (!level.isLoaded(at)) {
                o.addProperty("loaded", false);
                o.addProperty("ground", -1);
                o.add("id", com.google.gson.JsonNull.INSTANCE);
                o.add("family", com.google.gson.JsonNull.INSTANCE);
                o.addProperty("canopy", -1);
                return o;
            }
            int[] c = io.github.mojolowjo.entropybot.surface.SurfaceColumns.column(
                    io.github.mojolowjo.entropybot.surface.SurfaceExport.liveSource(level), x, z, level.getMinBuildHeight());
            o.addProperty("loaded", true);
            o.addProperty("ground", c[0]);
            if (c[0] >= level.getMinBuildHeight()) {
                o.addProperty("id", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(new net.minecraft.core.BlockPos(x, c[0], z)).getBlock()).toString());
                o.addProperty("family", io.github.mojolowjo.entropybot.surface.SurfaceFamily.FAMILIES.get(c[1]));
            } else {
                o.add("id", com.google.gson.JsonNull.INSTANCE);
                o.add("family", com.google.gson.JsonNull.INSTANCE);
            }
            o.addProperty("canopy", c[2]);
            return o;
        }
    }

    /** P4: GET /block's object (client thread). */
    static JsonObject blockJson(net.minecraft.client.multiplayer.ClientLevel level, int x, int y, int z) {
        JsonObject o = new JsonObject();
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
        boolean loaded = level.isLoaded(pos) && y >= level.getMinBuildHeight() && y < level.getMaxBuildHeight();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("loaded", loaded);
        if (!loaded) {
            o.add("id", com.google.gson.JsonNull.INSTANCE);
            o.add("family", com.google.gson.JsonNull.INSTANCE);
            o.addProperty("container", false);
            o.addProperty("protected", false);
            return o;
        }
        var st = level.getBlockState(pos);
        o.addProperty("id", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
        o.addProperty("container", level.getBlockEntity(pos) instanceof net.minecraft.world.Container);
        io.github.mojolowjo.entropybot.guard.Guard g = io.github.mojolowjo.entropybot.guard.Guard.INSTANCE;
        boolean prot = g.isProtectedBlock(st.getBlock())
                || g.core.policy().protectAt(io.github.mojolowjo.entropybot.guard.Guard.dimOf(level), x, y, z) != null;
        o.addProperty("protected", prot);
        o.addProperty("family", io.github.mojolowjo.entropybot.surface.SurfaceFamily.FAMILIES.get(
                io.github.mojolowjo.entropybot.surface.SurfaceExport.familyOfState(st)));
        return o;
    }

    /** True while the fast channel's server runs (for Core.features: "fast"). */
    public boolean fastRunning() { return fast != null && fast.running(); }

    private Reply runCommand(Minecraft mc, JsonObject cmd, String type, String text, String from, String notify, String id) {
        LocalPlayer player = mc.player;
        // B7e N: the big verbs sent as their own cmd types ask first too (a trailing "confirm" runs them at once)
        if ((type.equals("dig") || type.equals("build") || type.equals("stripmine") || type.equals("area")) && (cmd == null || !cmd.has("confirmed"))) {
            ConfirmGate.Gate g = confirmGate.gate(owner(), type + " " + text, false, System.currentTimeMillis(), chainBusyText(), jobBusyText());
            if (g.reply() != null) return Reply.now(g.reply());
            String t = Texts.verbAndRest(g.line())[1];
            if (!t.equals(text)) {
                JsonObject c2 = cmd == null ? new JsonObject() : cmd.deepCopy();
                c2.addProperty("confirmed", true);
                return runCommand(mc, c2, type, t, from, notify, id);
            }
        }
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
            // B7e E1: answered by the mod itself
            case "noop" -> { return Reply.now("ok"); }
            case "recorder" -> { return Reply.now(io.github.mojolowjo.entropybot.recorder.RecorderCommand.handle(core.recorder, text, true, owner())); }
            case "baritone" -> { return Reply.now(BaritoneVerb.run(text, true, owner())); }
            case "mouse" -> { return Reply.now(io.github.mojolowjo.entropybot.engine.WindowCare.INSTANCE.mouseCommand(text)); }
            case "debug" -> { return Reply.now(DebugVerbs.handle(core, text, DebugRules.Source.LOCAL, true, owner())); }
            case "memory" -> { return Reply.now(MemoryCommand.command(core, this, text)); }
            case "pm" -> {
                String[] dv = Texts.verbAndRest(text);
                if (dv[0].equals("debug")) return Reply.now(DebugVerbs.handle(core, dv[1], DebugRules.Source.LOCAL, true, owner()));
                Reply r = handle(owner(), text, false, notifyListener(owner()));
                return new Reply(r.text() == null || r.text().isEmpty() ? "ok" : r.text(), r.pending());
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
            // B7d: the listed/preferred ores and the zone never wait for a job
            case "ores" -> { return Reply.now(text.trim().toLowerCase().matches("^prefer\\b.*") ? StripMine.get().oresPrefer(text) : Mining.get().ores(player, text)); }
            case "zone" -> { return Reply.now(DigCommands.zone(this, player, text, from)); }
            // package B: the hotbar layout and the tool policy (settings, never busy)
            case "hotbar" -> { return Reply.now(hotbarCommand(player, text)); }
            case "tools" -> { return Reply.now(toolsCommand(text)); }
            // B7b part 1: the places and the walks the mod does (cmd.from = whose spot "mark" uses, as before)
            case "mark", "setbase", "sethome", "forget", "places" -> { return Reply.now(placeCommand(type, text, from, player)); }
            // B7b part 2: the instant GUI and storage verbs never wait for a job (as the bridge's runCommand)
            case "take", "put", "close", "drop", "use", "wear", "equip", "where", "trust", "untrust", "recipe", "need", "supplies" -> { return Reply.now(modJob(type, text, owner(), player)); }
            case "spawn", "home", "base", "twerk", "find", "go", "open", "scan", "deposit", "corpse", "death", "rs", "pots",
                 "craft", "kit", "smelt", "get", "restock", "farm", "compact", "infuse", "upgrade",
                 "dig", "build", "place", "stripmine", "mine", "explore", "chop" -> {
                if (type.equals("chop") && Chopping.instant(text)) return Reply.now(Chopping.get().command(player, text));
                if (type.equals("farm") && FarmCommand.instant(text)) return Reply.now(crafting.farm(player, text));
                if (type.equals("stripmine") && text.trim().toLowerCase().matches("^(status|ores( collect| list)?)$")) return Reply.now(StripMine.get().command(player, text));
                if (type.equals("smelt") && Crafting.smeltInstant(text)) return Reply.now(crafting.smelt(player, text));
                // as the bridge's runCommand: a task makes these busy (a walk is replaced; twerk toggles; find never waits)
                if (type.equals("twerk") && jobs.running() && jobs.job.type.equals("twerk")) return Reply.now(jobs.startTwerk(text));
                if (!type.equals("find")) {
                    if (jobs.running() && !jobs.walking()) return Reply.now("error: busy with \"" + jobs.job.status + "\" - send stop first");
                    jobs.replaceWalk();
                }
                String r = modJob(type, text, owner(), player);
                Request q = jobs.attach("cmd", owner(), type + " " + text, r, notify == null ? null : notifyListener(notify));
                return new Reply(r, q);
            }
            default -> { return Reply.now(Texts.unknownType(type)); }
        }
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

    /** state.json "mobs": radius, cap. */
    static final double MOBS_RADIUS = 32;
    static final int MOBS_CAP = 48;

    /**
     * state.json's {@code mobs} (0.19.5): hostile mobs only, as the defence judges them ({@link
     * io.github.mojolowjo.entropybot.engine.Hostility#kind}: tamed/owned never count), within {@link #MOBS_RADIUS} of
     * the bot, nearest first, at most {@link #MOBS_CAP}: {@code {id, name, x, y, z, distance, target}} (one decimal;
     * target = the mob's current target is the bot).
     */
    private JsonArray mobsJson(Minecraft mc, LocalPlayer p) {
        io.github.mojolowjo.entropybot.engine.Hostility h = io.github.mojolowjo.entropybot.engine.Hostility.INSTANCE;
        boolean hurt = core.reflexes.recentlyHurt();
        List<net.minecraft.world.entity.Mob> found = mc.level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                p.getBoundingBox().inflate(MOBS_RADIUS),
                m -> m.isAlive() && m.distanceToSqr(p) <= MOBS_RADIUS * MOBS_RADIUS && h.kind(m, hurt).counts());
        // RTS later: passive mobs (animals) in their own array, not live yet:
        // List<Mob> passive = mc.level.getEntitiesOfClass(Mob.class, box, m -> m.isAlive() && !h.kind(m, hurt).counts());
        found.sort(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(p)));
        JsonArray a = new JsonArray();
        for (net.minecraft.world.entity.Mob m : found) {
            if (a.size() >= MOBS_CAP) break;
            JsonObject o = new JsonObject();
            o.addProperty("id", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).toString());
            o.addProperty("name", m.getName().getString());
            o.addProperty("x", Math.round(m.getX() * 10) / 10.0);
            o.addProperty("y", Math.round(m.getY() * 10) / 10.0);
            o.addProperty("z", Math.round(m.getZ() * 10) / 10.0);
            o.addProperty("distance", Math.round(Math.sqrt(m.distanceToSqr(p)) * 10) / 10.0);
            o.addProperty("target", m.getTarget() == p);
            a.add(o);
        }
        return a;
    }

    /**
     * Builds the state object (state.json, and GET /state on the fast channel). Fields: time, lastCmdId, lastResult,
     * inWorld, errors, memory, name, x/y/z, health, food, dead, dimension, selectedSlot, inventory, players,
     * mobs (hostile only, see {@link #mobsJson}), lookingAt, screen, container, job, defence, reflex, chain,
     * autominer, pm, restartOk, mod, guard, baritone, settings.
     */
    private void writeState(Minecraft mc, long tick) {
        if (stateFiles == null) return;
        String w = stateFiles.writeJson("state.json", stateJson(mc).toString());
        if (!w.startsWith("ok") && errors++ < 5) LOG.warn("[entropybot] state write failed: {}", w);
    }

    JsonObject stateJson(Minecraft mc) {
        JsonObject s = new JsonObject();
        s.addProperty("time", System.currentTimeMillis());
        s.addProperty("lastCmdId", lastCmdId);
        s.addProperty("lastResult", lastResult);
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            s.addProperty("inWorld", false);
            s.addProperty("reconnect", core.reconnect.statusText());     // T4: "next try to ... at 07:38 (try 4)"
            return s;
        }
        s.addProperty("inWorld", true);
        JsonArray errs = new JsonArray();
        s.add("errors", errs);
        // B7e: the mod's stores' health (state ok|backup|broken, problem, savedAt, backupAt), as the bridge's block was shaped
        try {
            s.add("memory", memoryBlock());
        } catch (RuntimeException e) {
            errs.add("memory: " + e);
        }
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
        // RTS later: players with exact x/y/z (one decimal) for the 3D page, not live yet:
        // q.addProperty("fx", Math.round(o.getX() * 10) / 10.0); ... (same for y, z)
        try {
            s.add("mobs", mobsJson(mc, p));
        } catch (RuntimeException e) {
            errs.add("mobs: " + e);
            s.add("mobs", new JsonArray());
        }
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
        // the job: the mod's (the running one, else the last one, done)
        if (jobs.job != null) s.add("job", jobs.stateJson());
        JsonObject rx = core.reflexes.status();
        JsonObject def = new JsonObject();
        def.addProperty("on", core.reflexes.defence());
        String kind = rx.get("reflex").getAsString();
        def.add("status", kind.equals("none") ? JsonNull.INSTANCE : rx.get("status"));
        s.add("defence", def);
        s.addProperty("reflex", kind);
        s.add("chain", chains.running() ? new com.google.gson.JsonPrimitive(chains.chainStatus())
                : gathering.running() ? new com.google.gson.JsonPrimitive(gathering.statusText()) : JsonNull.INSTANCE);
        // package A: the autominer's last decision, so one "status" shows what it decided and why
        try {
            JsonObject am = chains.autominerState();
            if (am != null) s.add("autominer", am);
        } catch (RuntimeException e) {
            errs.add("autominer: " + e);
        }
        JsonObject pm = new JsonObject();
        pm.addProperty("listening", true);
        pm.addProperty("owner", owner());
        JsonArray al = new JsonArray();
        for (String a : allowed()) al.add(a);
        pm.add("allowed", al);
        synchronized (pmQueue) {
            pm.addProperty("queued", pmQueue.size());
        }
        pm.addProperty("outbox", outbox.size());
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
        // package C: the values the dashboard's settings page shows (SettingsBlock)
        try {
            s.add("settings", settingsJson());
        } catch (RuntimeException e) {
            errs.add("settings: " + e);
        }
        return s;
    }

    /** state.json's "settings" block (package C): commands.json and areas.json plus the live values. */
    private JsonObject settingsJson() {
        String mode = null;
        try {
            JsonElement m = core.guardStatus().get("mode");
            if (m != null && !m.isJsonNull()) mode = m.getAsString();
        } catch (RuntimeException ignored) {}
        SettingsBlock.Live live = new SettingsBlock.Live(policy.strict(), mode, core.reflexes.defence(), core.reconnect.on(), orePrefer(),
                chains.running() ? chains.name() : null, chains.running() ? chains.chainStatus() : null);
        return SettingsBlock.build(brainStore.data(), areaStore.data(), live);
    }

    /** The policy as areas.json holds it: {areas, protect, strict, corner1} (the miner's area checks read it). */
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

    @Override public Reply dispatchAuto(String from, String text, Listener l) { return handle(from, text, false, true, l); }

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

    @Override public boolean busy() { return requests.busy(); }

    @Override public int freeSlots() {
        LocalPlayer p = Minecraft.getInstance().player;
        int n = 0;
        if (p != null) for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) n++;
        return n;
    }

    /** B7e (E1): the mod's own count (free slots plus junk a deposit puts away). */
    @Override public int bagRoom() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? 0 : io.github.mojolowjo.entropybot.storage.BagRoom.count(Storage.slots(p), storage.keeps());
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

    /** B7d D2: the mod keeps it (commands.json). */
    @Override public String orePrefer() { return StripMine.get().orePrefer(); }

    @Override public JsonObject minePlace() { return core.knowledge.places().get("mine"); }

    @Override public boolean inAreas(String dim, int x, int z) { return policy.inAreas(dim, x, z); }

    @Override public String dim() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? "" : Guard.dimOf(mc.level);
    }

    @Override public void saved() { brainStore.changed(core.tick()); }

    /** Wave 1: where the bot stands (a chain's retry after a fight walks back to the step's spot). */
    @Override public int[] pos() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null || p.isDeadOrDying() ? null : Jobs.here(p);
    }

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
            String r = io.github.mojolowjo.entropybot.api.BotAPI.check(dim, x, y, z, action);
            // wave 1 (item 3): the guard only knows boxes; the digging never breaks a block next to water or lava
            Minecraft mc = Minecraft.getInstance();
            String liquid = "break".equalsIgnoreCase(action) && mc.level != null && dim.equals(io.github.mojolowjo.entropybot.guard.Guard.dimOf(mc.level))
                    ? io.github.mojolowjo.entropybot.guard.Guard.liquidNextTo(mc.level, x, y, z) : null;
            return GuardCore.checkReply(r, action, liquid);
        }
    }

    @SuppressWarnings("unused")
    private static JsonObject parse(String s) { return JsonParser.parseString(s).getAsJsonObject(); }
}
