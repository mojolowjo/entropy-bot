package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.StationFact;
import io.github.mojolowjo.entropybot.aa.brain.DepositRule;
import io.github.mojolowjo.entropybot.aa.brain.StripOrder;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.JobHands;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.gui.Gui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.TorchBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The deposit job (Step.Deposit: the strip order's leg, or a plain deposit: aa want deposit, AA's bag option), {@link DepositRule} in the game. The chest: the nearest known
 * container ({@link NearbyLive#containers}, nearest by Baritone's path cost, {@link LivePathPrice}); none -> a chest from the
 * bag, else crafted (the craft job); a new one is placed near the tunnel start (walk there by the travel job, then the
 * fork's placeNear) and noted in the ledger as a station. Walk to it and open it by the fork's InteractProcess
 * ({@link Storage#use}: no clock on the walk), put in everything outside the kept set (QUICK_MOVE), note the chest's
 * contents (Storage.rememberOpen, as the chest notes do), close it. Failures each end the job with their reason: chest
 * full ("chest at x y z full: N items left"), gone, no path, no chest. Loader notes: vanilla menus (QUICK_MOVE), item
 * tags, the food component, TorchBlock; the fork's InteractProcess and placeNear through JobHands. Client thread only.
 */
final class DepositLeg {
    private static final Logger LOG = LoggerFactory.getLogger("entropybot");

    enum Phase { CHOOSE, CRAFT, GO_START, PLACE, USE, PUT, COUNT }

    private final Commands c;
    private final Step.Deposit step;
    /** The two jobs the leg starts (StepHands: the typed methods, never a verb line). */
    interface Hands {
        String craft(String item, int n);
        String walk(int x, int y, int z);
    }

    private final Hands hands;
    private final Supplier<Car.Status> request;        // StepHands.requestStatus
    private Phase phase = Phase.CHOOSE;
    private int[] chest;
    private boolean placed;
    private Map<String, Integer> before;
    private int putN;

    DepositLeg(Commands c, Step.Deposit step, Hands hands, Supplier<Car.Status> request) {
        this.c = c;
        this.step = step;
        this.hands = hands;
        this.request = request;
    }

    /** The bag's stacks as DepositRule's item facts (the item data: tool tags / damageable, food, station, torch block). */
    static List<DepositRule.Item> bag(LocalPlayer p) {
        List<DepositRule.Item> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty()) out.add(item(st));
        }
        return out;
    }

    static DepositRule.Item item(ItemStack st) {
        String id = Gui.itemId(st);
        boolean tool = st.isDamageableItem() || st.is(ItemTags.PICKAXES) || st.is(ItemTags.AXES) || st.is(ItemTags.SHOVELS)
                || st.is(ItemTags.HOES) || st.is(ItemTags.SWORDS);
        boolean food = st.has(DataComponents.FOOD);
        boolean station = io.github.mojolowjo.entropybot.clear.OwnStations.station(id) || io.github.mojolowjo.entropybot.clear.OwnStations.container(id);
        boolean torch = st.getItem() instanceof BlockItem bi && bi.getBlock() instanceof TorchBlock;
        return new DepositRule.Item(id, st.getCount(), tool, food, station, torch);
    }

    private static boolean chestItem(ItemStack st) {
        return st.getItem() instanceof BlockItem bi && bi.getBlock().defaultBlockState().is(net.neoforged.neoforge.common.Tags.Blocks.CHESTS)
                && io.github.mojolowjo.entropybot.clear.OwnStations.container(Gui.itemId(st));
    }

    private static String chestInBag(LocalPlayer p) {
        for (int i = 0; i < 36; i++) if (chestItem(p.getInventory().getItem(i))) return Gui.itemId(p.getInventory().getItem(i));
        return null;
    }

    private static int planks(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.is(ItemTags.PLANKS)) n += st.getCount();
        }
        return n;
    }

    /** null = started (or an instant end in {@code instant[0]}), else "error: ...". */
    String start(LocalPlayer p, String[] instant) {
        if (step.order() != null && StripStep.met(step.order())) { instant[0] = "ok: the strip order is met, nothing to deposit"; return null; }
        if (DepositRule.toPut(bag(p)).isEmpty()) { instant[0] = "ok: deposit: nothing outside the kept set in the bag"; return null; }
        return null;
    }

    Car.Status tick(LocalPlayer p) {
        return switch (phase) {
            case CHOOSE -> choose(p);
            case CRAFT -> {
                Car.Status st = request.get();
                if (st.state() == Car.State.RUNNING) yield Car.Status.running("deposit: crafting a chest - " + st.why());
                if (st.state() != Car.State.DONE) yield Car.Status.failed("deposit: no chest: the craft failed: " + st.why());
                yield goStart(p);
            }
            case GO_START -> {
                Car.Status st = request.get();
                if (st.state() == Car.State.RUNNING) yield Car.Status.running("deposit: to the tunnel start - " + st.why());
                if (st.state() != Car.State.DONE) yield Car.Status.failed("deposit: no path to the tunnel start: " + st.why());
                phase = Phase.PLACE;
                yield Car.Status.running("deposit: placing a chest");
            }
            case PLACE -> place(p);
            case USE -> use(p);
            case PUT -> put(p);
            case COUNT -> count(p);
        };
    }

    private Car.Status choose(LocalPlayer p) {
        StationFact f = c.nearby.containers();
        List<int[]> known = switch (f) {
            case StationFact.NotLoaded n -> null;
            case StationFact.None n -> List.of();
            case StationFact.Known k -> {
                List<int[]> l = new ArrayList<>();
                for (var s : k.spots()) l.add(new int[]{s.x(), s.y(), s.z()});
                yield l;
            }
        };
        String bagChest = chestInBag(p);
        // main-stations (0.39.z35): the lookup's price bonus of each chest (the main's station.mainBonusS, a use-stations area's)
        java.util.Map<String, Double> bonus = new java.util.HashMap<>();
        if (f instanceof StationFact.Known k) for (var s : k.spots()) bonus.put(DepositRule.at(new int[]{s.x(), s.y(), s.z()}), s.bonusS());
        DepositRule.Choice ch = DepositRule.choose(known, cell -> {
            // 0.39.z41: a complete path is the price; one still computed is NaN (the deposit waits for it); else no path
            var a = LivePathPrice.INSTANCE.price(cell[0], cell[1], cell[2], Double.NaN);
            return a.walk() != null ? a.walk().seconds() : a.kind() == io.github.mojolowjo.entropybot.aa.PathPrice.Kind.COMPUTING ? Double.NaN : null;
        }, cell -> bonus.getOrDefault(DepositRule.at(cell), 0.0), bagChest != null, planks(p) >= 8);          // the chest recipe's input (8 planks), as the game's recipe has it
        LOG.info("[entropybot] deposit: {}", ch.why());
        return switch (ch.how()) {
            case WAIT -> Car.Status.running("deposit: " + ch.why());
            case FAIL -> Car.Status.failed("deposit: " + ch.why());
            case KNOWN -> { chest = ch.at(); phase = Phase.USE; yield Car.Status.running("deposit: " + ch.why()); }
            case BAG -> goStart(p);
            case CRAFT -> {
                String r = hands.craft("minecraft:chest", 1);
                if (r != null) yield Car.Status.failed("deposit: no chest: " + r);
                phase = Phase.CRAFT;
                yield Car.Status.running("deposit: crafting a chest");
            }
        };
    }

    /** The tunnel start (the strip's saved progress) for the new chest; no saved start: here. */
    private Car.Status goStart(LocalPlayer p) {
        StripOrder.Saved s = step.order() == null ? null : StripStep.load(step.order());
        if (s == null || !s.started()) { phase = Phase.PLACE; return Car.Status.running("deposit: no tunnel start saved, placing the chest here"); }
        String r = hands.walk(s.x(), s.y(), s.z());
        if (r != null) return Car.Status.failed("deposit: to the tunnel start: " + r);
        phase = Phase.GO_START;
        return Car.Status.running("deposit: to the tunnel start " + s.x() + " " + s.y() + " " + s.z());
    }

    private Car.Status place(LocalPlayer p) {
        String item = chestInBag(p);
        if (item == null) return Car.Status.failed("deposit: no chest in the bag to place");
        var r = JobHands.get().placeNear(item, Tuning.i("steps.placeNearR"), (int) (20 * Tuning.need("hands.buildS")), "deposit");
        if (r.failed()) return Car.Status.failed("deposit: placing the chest: " + r.why());
        if (!r.done()) return Car.Status.running("deposit: placing the chest - " + r.why());
        String line = io.github.mojolowjo.entropybot.clear.OwnStations.afterPlaceNear(item, r.cell(), RestoreLive.INSTANCE::noteStation);
        if (line != null) LOG.info("[entropybot] {}", line);
        c.nearby.forget();
        if (r.cell() == null) return Car.Status.failed("deposit: the chest was placed but the fork gave no cell");
        chest = r.cell().clone();
        placed = true;
        phase = Phase.USE;
        return Car.Status.running("deposit: placed a chest at " + DepositRule.at(chest));
    }

    private Car.Status use(LocalPlayer p) {
        JobHands.Result r = c.storage.use(chest);
        if (r.running()) return Car.Status.running("deposit: to the chest at " + DepositRule.at(chest) + " - " + r.why());
        if (!isContainer(chest)) {
            String why = "chest at " + DepositRule.at(chest) + " gone";
            io.github.mojolowjo.entropybot.clear.OwnStations.forget(chest[0], chest[1], chest[2], why);
            c.nearby.forget();
            return Car.Status.failed("deposit: " + why);
        }
        if (r.failed()) return Car.Status.failed("deposit: chest at " + DepositRule.at(chest) + ": " + r.why());
        if (!Gui.open(p)) return Car.Status.running("deposit: waiting for the chest at " + DepositRule.at(chest) + " to open");
        phase = Phase.PUT;
        return Car.Status.running("deposit: chest at " + DepositRule.at(chest) + " open");
    }

    private Boolean isContainerOrNull(int[] at) {
        var lv = Minecraft.getInstance().level;
        BlockPos q = new BlockPos(at[0], at[1], at[2]);
        if (lv == null || !lv.isLoaded(q)) return null;
        return io.github.mojolowjo.entropybot.clear.OwnStations.container(BuiltInRegistries.BLOCK.getKey(lv.getBlockState(q).getBlock()).toString());
    }

    private boolean isContainer(int[] at) {
        return !Boolean.FALSE.equals(isContainerOrNull(at));
    }

    /** Every bag stack outside the kept set goes in by QUICK_MOVE (the game moves it into the chest's free room). */
    private Car.Status put(LocalPlayer p) {
        if (!Gui.open(p)) return Car.Status.failed("deposit: the chest at " + DepositRule.at(chest) + " closed before the items went in");
        before = DepositRule.toPut(bag(p));
        var menu = p.containerMenu;
        for (Slot s : menu.slots) {
            if (s.container != p.getInventory() || !s.hasItem()) continue;
            if (DepositRule.kept(item(s.getItem()))) continue;
            Minecraft.getInstance().gameMode.handleInventoryMouseClick(menu.containerId, s.index, 0, ClickType.QUICK_MOVE, p);
        }
        phase = Phase.COUNT;
        return Car.Status.running("deposit: putting " + total(before) + " items in");
    }

    /** One tick later: what is left, the chest noted, closed; the order's deposits saved. */
    private Car.Status count(LocalPlayer p) {
        Map<String, Integer> left = DepositRule.toPut(bag(p));
        int leftN = total(left);
        putN = Math.max(0, total(before) - leftN);
        c.storage.rememberOpen(p);
        Gui.close(p);
        if (step.order() != null) try {
            StripStep.saveDeposits(step.order(), StripStep.deposits(step.order()).add(putN, chest));
        } catch (RuntimeException e) {
            return Car.Status.failed("deposit: " + putN + " items in, but the deposits were not saved: " + e.getMessage());
        }
        if (leftN > 0) return Car.Status.failed("deposit: " + DepositRule.full(chest, leftN));
        return Car.Status.done("ok: deposited " + putN + " items at " + DepositRule.at(chest) + (placed ? " (a new chest)" : ""));
    }

    private static int total(Map<String, Integer> m) {
        int n = 0;
        for (int v : m.values()) n += v;
        return n;
    }
}
