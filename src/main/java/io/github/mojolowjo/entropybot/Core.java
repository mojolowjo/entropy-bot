package io.github.mojolowjo.entropybot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.baritone.BaritoneHook;
import io.github.mojolowjo.entropybot.engine.EngineProcess;
import io.github.mojolowjo.entropybot.engine.Reflexes;
import io.github.mojolowjo.entropybot.events.EventRing;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.guard.MixinFlags;
import io.github.mojolowjo.entropybot.io.BotFiles;
import io.github.mojolowjo.entropybot.cave.Caves;
import io.github.mojolowjo.entropybot.memory.Knowledge;
import io.github.mojolowjo.entropybot.poi.PoiScanner;
import io.github.mojolowjo.entropybot.poi.Pois;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/** Everything the mod runs, wired together; {@code BotAPI} is the static face of this. */
public final class Core {
    private static final Logger LOG = LogUtils.getLogger();
    public static final Core INSTANCE = new Core();
    public static final String MODID = "entropybot";

    public final EventRing events = new EventRing();
    public final Guard guard = Guard.INSTANCE;
    public final BaritoneHook baritone = new BaritoneHook();
    public final EngineProcess engine = new EngineProcess();
    public final Knowledge knowledge = new Knowledge();
    public final Pois pois = new Pois();
    public final Caves caves = new Caves();
    /** B7d (D3): the listed ores and the explored chunks (ores.json, explored.json; moved over from memory.json once). */
    public final io.github.mojolowjo.entropybot.cave.MineNotes mineNotes = new io.github.mojolowjo.entropybot.cave.MineNotes();
    public final io.github.mojolowjo.entropybot.engine.Reconnect reconnect = new io.github.mojolowjo.entropybot.engine.Reconnect(events);
    public final PoiScanner poiScanner = new PoiScanner(pois, events);
    public final Reflexes reflexes = new Reflexes(events, engine, knowledge);
    public final io.github.mojolowjo.entropybot.commands.Commands commands = new io.github.mojolowjo.entropybot.commands.Commands(this);
    public final String token = UUID.randomUUID().toString();
    private volatile BotFiles files;
    private long tick;
    private boolean ready, inWorld;
    /** B7b: the ground the bot has had loaded, painted into map tiles for the dashboard. */
    private io.github.mojolowjo.entropybot.map.TerrainMap terrain;
    private int errors;
    /** B7e (E5): the flight recorder; NONE until E5 installs its own in the ready block. Read by E1's debug verbs. */
    public volatile io.github.mojolowjo.entropybot.recorder.Recorder recorder = io.github.mojolowjo.entropybot.recorder.Recorder.NONE;

    private Core() {
        guard.attachEvents(events);
    }

    public String version() {
        return ModList.get().getModContainerById(MODID).map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
    }

    public BotFiles files() { return files; }

    public long tick() { return tick; }

    /** Once per client tick, from the mod's event listener. Never lets an exception out. */
    public void onClientTick() {
        try {
            tick++;
            events.setTick(tick);
            guard.core.tick(tick);
            Minecraft mc = Minecraft.getInstance();
            reconnect.tick(tick);                         // B6: also outside a world
            commands.tick(tick);                          // B7a: state.json says "not in a world" too
            if (mc.level == null) {
                if (inWorld && terrain != null) terrain.flushAll();
                if (inWorld && ready) mineNotes.flush();      // S1: the miner's notes are written when leaving the world
                if (inWorld) io.github.mojolowjo.entropybot.engine.WatchCamera.INSTANCE.leftWorld();     // camera v1/v2: the old view and cap back
                if (inWorld) io.github.mojolowjo.entropybot.watchview.TunnelView.INSTANCE.leftWorld();   // camera v2: known air saved
                if (inWorld) io.github.mojolowjo.entropybot.watchview.SeenSampler.INSTANCE.leftWorld();  // watch seen: seen faces saved
                if (inWorld) io.github.mojolowjo.entropybot.watchview.SkyScanner.INSTANCE.leftWorld();   // 0.17.1: the surface scan forgotten
                if (inWorld) io.github.mojolowjo.entropybot.surface.SurfaceExport.INSTANCE.leftWorld();   // 0.19.3: the surface export's files wiped
                if (inWorld) io.github.mojolowjo.entropybot.routing.RouteRuntime.INSTANCE.leftWorld();   // routing R2: stop + save
                inWorld = false;
                return;
            }
            inWorld = true;
            if (!ready) {
                files = new BotFiles(mc.gameDirectory.toPath().resolve(MODID));
                guard.ensureProtectedBlocks();
                LOG.info("[entropybot] knowledge: {}", knowledge.load(files));
                LOG.info("[entropybot] points of interest: {}", pois.load(files));
                LOG.info("[entropybot] caves: {}", caves.load(files));
                LOG.info("[entropybot] mine notes: {}", mineNotes.load(files, new BotFiles(mc.gameDirectory.toPath().resolve("kubejs/bridge"))));
                // S1: and when the game quits from inside a world (no tick runs after that)
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try { mineNotes.flush(); } catch (Throwable ignored) {}
                    try { io.github.mojolowjo.entropybot.watchview.TunnelView.INSTANCE.save(true); } catch (Throwable ignored) {}
                    try { io.github.mojolowjo.entropybot.watchview.SeenSampler.INSTANCE.save(true); } catch (Throwable ignored) {}
                }, "entropybot-mine-notes"));
                io.github.mojolowjo.entropybot.watchview.TunnelView.INSTANCE.load(files.root());
                LOG.info("[entropybot] watch tunnel: {}", io.github.mojolowjo.entropybot.watchview.TunnelView.INSTANCE.fileNote());
                io.github.mojolowjo.entropybot.watchview.SeenSampler.INSTANCE.load(files.root());
                LOG.info("[entropybot] watch seen: {}", io.github.mojolowjo.entropybot.watchview.SeenSampler.INSTANCE.fileNote());
                LOG.info("[entropybot] commands: {}", commands.init(mc, files));
                terrain = new io.github.mojolowjo.entropybot.map.TerrainMap(files.root().resolve("map"));
                recorder = new io.github.mojolowjo.entropybot.recorder.FlightRecorder(files.root().resolve("recorder"), events);   // B7e E5
                ready = true;
                LOG.info("[entropybot] {} ready: guard {}, floor {}; mixins: click={} place={} astar={} watch={} camerapos={} restorehook={} (target present={}); folder {}",
                        version(), guard.core.mode().name().toLowerCase(), guard.floorInfo(),
                        MixinFlags.clickApplied, MixinFlags.placeApplied, MixinFlags.astarApplied, MixinFlags.watchApplied, MixinFlags.cameraPosApplied,
                        MixinFlags.restoreApplied, MixinFlags.astarTargetPresent, files.root());
                events.push("job", "mod ready " + version(), null);
            }
            if (!baritone.hooked() && tick % 20 == 0) baritone.tryHook(events, engine);
            reflexes.tick(tick);
            knowledge.flushIfDue(tick);
            // package H: what the caps dropped, in the log
            String pruned = knowledge.takePruneNote();
            if (pruned != null) LOG.info("[entropybot] knowledge: {}", pruned);
            pruned = caves.takePruneNote();
            if (pruned != null) LOG.info("[entropybot] caves: {}", pruned);
            poiScanner.tick(tick);
            try { recorder.tick(tick); } catch (RuntimeException e) { if (errors++ <= 5) LOG.warn("[entropybot] recorder: {}", e.toString()); }
            if (terrain != null) {
                terrain.tick(tick);
                if (tick % 12000 == 0) LOG.info("[entropybot] {}", terrain.status());
            }
            pois.flushIfDue(tick);
            caves.flushIfDue(tick);
            mineNotes.flushIfDue(tick);
            pruned = mineNotes.takePruneNote();
            if (pruned != null) LOG.info("[entropybot] mine notes: {}", pruned);
            pruned = mineNotes.takeWriteError();
            if (pruned != null) LOG.warn("[entropybot] mine notes: couldn't write {}", pruned);
            if (baritone.hooked() && tick % 20 == 0) {
                List<String> turned = baritone.enforceSettings();
                if (!turned.isEmpty()) {
                    LOG.warn("[entropybot] Baritone settings drifted, turned off again: {}", turned);
                    events.push("job", "settings turned off again: " + String.join(", ", turned), null);
                }
            }
            io.github.mojolowjo.entropybot.baritone.SafetyNet.INSTANCE.tick(tick);
            io.github.mojolowjo.entropybot.engine.WindowCare.INSTANCE.tick(tick, commands.jobs.running(), reflexes.hold());
            io.github.mojolowjo.entropybot.engine.WatchCamera.INSTANCE.tick();
            io.github.mojolowjo.entropybot.watchview.TunnelView.INSTANCE.tick(tick);      // camera v2: known air (never throws)
            io.github.mojolowjo.entropybot.watchview.SeenSampler.INSTANCE.tick(tick);     // watch seen: the visible-faces sampler (never throws)
            io.github.mojolowjo.entropybot.watchview.SkyScanner.INSTANCE.tick(tick);      // 0.17.1: the tunnel view's surface scan (never throws)
            io.github.mojolowjo.entropybot.surface.SurfaceExport.INSTANCE.tick();      // 0.19.3: the surface export for the dashboard (never throws)
            io.github.mojolowjo.entropybot.routing.RouteRuntime.INSTANCE.tick(tick);     // routing R2: the map builder (never throws)
        } catch (Throwable t) {
            errors++;
            if (errors <= 5 || errors % 1200 == 0) LOG.error("[entropybot] tick error #{}: {}", errors, t.toString());
        }
    }

    public JsonArray features() {
        JsonArray a = new JsonArray();
        a.add("files");
        if (baritone.hooked()) a.add("events");
        a.add("guard:" + guard.core.mode().name().toLowerCase());
        if (MixinFlags.clickApplied) a.add("guard:click");
        if (MixinFlags.watchApplied) a.add("camera:watch");
        if (MixinFlags.cameraPosApplied) a.add("camera:tunnel");
        if (MixinFlags.placeApplied) a.add("guard:place");
        if (MixinFlags.astarApplied) a.add("guard:astar");
        if (MixinFlags.farmlandApplied) a.add("baritone:farmland");
        if (MixinFlags.restoreApplied) a.add("restore");
        if (ready) a.add("reflexes");
        if (ready) a.add("knowledge");
        if (ready) a.add("poi");
        if (ready) a.add("cave");
        a.add("reconnect");
        if (ready) a.add("safetynet");
        if (ready && recorder.enabled()) a.add("recorder");
        if (commands.ready()) a.add("commands");
        if (commands.ready()) a.add("jobs:walk");
        if (commands.ready()) a.add("jobs:storage");
        if (commands.ready()) a.add("hotbar");        // package B: the layout keeper
        if (commands.ready()) a.add("furnaces");
        if (commands.ready()) a.add("mystical");     // package E: infuse, upgrade, crystal batching     // package D: remembered furnace jobs, smelt mode, the planner fixes
        if (commands.fastRunning()) a.add("fast");          // package G: the 127.0.0.1 fast channel is up
        if (terrain != null) a.add("terrain");
        if (baritone.engineRegistered() && !engine.disabled()) a.add("engine");
        if (baritone.hooked()) a.add("settings:fixed");
        if (io.github.mojolowjo.entropybot.routing.RouteRuntime.INSTANCE.available()) a.add("route:map");
        return a;
    }

    public JsonObject guardStatus() {
        JsonObject o = guard.core.status(guard.floorSize(), MixinFlags.astarApplied, MixinFlags.clickApplied && MixinFlags.placeApplied);
        o.addProperty("floorReady", guard.floorReady());
        o.addProperty("baritoneHooked", baritone.hooked());
        if (baritone.lastError() != null) o.addProperty("baritoneError", baritone.lastError());
        return o;
    }
}
