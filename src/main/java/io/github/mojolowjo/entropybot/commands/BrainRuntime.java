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
 * damage, Level.getBrightness(LightLayer.BLOCK), Level.getDayTime, ClientPacketListener.getPlayerInfo; 0.26.1: Level.isThundering,
 * Level.canSeeSky(BlockPos) at the head cell, the guard's dry run BotAPI.check for brain copy); the tick comes
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
            if (c.chains.running() || c.jobs.running()) return null;
            String v = Texts.verbAndRest(raw)[0];
            if (!v.equals("dig") && !v.equals("mine") && !v.equals("explore")) return null;
            boolean on = brain.on();
            // 0.25.1 cave.eatFirst: also with the brain off (the owner's own habit before a cave trip)
            if (!on && !io.github.mojolowjo.entropybot.brain.PlayRules.caveTrip(raw)) return null;
            BrainState s = sense();
            SupplyCheck.Supply sup = on ? SupplyCheck.before(raw, s, 0, 0) : null;
            String eat = io.github.mojolowjo.entropybot.brain.PlayRules.eatFirst(raw, s, io.github.mojolowjo.entropybot.brain.BrainConfig.current());
            if (sup == null && eat == null) return null;
            String pre = (sup == null ? "" : sup.chain() + " then ") + (eat == null ? "" : "eat then ");
            String r = c.chains.startChain(from, "supply", pre + raw, 1);
            LOG.info("[entropybot] brain: supply check for {}: {}{} -> {}", raw, pre, eat == null ? "" : " (" + eat + ")", r);
            String why = sup == null ? eat : eat == null ? sup.why() : sup.why() + "; " + eat;
            return Chains.Reply.now(r.startsWith("started") ? "ok: " + why + ", then " + raw : r);
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
        int food = 0, torches = 0, picks = 0, bestPct = 0, dur = 0, wood = 0, coal = 0, logs = 0;
        boolean bedBag = false;
        Map<String, Integer> bag = new java.util.HashMap<>();      // 0.26.1 R8: the pickaxe materials
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            String id = Commands.itemId(st);
            if (st.has(DataComponents.FOOD)) food += st.getCount();
            if (id.endsWith("_log") || id.endsWith("_planks") || id.endsWith("_wood") || id.endsWith("_stem")) wood += st.getCount();      // 0.25.1
            if (id.equals("minecraft:torch")) torches += st.getCount();
            if (id.equals("minecraft:coal") || id.equals("minecraft:charcoal")) coal += st.getCount();      // 0.26.1 R2
            if (id.endsWith("_log") || id.endsWith("_stem")) logs += st.getCount();
            if (id.endsWith("_bed")) bedBag = true;
            bag.merge(id.substring(id.indexOf(':') + 1), st.getCount(), Integer::sum);
            if (id.endsWith("_pickaxe")) {
                picks++;
                int left = st.isDamageableItem() ? st.getMaxDamage() - st.getDamageValue() : 1000;
                dur += left;
                bestPct = Math.max(bestPct, st.isDamageableItem() ? 100 * left / Math.max(1, st.getMaxDamage()) : 100);
            }
        }
        s.foodItems = food;
        s.woodItems = wood;
        s.torches = torches;
        s.coal = coal;
        s.logs = logs;
        s.bedInBag = bedBag;
        s.pickaxes = picks;
        s.pickPct = bestPct;
        s.pickDurability = dur;
        s.armorWorn = io.github.mojolowjo.entropybot.camp.ArmorCareRules.lowest(CampCommands.armor(p));
        s.toolBroke = lastPickaxes > 0 && picks < lastPickaxes && brainChainRunning();
        lastPickaxes = picks;
        // the stage (the stock view, every 30 s)
        if (stockAt < 0 || s.now - stockAt > 30_000) {
            stockAt = s.now;
            try {
                Map<String, Integer> totals = c.storage.stock(p).totals();
                lastTotals = totals;          // 0.26.2: the feasibility facts' stock
                stage = GameStage.of(totals);
                foodStock = edible(totals);          // 0.25.1: bag + storage (the stock view)
                // 0.26.1 R1: a scan or deposit changed the stock view; 0.26.2 fix: storage only (the totals count the bag too,
                // so a /clear or any pickup cleared every NONE_IN_CHESTS record and "get food" failed 3 times into a park)
                int h = io.github.mojolowjo.entropybot.brain.FailMemory.storageHash(totals, io.github.mojolowjo.entropybot.gui.Gui.inventory(p));
                if (h != stockHash) { stockHash = h; storageSeq++; }
            } catch (RuntimeException e) { LOG.warn("[entropybot] brain stage: {}", e.toString()); }
        }
        s.stage = stage;
        s.storageSeq = storageSeq;
        s.pickMaterials = io.github.mojolowjo.entropybot.brain.PlayRules.pickMaterials(SupplyCheck.tier(stage), bag);
        s.foodStock = foodStock < 0 ? food : Math.max(foodStock, food);
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
        // 0.24.3: night safety (armour, weapon, deaths today, time, a bed near, the camp)
        try {
            java.util.List<String> worn = new java.util.ArrayList<>();
            for (var pc : CampCommands.armor(p)) if (pc.id() != null) worn.add(pc.id());
            s.armourTier = io.github.mojolowjo.entropybot.brain.NightSafety.armourTier(worn);
            int wt = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (st.isEmpty()) continue;
                String id = Commands.itemId(st);
                if (id.endsWith("_sword") || id.endsWith("_axe")) wt = Math.max(wt, io.github.mojolowjo.entropybot.brain.NightSafety.tierOf(id));
            }
            s.weaponTier = wt;
            s.shelterBlocks = io.github.mojolowjo.entropybot.camp.ShelterPlan.blocks(io.github.mojolowjo.entropybot.gui.Gui.inventory(p));
            s.deathsDay = io.github.mojolowjo.entropybot.summary.DaySummary.INSTANCE.deaths();
            s.dayTime = dt;
            // 0.26.1 R6: by day too, capped: one bed search (r 24) a minute by day, every loop at night as before
            if (s.night || s.now - bedAt > 60_000) {
                bedAt = s.now;
                bedNearDay = Jobs.findBlock(p, "_bed 24").startsWith("found");
            }
            s.bedNear = bedNearDay;
            // 0.26.1 R4: two bounded reads: the level thunders, the head cell sees the sky
            s.thundering = mc.level.isThundering();
            s.skyAbove = mc.level.canSeeSky(p.blockPosition().above());
            JsonObject camp = core.knowledge.places().get("camp");
            if (camp != null && Jobs.dimOf(camp).equals(Storage.dim())) s.campPos = Jobs.pos(camp);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] brain night safety: {}", e.toString());
        }
        // the owner
        String owner = c.owner();
        s.ownerOnline = mc.getConnection() != null && mc.getConnection().getPlayerInfo(owner) != null;
        // 0.26.1 R9: a removed or placeholder entity is no position (ownerKnown() drops the far placeholder too)
        for (Player o : mc.level.players())
            if (o.getGameProfile().getName().equalsIgnoreCase(owner) && !o.isRemoved() && o.distanceToSqr(p) < 1e10) s.ownerPos = new int[]{o.getBlockX(), o.getBlockY(), o.getBlockZ()};
        if (s.ownerPos == null) s.ownerPos = c.ownerFixPos(owner);
        s.released = c.vocab.released();
        // 0.26.1 R5: how long the owner has been offline (0 online; the clock starts when the loop first sees it)
        if (s.ownerOnline) ownerOffAt = -1;
        else if (ownerOffAt < 0) ownerOffAt = s.now;
        s.ownerOfflineMs = s.ownerOnline ? 0 : s.now - ownerOffAt;
        s.areasHere = c.inAreas(Storage.dim(), s.botPos[0], s.botPos[2]);      // 0.26.1 R1: OUTSIDE clears inside an area
        // what runs
        s.menuOpen = mc.screen != null && !brainChainRunning() && !c.jobs.running();
        if (brainChainRunning()) s.ownerJob = null;         // the brain's chain: its step (a job, a gather, a carry) is the brain's
        else if (c.chains.running()) s.ownerJob = c.chains.name();
        else if (c.jobs.running()) s.ownerJob = c.jobs.job.status;
        else if (c.gathering.running()) s.ownerJob = "gather";
        else if (c.mule.running()) s.ownerJob = "carry";
        else if (c.requests.busy()) s.ownerJob = "a request";
        if (c.heldInPlace()) {      // 0.26.1: assist alone (no hold) named itself; escort and assist are owner-bound (R5)
            boolean esc = core.reflexes.escort.active();
            s.passive = esc ? "escorting " + core.reflexes.escort.name() : c.vocab.hold != null ? c.vocab.hold.what() : "assisting";
            s.passiveOwnerBound = esc || c.assist.on();
        }
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
        s.planner = c.planning::chainFor;          // B3: a goal with no chain asks the planner
        // brain copy
        if (brain.copyOn()) {
            s.copy = copy.update(ownerReport(), owner, s.now, brain.config().i("copyIdleS"));
            // 0.26.1 R9: the guard (as fence check) on the copied job's spot, the owner's feet: one bounded check
            if (s.copy != null && s.copy.chain() != null && s.ownerKnown()) {
                String gr = io.github.mojolowjo.entropybot.api.BotAPI.check(Storage.dim(), s.ownerPos[0], s.ownerPos[1], s.ownerPos[2], "break");
                if (gr != null && gr.startsWith("would refuse:")) s.copyRefused = gr.contains("outside") ? "outside my areas" : "(the guard " + gr.substring(6) + ")";
            }
        }
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
        feasFacts(p, s, bag);
        return s;
    }

    /**
     * 0.26.2: the feasibility facts, bounded reads only: the bag (read above), the stock totals of the 30 s refresh, the
     * places, the base chests' notes, the caves' count, a lazy fence check (BotAPI.check, only when a go/goto is judged);
     * the table/furnace (CampCommands.near) and trees (GoalPlanning.scan) block reads at most once a minute.
     * Errors: caught and logged once a minute; the facts stay "not known" (checks answer UNKNOWN).
     */
    private void feasFacts(LocalPlayer p, BrainState s, Map<String, Integer> bag) {
        try {
            io.github.mojolowjo.entropybot.brain.Feasibility.Facts f = s.feas;
            f.bag.putAll(bag);
            if (lastTotals != null) for (Map.Entry<String, Integer> e : lastTotals.entrySet()) {
                String k = e.getKey().substring(e.getKey().indexOf(':') + 1);
                int n = e.getValue() - bag.getOrDefault(k, 0);
                if (n > 0) f.stock.merge(k, n, Integer::sum);
            }
            int t = 0;
            for (String k : bag.keySet()) if (k.endsWith("_pickaxe")) t = Math.max(t, io.github.mojolowjo.entropybot.plan.PlanFacts.tier(k.substring(0, k.length() - 8)));
            f.pickTier = t;
            Core core = Core.INSTANCE;
            for (Map.Entry<String, JsonObject> e : core.knowledge.places().entrySet()) f.places.put(e.getKey(), xyz(e.getValue()));
            f.isArea = n -> c.policyArea(n) != null;
            f.baseChests = !c.storage.baseChests(p, false).isEmpty();
            f.mineMarked = s.upkeep.mine != null && s.upkeep.mineReady;
            f.caveKnown = core.caves.size() > 0;
            String dim = Storage.dim();
            f.inFence = q -> {
                String r = io.github.mojolowjo.entropybot.api.BotAPI.check(dim, q[0], q[1], q[2], "go");
                return r == null || !r.startsWith("would refuse");
            };
            if (s.now - nearAt > 60_000) {
                nearAt = s.now;
                net.minecraft.client.multiplayer.ClientLevel lv = Minecraft.getInstance().level;
                io.github.mojolowjo.entropybot.plan.PlanFacts pf = new io.github.mojolowjo.entropybot.plan.PlanFacts();
                GoalPlanning.scan(lv, s.botPos, pf);
                nearTrees = pf.flags.contains("trees");
                nearTable = CampCommands.near(lv, s.botPos, "crafting_table");
                nearFurnace = CampCommands.near(lv, s.botPos, "furnace");
            }
            f.trees = nearTrees;
            f.table = nearTable;
            f.furnace = nearFurnace;
            f.known = true;
        } catch (RuntimeException e) {
            if (s.now - feasErrAt > 60_000) {
                feasErrAt = s.now;
                LOG.warn("[entropybot] brain feasibility facts: {}", e.toString());
            }
        }
    }

    private long nearAt = -60_001, feasErrAt = -60_001;
    private boolean nearTrees, nearTable, nearFurnace;
    private Map<String, Integer> lastTotals;

    /** 0.26.2: a sub-plan for the brain's feasibility check, on the planner's worker (GoalPlanning.searchAsync's pattern). */
    @Override public boolean planSubgoal(String item, int n, int maxSteps, java.util.function.Consumer<String[]> then) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return false;
        return c.planning.subgoal(p, item, n, maxSteps, then);
    }

    private int foodStock = -1;
    /** 0.26.1: the stock view's hash and change count (R1), the day bed search's time and answer (R6), when the owner went offline (R5). */
    private int stockHash;
    private long storageSeq, bedAt = -60_001, ownerOffAt = -1;
    private boolean bedNearDay;

    /** 0.25.1: edible items among the stock totals (an item whose default stack has the FOOD component). */
    static int edible(Map<String, Integer> totals) {
        int n = 0;
        for (Map.Entry<String, Integer> e : totals.entrySet()) {
            try {
                var rl = net.minecraft.resources.ResourceLocation.tryParse(e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey());
                if (rl == null) continue;
                var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
                if (item != null && item.getDefaultInstance().has(DataComponents.FOOD)) n += Math.max(0, e.getValue());
            } catch (RuntimeException ignored) {
                // an odd id: not food
            }
        }
        return n;
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

    @Override public void goalDone(String chain) { c.vocab.goalDone(chain); }

    /** B3: the game stage as last sensed (every 30 s), for actions.json. */
    String stage() { return stage; }

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
