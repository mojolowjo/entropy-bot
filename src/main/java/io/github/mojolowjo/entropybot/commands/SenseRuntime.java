package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.camp.DayNight;
import io.github.mojolowjo.entropybot.engine.Reflexes;
import io.github.mojolowjo.entropybot.sense.BrainState;
import io.github.mojolowjo.entropybot.vocab.GameStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import org.slf4j.Logger;

import java.util.Map;

/**
 * AA_CLEANUP 3: the sense part of the old BrainRuntime, kept for AA: fills {@link BrainState} from what the mod already
 * has (the player, the reflexes, the stock view, the places, the companion's owner fix). AA calls {@link #sense()} every
 * 10 ticks. Lives in the commands package for the package-private parts of Commands it reads.
 *
 * <p>Loader notes: only vanilla client getters (LocalPlayer health/food/inventory, ItemStack DataComponents.FOOD and
 * damage, Level.getBrightness(LightLayer.BLOCK), Level.getDayTime, ClientPacketListener.getPlayerInfo, Level.isThundering,
 * Level.canSeeSky(BlockPos) at the head cell). No mixin, no Baritone. Errors: each part catches and logs; the facts stay
 * at their harmless defaults.
 */
public final class SenseRuntime {
    private static final Logger LOG = LogUtils.getLogger();

    private final Commands c;
    private long stockAt = -1;
    private String stage = "nothing";
    private int foodStock = -1;
    /** 0.26.1: the stock view's hash and change count (R1), the day bed search's time and answer (R6), when the owner went offline (R5). */
    private int stockHash;
    private long storageSeq, bedAt = -60_001, ownerOffAt = -1;
    private boolean bedNearDay;
    private long nearAt = -60_001, feasErrAt = -60_001;
    private boolean nearTrees, nearTable, nearFurnace;
    private Map<String, Integer> lastTotals;

    SenseRuntime(Commands c) { this.c = c; }

    /** The game stage as last sensed (every 30 s), for actions.json. */
    String stage() { return stage; }

    public BrainState sense() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        BrainState s = new BrainState();
        s.now = System.currentTimeMillis();
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
        float bestSpeed = 0;
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
                try { bestSpeed = Math.max(bestSpeed, st.getDestroySpeed(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())); } catch (RuntimeException e) { bestSpeed = Math.max(bestSpeed, 4f); }
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
        s.pickSpeed = bestSpeed;
        s.armorWorn = io.github.mojolowjo.entropybot.camp.ArmorCareRules.lowest(CampCommands.armor(p));
        // the stage (the stock view, every 30 s)
        if (stockAt < 0 || s.now - stockAt > 30_000) {
            stockAt = s.now;
            try {
                Map<String, Integer> totals = c.storage.stock(p).totals();
                lastTotals = totals;          // 0.26.2: the feasibility facts' stock
                stage = GameStage.of(totals);
                foodStock = edible(totals);          // 0.25.1: bag + storage (the stock view)
                // 0.26.2 fix: storage only (the totals count the bag too)
                int h = io.github.mojolowjo.entropybot.sense.FailMemory.storageHash(totals, io.github.mojolowjo.entropybot.gui.Gui.inventory(p));
                if (h != stockHash) { stockHash = h; storageSeq++; }
            } catch (RuntimeException e) { LOG.warn("[entropybot] sense stage: {}", e.toString()); }
        }
        s.stage = stage;
        s.storageSeq = storageSeq;
        s.pickMaterials = io.github.mojolowjo.entropybot.sense.Gear.pickMaterials(io.github.mojolowjo.entropybot.sense.Gear.stageTier(stage), bag);
        s.foodStock = foodStock < 0 ? food : Math.max(foodStock, food);
        // danger: the reflexes fight, flee or retreat, or one holds the bot
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
        // 0.24.3: armour, weapon, deaths today, time, a bed near, the camp
        try {
            java.util.List<String> worn = new java.util.ArrayList<>();
            for (var pc : CampCommands.armor(p)) if (pc.id() != null) worn.add(pc.id());
            s.armourTier = io.github.mojolowjo.entropybot.sense.Gear.armourTier(worn);
            int wt = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (st.isEmpty()) continue;
                String id = Commands.itemId(st);
                if (id.endsWith("_sword") || id.endsWith("_axe")) wt = Math.max(wt, io.github.mojolowjo.entropybot.sense.Gear.tierOf(id));
            }
            s.weaponTier = wt;
            s.shelterBlocks = io.github.mojolowjo.entropybot.camp.ShelterPlan.blocks(io.github.mojolowjo.entropybot.gui.Gui.inventory(p));
            s.deathsDay = io.github.mojolowjo.entropybot.summary.DaySummary.INSTANCE.deaths();
            s.dayTime = dt;
            // 0.26.1 R6: by day too, capped: one bed search (r 24) a minute by day, every read at night
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
            LOG.warn("[entropybot] sense night safety: {}", e.toString());
        }
        // the owner
        String owner = c.owner();
        s.ownerOnline = mc.getConnection() != null && mc.getConnection().getPlayerInfo(owner) != null;
        // 0.26.1 R9: a removed or placeholder entity is no position (ownerKnown() drops the far placeholder too)
        for (Player o : mc.level.players())
            if (o.getGameProfile().getName().equalsIgnoreCase(owner) && !o.isRemoved() && o.distanceToSqr(p) < 1e10) s.ownerPos = new int[]{o.getBlockX(), o.getBlockY(), o.getBlockZ()};
        if (s.ownerPos == null) s.ownerPos = c.ownerFixPos(owner);
        s.released = c.vocab.released();
        // 0.26.1 R5: how long the owner has been offline (0 online; the clock starts when the read first sees it)
        if (s.ownerOnline) ownerOffAt = -1;
        else if (ownerOffAt < 0) ownerOffAt = s.now;
        s.ownerOfflineMs = s.ownerOnline ? 0 : s.now - ownerOffAt;
        s.areasHere = c.inAreas(Storage.dim(), s.botPos[0], s.botPos[2]);      // 0.26.1 R1
        JsonObject base = core.knowledge.places().get("base");
        if (base != null) s.basePos = xyz(base);
        JsonObject m = c.minePlace();
        boolean mineReady = false;
        if (m != null) {
            int[] mine = xyz(m);
            mineReady = mine != null && m.has("dir") && c.inAreas(m.has("dim") ? m.get("dim").getAsString() : "minecraft:overworld", mine[0], mine[2]);
        }
        feasFacts(p, s, bag, mineReady);
        return s;
    }

    /**
     * 0.26.2: the feasibility facts, bounded reads only: the bag (read above), the stock totals of the 30 s refresh, the
     * places, the base chests' notes, the caves' count, a lazy fence check (BotAPI.check, only when a go/goto is judged);
     * the table/furnace (CampCommands.near) and trees (GoalPlanning.scan) block reads at most once a minute.
     * Errors: caught and logged once a minute; the facts stay "not known" (checks answer UNKNOWN).
     */
    private void feasFacts(LocalPlayer p, BrainState s, Map<String, Integer> bag, boolean mineReady) {
        try {
            io.github.mojolowjo.entropybot.sense.Feasibility.Facts f = s.feas;
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
            f.mineMarked = mineReady;
            f.caveKnown = core.caves.size() > 0;
            f.oreKnown = Mining.indexReady() ? fam -> Mining.get().oreKnownNear(p, fam) : null;      // 0.28.0: the chunk pass's index
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
                LOG.warn("[entropybot] sense feasibility facts: {}", e.toString());
            }
        }
    }

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
}
