package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.brain.Brain;
import io.github.mojolowjo.entropybot.brain.BrainEnv;
import io.github.mojolowjo.entropybot.brain.BrainState;
import io.github.mojolowjo.entropybot.brain.CopyRules;
import io.github.mojolowjo.entropybot.brain.DecisionLog;
import io.github.mojolowjo.entropybot.brain.SupplyCheck;
import io.github.mojolowjo.entropybot.camp.DayNight;
import io.github.mojolowjo.entropybot.engine.Reflexes;
import io.github.mojolowjo.entropybot.vocab.GameStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * B1, the game side of the brain (docs/BRAIN_PLAN.md 4.6): fills {@link BrainState} from what the mod already has
 * (the player, the reflexes, the chains and jobs, the stock view, the places, the companion's owner.json), starts the
 * brain's jobs as a chain named "brain" (the owner hears its end like any chain's), writes brain.json and the decision
 * log under {@code entropybot/brain/}. Ticked every 40 client ticks from {@link Commands}' tick. Lives in the commands
 * package for the package-private parts of Commands it reads.
 *
 * <p>Loader notes: only vanilla client getters (LocalPlayer health/food/inventory, ItemStack DataComponents.FOOD and
 * damage, Level.getBrightness(LightLayer.BLOCK), Level.getDayTime, ClientPacketListener.getPlayerInfo); the tick comes
 * through Commands (NeoForge ClientTickEvent.Post via Core; Fabric: END_CLIENT_TICK). No mixin, no Baritone.
 * Errors: the loop catches everything (Brain.tick); brain.json write failures are counted, never fatal.
 */
public final class BrainRuntime implements BrainEnv {
    private static final Logger LOG = LogUtils.getLogger();
    public static final String CHAIN = "brain";

    private final Commands c;
    public final Brain brain;
    private final DecisionLog log;
    private final CopyRules.Tracker copy = new CopyRules.Tracker();
    private String lastEnd;
    private long stockAt = -1, ownerMtime = Long.MIN_VALUE;
    private String stage = "nothing";
    private CopyRules.Report ownerReport;
    private int lastPickaxes = -1;
    long stateErrors;

    BrainRuntime(Commands c) {
        this.c = c;
        this.log = new DecisionLog(new FileSink(), ZoneId.systemDefault());
        this.brain = new Brain(this);
    }

    /** The chain named "brain" runs. */
    boolean brainChainRunning() { return c.chains != null && c.chains.running() && CHAIN.equals(c.chains.name()); }

    /** How a chain ended (Chains calls it for the brain's chain). */
    void chainEnded(String name, String msg) { if (CHAIN.equals(name)) lastEnd = msg; }

    /** The owner's order while the brain's chain runs: the brain steps aside (its job stops; the owner's runs). */
    void yieldToOwner(String verb) {
        if (!brainChainRunning()) return;
        LOG.info("[entropybot] brain: steps aside for the owner's {}", verb);
        brain.yieldTo(verb);
    }

    /**
     * BRAIN_LOOP answer 4.4: the owner's dig / mine strip|cave / explore with the brain on: what it needs first, as a
     * chain "supplies then the order" with a whisper; null when nothing is short (the order runs as typed).
     */
    Chains.Reply ownerSupply(String from, String raw) {
        try {
            if (!brain.on() || c.chains.running() || c.jobs.running()) return null;
            String v = Texts.verbAndRest(raw)[0];
            if (!v.equals("dig") && !v.equals("mine") && !v.equals("explore")) return null;
            BrainState s = sense();
            SupplyCheck.Supply sup = SupplyCheck.before(raw, s, 0, 0);
            if (sup == null) return null;
            String r = c.chains.startChain(from, "supply", sup.chain() + " then " + raw, 1);
            LOG.info("[entropybot] brain: supply check for {}: {} -> {}", raw, sup.chain(), r);
            return Chains.Reply.now(r.startsWith("started") ? "ok: " + sup.why() + ", then " + raw : r);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] brain: supply check: {}", e.toString());
            return null;
        }
    }

    // ---- BrainEnv ----

    @Override public long now() { return System.currentTimeMillis(); }

    @Override public long tick() { return Core.INSTANCE.tick(); }

    @Override public BrainState sense() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        BrainState s = new BrainState();
        s.now = now();
        if (p == null || mc.level == null || p.isDeadOrDying()) {
            s.inWorld = false;
            return s;
        }
        Core core = Core.INSTANCE;
        s.parked = c.chains.parked() || c.chains.corpseBusy();
        s.health = p.getHealth();
        s.maxHealth = p.getMaxHealth();
        s.food = p.getFoodData().getFoodLevel();
        s.freeSlots = c.freeSlots();
        // the bag: food, torches, pickaxes
        int food = 0, torches = 0, picks = 0, bestPct = 0, dur = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            String id = Commands.itemId(st);
            if (st.has(DataComponents.FOOD)) food += st.getCount();
            if (id.equals("minecraft:torch")) torches += st.getCount();
            if (id.endsWith("_pickaxe")) {
                picks++;
                int left = st.isDamageableItem() ? st.getMaxDamage() - st.getDamageValue() : 1000;
                dur += left;
                bestPct = Math.max(bestPct, st.isDamageableItem() ? 100 * left / Math.max(1, st.getMaxDamage()) : 100);
            }
        }
        s.foodItems = food;
        s.torches = torches;
        s.pickaxes = picks;
        s.pickPct = bestPct;
        s.pickDurability = dur;
        s.toolBroke = lastPickaxes > 0 && picks < lastPickaxes && brainChainRunning();
        lastPickaxes = picks;
        // the stage (the stock view, every 30 s)
        if (stockAt < 0 || s.now - stockAt > 30_000) {
            stockAt = s.now;
            try { stage = GameStage.of(c.storage.stock(p).totals()); } catch (RuntimeException e) { LOG.warn("[entropybot] brain stage: {}", e.toString()); }
        }
        s.stage = stage;
        // danger: the reflexes fight, flee or retreat (the B2 threat test fed them), or one holds the bot
        Reflexes.Reflex r = core.reflexes.reflex();
        s.danger = r == Reflexes.Reflex.FIGHTING || r == Reflexes.Reflex.FLEEING || r == Reflexes.Reflex.RETREATING;
        s.dangerWhy = s.danger ? r.name().toLowerCase() : "";
        if (!s.danger && core.reflexes.hold()) {
            s.danger = true;
            s.dangerWhy = "a reflex runs (" + r.name().toLowerCase() + ")";
        }
        // night, sleep, light
        long dt = mc.level.getDayTime();
        s.night = DayNight.night(dt);
        JsonObject root = c.brainData();
        s.sleepAuto = !root.has("sleepAuto") || root.get("sleepAuto").getAsBoolean();
        boolean others = mc.hasSingleplayerServer();
        for (Player o : mc.level.players()) if (o != p && o.isSleeping()) others = true;
        s.othersSleeping = others;
        s.litHere = mc.level.getBrightness(LightLayer.BLOCK, p.blockPosition()) >= 8;
        s.botPos = Jobs.here(p);
        // the owner
        String owner = c.owner();
        s.ownerOnline = mc.getConnection() != null && mc.getConnection().getPlayerInfo(owner) != null;
        for (Player o : mc.level.players()) if (o.getGameProfile().getName().equalsIgnoreCase(owner)) s.ownerPos = new int[]{o.getBlockX(), o.getBlockY(), o.getBlockZ()};
        if (s.ownerPos == null) s.ownerPos = c.ownerFixPos(owner);
        s.released = c.vocab.released();
        // what runs
        s.menuOpen = mc.screen != null && !brainChainRunning() && !c.jobs.running();
        if (brainChainRunning()) s.ownerJob = null;         // the brain's chain: its step (a job, a gather, a carry) is the brain's
        else if (c.chains.running()) s.ownerJob = c.chains.name();
        else if (c.jobs.running()) s.ownerJob = c.jobs.job.status;
        else if (c.gathering.running()) s.ownerJob = "gather";
        else if (c.mule.running()) s.ownerJob = "carry";
        else if (c.requests.busy()) s.ownerJob = "a request";
        if (c.heldInPlace()) s.passive = core.reflexes.escort.active() ? "escorting " + core.reflexes.escort.name() : c.vocab.hold.what();
        // the owner's needs and goals
        if (root.has("needs") && root.get("needs").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("needs").entrySet()) {
                try { s.needs.add(new BrainState.NeedItem(e.getKey(), e.getValue().getAsInt(), c.vocab.haveOf(p, e.getKey()), 0)); } catch (RuntimeException ignored) {}
            }
        }
        if (root.has("goals") && root.get("goals").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("goals")) {
                try {
                    JsonObject g = e.getAsJsonObject();
                    s.goals.add(new BrainState.Goal(g.get("text").getAsString(), g.get("chain").getAsString(), g.has("at") ? g.get("at").getAsLong() : 0));
                } catch (RuntimeException ignored) {}
            }
        }
        // brain copy
        if (brain.copyOn()) s.copy = copy.update(ownerReport(), owner, s.now, brain.config().i("copyIdleS"));
        // the upkeep facts
        s.upkeep.now = s.now;
        s.upkeep.ores = c.orePrefer() == null || c.orePrefer().isEmpty() ? "any" : c.orePrefer();
        Map<String, Integer> inv = c.inventory();
        JsonObject sup = c.supplies();
        if (sup != null) for (Map.Entry<String, JsonElement> e : sup.entrySet()) {
            try { if (inv.getOrDefault(e.getKey(), 0) < e.getValue().getAsInt()) { s.upkeep.shortSupply = Texts.shortId(e.getKey()); break; } } catch (RuntimeException ignored) {}
        }
        JsonObject bd = root.has("brain") && root.get("brain").isJsonObject() ? root.getAsJsonObject("brain") : new JsonObject();
        s.upkeep.restockAt = bd.has("restockAt") ? bd.get("restockAt").getAsLong() : -1;
        if (bd.has("stripGaveUp") && bd.get("stripGaveUp").isJsonObject()) {
            JsonObject g = bd.getAsJsonObject("stripGaveUp");
            if (s.now - g.get("at").getAsLong() < io.github.mojolowjo.entropybot.brain.IdleList.GAVE_UP_MS) s.upkeep.mineGaveUp = g.get("why").getAsString();
        }
        JsonObject m = c.minePlace();
        if (m != null) {
            s.upkeep.mine = xyz(m);
            s.upkeep.mineReady = m.has("dir") && c.inAreas(m.has("dim") ? m.get("dim").getAsString() : "minecraft:overworld", s.upkeep.mine[0], s.upkeep.mine[2]);
        }
        JsonObject farm = core.knowledge.places().get("farm"), base = core.knowledge.places().get("base");
        if (farm != null) s.upkeep.farm = xyz(farm);
        if (base != null) s.basePos = xyz(base);
        return s;
    }

    static int[] xyz(JsonObject o) {
        try { return new int[]{(int) Math.floor(o.get("x").getAsDouble()), (int) Math.floor(o.get("y").getAsDouble()), (int) Math.floor(o.get("z").getAsDouble())}; }
        catch (RuntimeException e) { return null; }
    }

    /** owner.json as the copy rule reads it (held item, broken block), re-read when it changes. */
    private CopyRules.Report ownerReport() {
        try {
            if (Core.INSTANCE.files() == null) return null;
            Path f = Core.INSTANCE.files().root().resolve("owner.json");
            if (!Files.exists(f)) return null;
            long m = Files.getLastModifiedTime(f).toMillis();
            if (m != ownerMtime) {
                ownerMtime = m;
                CopyRules.Report r = CopyRules.parse(Files.readString(f, StandardCharsets.UTF_8));
                if (r != null) ownerReport = r;
            }
        } catch (IOException | RuntimeException e) {
            // caught mid-replace: the next loop reads again
        }
        return ownerReport;
    }

    @Override public String start(String chain) {
        if (c.chains.running()) return "error: " + c.chains.chainStatus() + " runs";
        lastEnd = null;
        return c.chains.startChain(c.owner(), CHAIN, chain, 1);
    }

    @Override public boolean jobRunning() { return brainChainRunning(); }

    @Override public String lastEnd() { return lastEnd; }

    @Override public void stopJob(String why) {
        if (!brainChainRunning()) return;
        c.chains.clear();
        if (c.jobs.running()) c.jobs.finish("stopped: " + why);
        LOG.info("[entropybot] brain: stopped its job ({})", why);
    }

    @Override public void whisper(String text) { c.whisper(c.owner(), text); }

    @Override public void log(String line) { LOG.info("[entropybot] {}", line); }

    @Override public JsonObject store() { return c.brainData(); }

    @Override public void saved() { c.saved(); }

    @Override public void writeState(JsonObject o) {
        try {
            if (Core.INSTANCE.files() == null) return;
            String r = Core.INSTANCE.files().writeJson("brain.json", o.toString());
            if (r != null && r.startsWith("error")) stateErrors++;
        } catch (RuntimeException e) {
            stateErrors++;
        }
    }

    @Override public DecisionLog decisions() { return log; }

    // B4: brain-config.json, brain-tree.json, brain-tree.override.json under entropybot/ (BotFiles: atomic writes)
    @Override public String readFile(String name) {
        try {
            return Core.INSTANCE.files() == null ? "error: not in a world yet" : Core.INSTANCE.files().readJson(name);
        } catch (RuntimeException e) {
            return "error: " + e;
        }
    }

    @Override public String writeFile(String name, String json) {
        try {
            return Core.INSTANCE.files() == null ? "error: not in a world yet" : Core.INSTANCE.files().writeJson(name, json);
        } catch (RuntimeException e) {
            return "error: " + e;
        }
    }

    @Override public String modVersion() {
        try { return Core.INSTANCE.version(); } catch (RuntimeException e) { return "unknown"; }
    }

    /** state.json's "brain": on, the branch, the job (null when the brain was never switched on). */
    JsonObject stateBlock() {
        JsonObject root = c.brainData();
        if (!root.has("brain")) return null;
        JsonObject o = new JsonObject();
        o.addProperty("on", brain.on());
        String part = brain.statusPart();
        if (part != null) o.addProperty("status", part);
        if (brain.lastDecision() != null) o.addProperty("branch", brain.lastDecision().branch());
        return o;
    }

    /** The decision log's files: entropybot/brain/decisions-YYYY-MM-DD.jsonl. */
    static final class FileSink implements DecisionLog.Sink {
        static Path dir() {
            return Core.INSTANCE.files() == null ? null : Core.INSTANCE.files().root().resolve("brain");
        }

        static Path file(String day) {
            Path d = dir();
            return d == null ? null : d.resolve("decisions-" + day + ".jsonl");
        }

        @Override public void append(String day, String line) throws IOException {
            Path f = file(day);
            if (f == null) throw new IOException("no bot folder yet");
            Files.createDirectories(f.getParent());
            Files.writeString(f, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        @Override public long size(String day) {
            try {
                Path f = file(day);
                return f != null && Files.exists(f) ? Files.size(f) : 0;
            } catch (IOException e) {
                return 0;
            }
        }

        @Override public List<String> days() {
            List<String> out = new ArrayList<>();
            Path d = dir();
            if (d == null || !Files.isDirectory(d)) return out;
            try (var st = Files.list(d)) {
                st.forEach(f -> {
                    String n = f.getFileName().toString();
                    if (n.startsWith("decisions-") && n.endsWith(".jsonl")) out.add(n.substring(10, n.length() - 6));
                });
            } catch (IOException e) {
                // listed again at the next day change
            }
            return out;
        }

        @Override public void delete(String day) throws IOException {
            Path f = file(day);
            if (f != null) Files.deleteIfExists(f);
        }
    }

}
