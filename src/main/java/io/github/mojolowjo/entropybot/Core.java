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
    public final PoiScanner poiScanner = new PoiScanner(pois, events);
    public final Reflexes reflexes = new Reflexes(events, engine, knowledge);
    public final String token = UUID.randomUUID().toString();
    private volatile BotFiles files;
    private long tick;
    private boolean ready;
    private int errors;

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
            if (mc.level == null) return;
            if (!ready) {
                files = new BotFiles(mc.gameDirectory.toPath().resolve(MODID));
                guard.ensureProtectedBlocks();
                LOG.info("[entropybot] knowledge: {}", knowledge.load(files));
                LOG.info("[entropybot] points of interest: {}", pois.load(files));
                LOG.info("[entropybot] caves: {}", caves.load(files));
                ready = true;
                LOG.info("[entropybot] {} ready: guard {}, floor {}; mixins: click={} place={} astar={} (target present={}); folder {}",
                        version(), guard.core.mode().name().toLowerCase(), guard.floorInfo(),
                        MixinFlags.clickApplied, MixinFlags.placeApplied, MixinFlags.astarApplied, MixinFlags.astarTargetPresent, files.root());
                events.push("job", "mod ready " + version(), null);
            }
            if (!baritone.hooked() && tick % 20 == 0) baritone.tryHook(events, engine);
            reflexes.tick(tick);
            knowledge.flushIfDue(tick);
            poiScanner.tick(tick);
            pois.flushIfDue(tick);
            caves.flushIfDue(tick);
            if (baritone.hooked() && tick % 20 == 0) {
                List<String> turned = baritone.enforceSettings();
                if (!turned.isEmpty()) {
                    LOG.warn("[entropybot] Baritone settings drifted, turned off again: {}", turned);
                    events.push("job", "settings turned off again: " + String.join(", ", turned), null);
                }
            }
        } catch (Throwable t) {
            errors++;
            if (errors <= 5 || errors % 1200 == 0) LOG.error("[entropybot] tick error #{}: {}", errors, t.toString());
        }
    }

    public JsonArray features() {
        JsonArray a = new JsonArray();
        a.add("token");
        a.add("files");
        if (baritone.hooked()) a.add("events");
        a.add("guard:" + guard.core.mode().name().toLowerCase());
        if (MixinFlags.clickApplied) a.add("guard:click");
        if (MixinFlags.placeApplied) a.add("guard:place");
        if (MixinFlags.astarApplied) a.add("guard:astar");
        if (ready) a.add("reflexes");
        if (ready) a.add("knowledge");
        if (ready) a.add("poi");
        if (ready) a.add("cave");
        if (baritone.engineRegistered() && !engine.disabled()) a.add("engine");
        if (baritone.hooked()) a.add("settings:fixed");
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
