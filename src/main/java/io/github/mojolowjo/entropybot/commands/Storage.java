package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.commands.Seq.Step;
import io.github.mojolowjo.entropybot.engine.Reflexes;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.gui.GuiCore;
import io.github.mojolowjo.entropybot.gui.McMenu;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.storage.StorageRules;
import io.github.mojolowjo.entropybot.storage.StorageRules.Spot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The storage verbs in the mod (B7b part 2, ported from the KubeJS bridge with the same behaviour and wording):
 * open / take / put / close / use / drop / wear, scan, deposit, where, trust, corpse and death, the Refined Storage
 * grid (rs, rs take, rs put), the botany pots and "go poi". The errands are {@link Seq} jobs in the mod's job slot;
 * chest notes and RS readings go into the knowledge files, which the bridge mirrors into memory.json.
 */
public final class Storage {
    private static final Logger LOG = LogUtils.getLogger();
    static final int GUI_SETTLE = 8, POTS_R = 16;

    private final Core core;
    private final Commands commands;
    private final Jobs jobs;
    /** "x y z" of the block the mod last right-clicked (a container that opens is noted under it). */
    String lastOpened;
    private long lastOpenedTick;
    private boolean containerSeen;
    /** Corpses already emptied (entity uuids). */
    final Set<UUID> emptyCorpses = new HashSet<>();
    /** B7c: the crafting, farm and compact steps (Seq hands them over). */
    Crafting crafting;

    Storage(Core core, Commands commands, Jobs jobs) {
        this.core = core;
        this.commands = commands;
        this.jobs = jobs;
    }

    private long now() { return core.tick(); }

    static String dim() { return Guard.dimOf(Minecraft.getInstance().level); }

    private Map<String, JsonObject> places() { return core.knowledge.places(); }

    // ---- clicking and remembering containers ----

    /** "use x y z": right-click the block (within reach); a container that opens is remembered under these coords. */
    String use(LocalPlayer p, String text) {
        String t = text == null ? "" : text.trim();
        if (!t.matches("^-?\\d+\\s+-?\\d+\\s+-?\\d+$")) return "usage: use x y z";
        String r = Jobs.useBlock(p, t);
        if (r.startsWith("ok")) {
            lastOpened = String.join(" ", t.split("\\s+"));
            lastOpenedTick = now();
            containerSeen = false;
        }
        return r;
    }

    /** The open container's note under lastOpened (the trust flag kept, the other half of a double chest dropped). */
    void rememberOpen(LocalPlayer p) {
        Map<String, Integer> contents = Gui.containerContents(p);
        if (contents == null || lastOpened == null) return;
        noteChest(lastOpened, contents);
    }

    private void noteChest(String key, Map<String, Integer> contents) {
        JsonObject items = new JsonObject();
        contents.forEach(items::addProperty);
        JsonObject note = new JsonObject();
        note.addProperty("dim", dim());
        note.add("items", items);
        note.addProperty("seen", System.currentTimeMillis());
        JsonObject old = core.knowledge.chests().get(key);
        if (old != null && old.has("trusted")) note.add("trusted", old.get("trusted"));
        core.knowledge.noteChest(key, note, now());
        forgetOtherHalf(key);
    }

    /** Every 20 ticks: a container opened by "use" (or by hand after it) is noted once its contents change. */
    void tick(LocalPlayer p) {
        if (core.reflexes.reflex() == Reflexes.Reflex.FETCHING) lastOpened = null;      // the food run's chest is its own
        Map<String, Integer> contents = Gui.containerContents(p);
        if (contents == null) {
            if (containerSeen) {
                containerSeen = false;
                lastOpened = null;
            } else if (lastOpened != null && now() - lastOpenedTick > 100) {
                lastOpened = null;            // the click opened nothing: never file a later container under it
            }
            return;
        }
        if (lastOpened == null || Gui.screenName().contains("Crafting")) return;
        containerSeen = true;
        JsonObject prev = core.knowledge.chests().get(lastOpened);
        if (prev != null && prev.has("items")) {
            Map<String, Integer> was = new TreeMap<>();
            for (Map.Entry<String, JsonElement> e : prev.getAsJsonObject("items").entrySet()) was.put(e.getKey(), e.getValue().getAsInt());
            if (was.equals(contents)) return;
        }
        noteChest(lastOpened, contents);
    }

    /** "left"/"right" for one half of a double chest, else null. */
    static String chestHalf(BlockState st) {
        for (Property<?> pr : st.getProperties()) {
            if (!pr.getName().equals("type")) continue;
            String v = String.valueOf(st.getValue(pr)).toLowerCase();
            if (v.equals("left") || v.equals("right")) return v;
        }
        return null;
    }

    /** The two halves of a double chest share one inventory: keep one note (the one just written at key). */
    void forgetOtherHalf(String key) {
        try {
            Minecraft mc = Minecraft.getInstance();
            String[] q = key.split(" ");
            int x = Integer.parseInt(q[0]), y = Integer.parseInt(q[1]), z = Integer.parseInt(q[2]);
            BlockState st = mc.level.getBlockState(new BlockPos(x, y, z));
            String half = chestHalf(st), id = st.getBlock().getDescriptionId();
            if (half == null) return;
            Map<String, JsonObject> notes = core.knowledge.chests();
            for (int[] s : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                String nk = (x + s[0]) + " " + y + " " + (z + s[1]);
                if (!notes.containsKey(nk)) continue;
                BlockState ns = mc.level.getBlockState(new BlockPos(x + s[0], y, z + s[1]));
                if (ns.getBlock().getDescriptionId().equals(id) && chestHalf(ns) != null && !half.equals(chestHalf(ns))) core.knowledge.forgetChest(nk, now());
            }
        } catch (RuntimeException ignored) {}
    }

    /** Is the block at p storage? A spot whose chunk isn't loaded can't be checked, so it counts. */
    static boolean isStorageBlock(int[] p) {
        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = new BlockPos(p[0], p[1], p[2]);
        if (!mc.level.isLoaded(pos)) return true;
        return StorageRules.isStorageId(mc.level.getBlockState(pos).getBlock().getDescriptionId());
    }

    static String blockName(BlockState st) {
        return st.getBlock().getDescriptionId().replaceFirst("^block\\.", "").replaceFirst("^minecraft\\.", "").replaceFirst("\\.", ":");
    }

    record Found(int x, int y, int z, String id, String half) {
        int[] pos() { return new int[]{x, y, z}; }
    }

    /** Chests and barrels around a point; the second half of a double chest is skipped. */
    static List<Found> findContainers(int[] c, int radius) {
        Minecraft mc = Minecraft.getInstance();
        List<Found> found = new ArrayList<>();
        int dyMax = Math.min(radius, 8);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -dyMax; dy <= dyMax; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockState st = mc.level.getBlockState(new BlockPos(c[0] + dx, c[1] + dy, c[2] + dz));
                    String id = st.getBlock().getDescriptionId();
                    if (id.contains("ender_chest") || (!id.contains("chest") && !id.contains("barrel"))) continue;
                    Found f = new Found(c[0] + dx, c[1] + dy, c[2] + dz, id, chestHalf(st));
                    // only the two halves of one double chest are the same container (a row of barrels is not)
                    boolean dup = false;
                    for (Found o : found) {
                        if (f.half() != null && o.half() != null && !o.half().equals(f.half()) && o.id().equals(id) && o.y() == f.y()
                                && Math.abs(o.x() - f.x()) + Math.abs(o.z() - f.z()) == 1) dup = true;
                    }
                    if (!dup) found.add(f);
                }
            }
        }
        return found;
    }

    /** Notes in the scanned area whose block is no storage any more are dropped. */
    int pruneChestNotes(int[] c, int radius) {
        Minecraft mc = Minecraft.getInstance();
        String d = dim();
        int n = 0;
        for (Map.Entry<String, JsonObject> e : core.knowledge.chests().entrySet()) {
            if (!d.equals(e.getValue().has("dim") ? e.getValue().get("dim").getAsString() : null)) continue;
            String[] q = e.getKey().split(" ");
            int x = Integer.parseInt(q[0]), y = Integer.parseInt(q[1]), z = Integer.parseInt(q[2]);
            if (Math.abs(x - c[0]) > radius || Math.abs(y - c[1]) > Math.min(radius, 8) || Math.abs(z - c[2]) > radius) continue;
            try {
                BlockPos pos = new BlockPos(x, y, z);
                if (!mc.level.isLoaded(pos)) continue;
                BlockState st = mc.level.getBlockState(pos);
                if (st.hasBlockEntity() && StorageRules.isStorageId(st.getBlock().getDescriptionId())) continue;
            } catch (RuntimeException ex) {
                continue;
            }
            core.knowledge.forgetChest(e.getKey(), now());
            n++;
        }
        if (n > 0) LOG.info("[entropybot] scan: dropped {} notes of containers that are gone", n);
        return n;
    }

    record ScanPlan(List<Step> steps, int found) {}

    /** The steps that open and note every container around center, nearest to `from` first (at most 30). */
    ScanPlan scanSteps(int[] center, int radius, int[] from) {
        pruneChestNotes(center, radius);
        List<Found> found = findContainers(center, radius);
        found.sort((a, b) -> Long.compare(Jobs.distSq(a.pos(), from), Jobs.distSq(b.pos(), from)));
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < found.size() && i < 30; i++) {
            steps.add(Step.walk(found.get(i).pos(), false));
            steps.add(Step.open(found.get(i).pos(), "no"));
            steps.add(Step.close());
        }
        return new ScanPlan(steps, found.size());
    }

    private String startSeq(String label, List<Step> steps, String close) {
        return jobs.startSeq(new Seq(jobs, this, label, steps, close), close);
    }

    // ---- the verbs ----

    /** "open <x y z | place>": walk there if it's out of reach, open it, remember what's inside; it stays open for take/put. */
    String open(LocalPlayer p, String text) {
        Spot spot = StorageRules.resolveSpot(text, places(), dim());
        if (spot.err() != null) return spot.err();
        List<Step> steps = new ArrayList<>();
        if (p.getEyePosition().distanceTo(new Vec3(spot.x() + 0.5, spot.y() + 0.5, spot.z() + 0.5)) > 4.4) steps.add(Step.walk(spot.pos(), false));
        steps.add(Step.open(spot.pos(), "auto"));
        Step note = new Step("note");
        note.kind = "open";
        steps.add(note);
        return startSeq("opening " + (spot.name() != null ? spot.name() + " (" + spot.fmt() + ")" : "the chest at " + spot.fmt()), steps, "fail");
    }

    /** "take ..." / "put ..." with a container open. */
    static String transfer(LocalPlayer p, String text, boolean toContainer) {
        if (!Gui.open(p)) return "error: nothing is open - PM \"open x y z\" first";
        return GuiCore.transferVerb(new McMenu(p), text, toContainer, Gui.wrongScreen(p));
    }

    /** "drop <item|all> [count|all]": inventory and hotbar only, never armor. */
    static String drop(LocalPlayer p, String text) {
        // the bot's own inventory menu (an open container's slot numbers would differ)
        McMenu m = new McMenu(p);
        return GuiCore.drop(m, text);
    }

    /** "scan [radius]" around the bot, or "scan <place|x y z> [radius]" (walks there first; containers are found on arrival). */
    String scan(LocalPlayer p, String text) {
        String[] a = StorageRules.scanArgs(text);
        String where = a[0];
        int radius = Integer.parseInt(a[1]);
        int[] me = Jobs.here(p);
        Spot spot = null;
        if (!where.isEmpty()) {
            spot = StorageRules.resolveSpot(where, places(), dim());
            if (spot.err() != null) return spot.err().replace("open", "scan");
        }
        if (spot == null || Jobs.distSq(spot.pos(), me) <= 64) {
            ScanPlan r = scanSteps(spot != null ? spot.pos() : me, radius, me);
            if (r.found() == 0) return "error: no chests or barrels within " + radius + " blocks of " + (spot != null ? spot.label() : "me");
            return startSeq("scanning " + Math.min(r.found(), 30) + " containers" + (spot != null ? " at " + spot.label() : " around me"), r.steps(), "always");
        }
        Step here = new Step("scanhere");
        here.center = spot;
        here.radius = radius;
        return startSeq("scanning around " + spot.label(), List.of(Step.walk(spot.pos(), true), here), "always");
    }

    /** The base (when within 64 across) or the bot. */
    int[] workCenter(LocalPlayer p) {
        JsonObject b = places().get("base");
        int[] me = Jobs.here(p);
        if (b != null && Jobs.dimOf(b).equals(dim())) {
            int[] bp = Jobs.pos(b);
            if (Math.abs(bp[0] - me[0]) <= 64 && Math.abs(bp[2] - me[2]) <= 64) return bp;
        }
        return me;
    }

    /** Remembered containers within 32 of the base that are still there (atBase: around the base even from far away). */
    List<StorageRules.Chest> baseChests(LocalPlayer p, boolean atBase) {
        Minecraft mc = Minecraft.getInstance();
        String d = dim();
        List<StorageRules.Chest> out = new ArrayList<>();
        int[] center;
        if (atBase) {
            JsonObject b = places().get("base");
            if (b == null || (b.has("dim") && !Jobs.dimOf(b).equals(d))) return out;
            center = Jobs.pos(b);
        } else {
            center = workCenter(p);
        }
        for (Map.Entry<String, JsonObject> e : core.knowledge.chests().entrySet()) {
            JsonObject c = e.getValue();
            if (!d.equals(c.has("dim") ? c.get("dim").getAsString() : null)) continue;
            if (c.has("trusted") && !c.get("trusted").getAsBoolean()) continue;       // "untrust": never put things in there
            String[] q = e.getKey().split(" ");
            int[] pos = {Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2])};
            if (Jobs.distSq(pos, center) > 32 * 32) continue;
            BlockPos bp = new BlockPos(pos[0], pos[1], pos[2]);
            if (mc.level.isLoaded(bp)) {
                String name = mc.level.getBlockState(bp).getBlock().getDescriptionId();
                if (!name.matches(".*(chest|barrel|shulker).*") || name.contains("ender_chest")) continue;
            }
            Map<String, Integer> items = new LinkedHashMap<>();
            if (c.has("items") && c.get("items").isJsonObject()) for (Map.Entry<String, JsonElement> it : c.getAsJsonObject("items").entrySet()) items.put(it.getKey(), it.getValue().getAsInt());
            out.add(new StorageRules.Chest(e.getKey(), pos, items));
        }
        return out;
    }

    static List<StorageRules.Held> held(LocalPlayer p) {
        List<StorageRules.Held> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty()) out.add(new StorageRules.Held(Gui.itemId(s), s.getCount(), Gui.isFood(s), i));
        }
        return out;
    }

    /** Package B: what deposit never puts away besides the old rules (the supplies, the hotbar layout, the best pickaxe). */
    StorageRules.Keeps keeps() {
        return new StorageRules.Keeps(commands.suppliesMap(), io.github.mojolowjo.entropybot.engine.Hotbar.layout());
    }

    record DepositSteps(List<Step> steps, String label, String err) {}

    /** The walk/open/put/close steps that take what the bot carries (see depositables) to the base chests. */
    DepositSteps depositSteps(LocalPlayer p, String text, boolean atBase, Pattern only) {
        List<StorageRules.Held> held = held(p);
        Map<String, Integer> items = StorageRules.depositables(held, text, false, only, keeps());
        if (items.isEmpty() && text != null && !text.trim().isEmpty() && !text.trim().equalsIgnoreCase("all")
                && !StorageRules.depositables(held, text, false, only).isEmpty()) return new DepositSteps(null, null, StorageRules.allKeptReply(text));
        StorageRules.Plan plan = StorageRules.depositPlan(items, baseChests(p, atBase), Jobs.here(p), text);
        if (plan.err() != null) return new DepositSteps(null, null, plan.err());
        List<Step> steps = new ArrayList<>();
        for (StorageRules.Stop s : plan.stops()) {
            steps.add(Step.walk(s.chest().pos(), false));
            steps.add(Step.open(s.chest().pos(), "no"));
            Step put = new Step("put");
            put.items = s.items();
            put.fallback = s.fallback();          // whatever doesn't fit goes to the bulk chest afterwards
            steps.add(put);
            steps.add(Step.close());
        }
        return new DepositSteps(steps, plan.label(), null);
    }

    /** "deposit [items]" (wave 1, item 5: from far away the base chests too; the walk teleports home first). */
    String deposit(LocalPlayer p, String text) {
        JsonObject b = places().get("base");
        boolean far = b != null && StorageRules.farFromBase(Jobs.pos(b), b.has("dim") ? Jobs.dimOf(b) : null, Jobs.here(p), dim());
        DepositSteps r = depositSteps(p, text, far, null);
        if (r.err() != null) return r.err();
        return startSeq(r.label(), r.steps(), "always");
    }

    /** "trust" (list) / "trust|untrust x y z|<place>". */
    String trust(String verb, String rest) {
        Map<String, JsonObject> chests = core.knowledge.chests();
        if (rest == null || rest.trim().isEmpty()) {
            List<String> list = new ArrayList<>();
            for (Map.Entry<String, JsonObject> e : chests.entrySet()) {
                JsonObject c = e.getValue();
                if (c.has("trusted") && !c.get("trusted").getAsBoolean()) {
                    String l = StorageRules.chestLabel(e.getKey(), places(), c);
                    list.add(e.getKey() + (l != null ? " (" + l + ")" : ""));
                }
            }
            return !list.isEmpty() ? "chests I don't use: " + String.join(" | ", list) : "I use every chest I know (none untrusted) - \"untrust x y z\" to keep me out of one";
        }
        Spot spot = StorageRules.resolveSpot(rest, places(), dim());
        if (spot.err() != null) return spot.err().replace("open", verb);
        String k = spot.fmt();
        JsonObject c = chests.get(k);
        if (c == null) {
            c = new JsonObject();
            c.addProperty("dim", spot.dim());
            c.add("items", new JsonObject());
            c.addProperty("seen", 0);
        }
        c.addProperty("trusted", verb.equals("trust"));
        core.knowledge.noteChest(k, c, now());
        return verb.equals("trust") ? "ok: I may take from and put into the chest at " + k : "ok: I won't take from or put into the chest at " + k + " (craft trips, deposits, the food run)";
    }

    /** "where <item>". */
    String where(LocalPlayer p, String text) {
        return StorageRules.where(text, Gui.inventory(p), core.knowledge.rs(), core.knowledge.chests(), places(), System.currentTimeMillis());
    }

    // ---- corpses (the server runs the Corpse mod) ----

    String corpse() { return startSeq("getting my stuff back", List.of(new Step("loot")), "always"); }

    /** "death": walk back to where it died, then empty the corpse. */
    String death(LocalPlayer p) {
        JsonObject d = commands.lastDeath();
        if (d == null) return "I haven't died yet";
        if (d.has("dim") && !d.get("dim").isJsonNull() && !d.get("dim").getAsString().equals(dim())) return "I died in " + d.get("dim").getAsString();
        long t = d.has("time") ? d.get("time").getAsLong() : System.currentTimeMillis();
        return startSeq("going back to where I died (" + Texts.ago(t, System.currentTimeMillis()) + ")", List.of(Step.walk(Jobs.pos(d), true), new Step("loot")), "always");
    }

    private static Object call(Object o, String name, Object... args) {
        Method m = findMethod(o.getClass(), name, args.length);
        if (m == null) throw new IllegalStateException("no method " + name + " on " + o.getClass().getSimpleName());
        try {
            return m.invoke(o, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(name + ": " + (e.getCause() != null ? e.getCause() : e));
        }
    }

    private static final Map<String, Method> METHODS = new java.util.concurrent.ConcurrentHashMap<>();

    /** A public method by name and argument count, through a public class or interface when the class itself isn't. */
    static Method findMethod(Class<?> c, String name, int argc) {
        String key = c.getName() + "#" + name + "/" + argc;
        Method hit = METHODS.get(key);
        if (hit != null) return hit;
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != argc) continue;
            Method usable = publicVersion(m);
            if (usable != null) {
                METHODS.put(key, usable);
                return usable;
            }
        }
        return null;
    }

    private static Method publicVersion(Method m) {
        if (Modifier.isPublic(m.getDeclaringClass().getModifiers())) return m;
        List<Class<?>> todo = new ArrayList<>();
        todo.add(m.getDeclaringClass());
        while (!todo.isEmpty()) {
            Class<?> k = todo.remove(0);
            for (Class<?> i : k.getInterfaces()) {
                try {
                    Method x = i.getMethod(m.getName(), m.getParameterTypes());
                    if (Modifier.isPublic(i.getModifiers())) return x;
                } catch (NoSuchMethodException ignored) {}
                todo.add(i);
            }
            if (k.getSuperclass() != null) {
                try {
                    Method x = k.getSuperclass().getMethod(m.getName(), m.getParameterTypes());
                    if (Modifier.isPublic(k.getSuperclass().getModifiers())) return x;
                } catch (NoSuchMethodException ignored) {}
                todo.add(k.getSuperclass());
            }
        }
        try {
            m.setAccessible(true);
            return m;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The bot's own corpse within radius (by owner uuid or name; a corpse that won't say counts only where the bot died). */
    Entity findOwnCorpse(LocalPlayer p, double radius) {
        Minecraft mc = Minecraft.getInstance();
        String me = p.getUUID().toString(), myName = p.getGameProfile().getName();
        JsonObject death = commands.lastDeath();
        Entity best = null;
        double bestD = 1e9;
        for (Entity e : mc.level.entitiesForRendering()) {
            ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            if (type == null || !type.toString().equals("corpse:corpse") || !e.isAlive() || emptyCorpses.contains(e.getUUID())) continue;
            double d = e.distanceTo(p);
            if (d > radius) continue;
            String uuid = null, name = null;
            try {
                Object u = call(e, "getCorpseUUID");
                if (u instanceof java.util.Optional<?> o) u = o.orElse(null);
                if (u != null) uuid = u.toString();
            } catch (RuntimeException ignored) {}
            try {
                Object n = call(e, "getCorpseName");
                if (n != null && !n.toString().isEmpty()) name = n.toString();
            } catch (RuntimeException ignored) {}
            boolean mine = uuid != null || name != null ? me.equals(uuid) || myName.equals(name)
                    : death != null && Jobs.distSq(new int[]{(int) e.getX(), (int) e.getY(), (int) e.getZ()}, Jobs.pos(death)) < 64;
            if (mine && d < bestD) {
                best = e;
                bestD = d;
            }
        }
        return best;
    }

    /** Presses the open screen's button with that label. */
    static boolean pressButton(String label) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) return false;
        for (GuiEventListener w : mc.screen.children()) {
            if (w instanceof AbstractWidget aw && w instanceof AbstractButton b && aw.getMessage().getString().equals(label)) {
                b.onPress();
                return true;
            }
        }
        return false;
    }

    // ---- Refined Storage (RS 2: the grid menu's repository view list; taking by onExtract, putting by shift-click and onInsert) ----

    /** The open grid's items {id: n}, or null when the open menu is no grid. */
    static Map<String, Integer> rsGridItems(LocalPlayer p) {
        if (!Gui.open(p)) return null;
        AbstractContainerMenu m = p.containerMenu;
        if (findMethod(m.getClass(), "getRepository", 0) == null) return null;
        Object repo;
        List<?> list;
        try {
            repo = call(m, "getRepository");
            list = (List<?>) call(repo, "getViewList");
        } catch (RuntimeException e) {
            return null;
        }
        Map<String, Integer> out = new TreeMap<>();
        for (Object e : list) {
            try {
                long n = ((Number) call(e, "getAmount", repo)).longValue();
                ItemStack st = (ItemStack) call(e, "getItemStack");           // fluids have no item stack: skipped
                if (n > 0 && st != null && !st.isEmpty()) out.merge(Gui.itemId(st), (int) Math.min(n, Integer.MAX_VALUE), Integer::sum);
            } catch (RuntimeException ignored) {}
        }
        return out;
    }

    /** The grid view entry for an item: {key, amount, max}, or null. */
    static Object[] rsEntry(LocalPlayer p, String id) {
        try {
            Object repo = call(p.containerMenu, "getRepository");
            for (Object e : (List<?>) call(repo, "getViewList")) {
                ItemStack st;
                try { st = (ItemStack) call(e, "getItemStack"); } catch (RuntimeException x) { continue; }
                if (st == null || st.isEmpty() || !Gui.itemId(st).equals(id)) continue;
                return new Object[]{call(e, "getResourceForRecipeMods"), ((Number) call(e, "getAmount", repo)).longValue(), st.getMaxStackSize()};
            }
        } catch (RuntimeException ignored) {}
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object rsEnum(AbstractContainerMenu m, String cls, String value) {
        try {
            Class c = Class.forName("com.refinedmods.refinedstorage.api.network.node.grid." + cls, true, m.getClass().getClassLoader());
            return Enum.valueOf(c, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(cls + ": " + e);
        }
    }

    void rsRemember(String key, Map<String, Integer> items) {
        JsonObject o = new JsonObject(), it = new JsonObject();
        items.forEach(it::addProperty);
        o.addProperty("dim", dim());
        o.add("items", it);
        o.addProperty("seen", System.currentTimeMillis());
        core.knowledge.noteRs(key, o, now());
    }

    /** "rs [x y z|place]" (read the grid), "rs take <item> [n]", "rs put <item|all> [n]". */
    String rs(LocalPlayer p, String text) {
        Minecraft mc = Minecraft.getInstance();
        String t = text == null ? "" : text.trim();
        java.util.regex.Matcher mv = Pattern.compile("^(take|put)\\b\\s*(.*)$", Pattern.CASE_INSENSITIVE).matcher(t);
        if (mv.find()) return rsMove(p, mv.group(1).toLowerCase(), mv.group(2));
        String grid = core.knowledge.rsGrid();
        if (t.isEmpty()) {
            // a grid is open already: read it now
            Map<String, Integer> items = rsGridItems(p);
            if (items != null) {
                String key = grid != null ? grid : "the open grid";
                rsRemember(key, items);
                return "ok: " + StorageRules.rsSummary(key, items);
            }
            if (grid == null) return "error: where is the grid? rs x y z (once; I remember it)";
            t = grid;
        }
        Spot spot = StorageRules.resolveSpot(t, places(), dim());
        if (spot.err() != null) return spot.err().replace("open", "rs");
        BlockPos bp = new BlockPos(spot.x(), spot.y(), spot.z());
        if (!mc.level.getBlockState(bp).getBlock().getDescriptionId().contains("grid") && mc.level.isLoaded(bp)) {
            return "error: the block at " + spot.fmt() + " is " + blockName(mc.level.getBlockState(bp)) + ", not a grid";
        }
        Step read = new Step("rsread");
        read.key = spot.fmt();
        return startSeq("reading the RS network at " + spot.label(), List.of(Step.walk(spot.pos(), false), Step.open(spot.pos(), "yes"), read, Step.close()), "always");
    }

    /** An item id from a name: an exact id, minecraft:<name>, its singular, else an id whose path ends with it (the carried one first). */
    static String resolveItem(String q, Map<String, Integer> counts) {
        q = q.toLowerCase().trim();
        if (q.isEmpty()) return null;
        if (q.contains(":")) return itemExists(q) ? q : null;
        String singular = q.replaceFirst("s$", "");
        if (itemExists("minecraft:" + q)) return "minecraft:" + q;
        if (!singular.equals(q) && itemExists("minecraft:" + singular)) return "minecraft:" + singular;
        String best = null;
        double bestScore = -1;
        for (ResourceLocation rl : BuiltInRegistries.ITEM.keySet()) {
            String id = rl.toString(), path = rl.getPath();
            if (!(path.equals(q) || path.equals(singular) || path.endsWith("_" + q) || path.endsWith("_" + singular))) continue;
            double score = (counts.getOrDefault(id, 0) > 0 ? 2 : 0) + (id.startsWith("minecraft:") ? 0.1 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = id;
            }
        }
        return best;
    }

    static boolean itemExists(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl != null && BuiltInRegistries.ITEM.containsKey(rl);
    }

    private String rsMove(LocalPlayer p, String op, String rest) {
        List<String> w = Texts.words(rest == null ? "" : rest.toLowerCase());
        if (w.isEmpty()) return "error: usage rs " + op + " <item" + (op.equals("put") ? "|all" : "") + "> [count]";
        Integer n = null;
        if (w.size() > 1 && w.get(w.size() - 1).matches("^\\d+$")) n = Integer.parseInt(w.remove(w.size() - 1));
        String grid = core.knowledge.rsGrid();
        if (grid == null) return "error: where is the grid? rs x y z first (I remember it)";
        Spot spot = StorageRules.resolveSpot(grid, places(), dim());
        if (spot.err() != null) return spot.err();
        String id;
        Map<String, Integer> keep = null;
        Map<String, Integer> inv = Gui.inventory(p);
        if (op.equals("put") && w.get(0).equals("all")) {
            keep = StorageRules.depositables(held(p), "", false, null, keeps());
            if (keep.isEmpty()) return "error: nothing to put away (I keep my tools, armor, food and torches)";
            id = "all";
        } else {
            String q = String.join("_", w);
            id = resolveItem(q, inv);
            if (id == null && op.equals("take")) {
                // a modded item the bot never held: look it up in the network's last reading
                JsonObject c = core.knowledge.rs().get(grid);
                if (c != null && c.has("items")) for (String k : c.getAsJsonObject("items").keySet()) if (GuiCore.bareId(k).equals(q) || k.equals(q)) id = k;
            }
            if (id == null) return "error: I don't know an item called " + String.join(" ", w);
            if (op.equals("put") && inv.getOrDefault(id, 0) <= 0) return "error: I carry no " + GuiCore.bareId(id);
        }
        Step mv = new Step("rsmove");
        mv.op = op;
        mv.id = id;
        mv.n = n;
        mv.keep = id.equals("all") ? keep : null;
        Step read = new Step("rsread");
        read.key = spot.fmt();
        read.keepNote = true;
        return startSeq((op.equals("take") ? "taking " : "putting ") + (n != null ? n + " " : "") + (id.equals("all") ? "my loot" : GuiCore.bareId(id))
                + (op.equals("take") ? " from" : " into") + " the RS network", List.of(Step.walk(spot.pos(), false), Step.open(spot.pos(), "yes"), mv, read, Step.close()), "always");
    }

    /** Seq step "rsmove": one click burst every 8 ticks, then the counts the server confirmed decide. */
    String rsMoveStep(Seq s, Step st, LocalPlayer p, long elapsed) {
        Minecraft mc = Minecraft.getInstance();
        AbstractContainerMenu m = p.containerMenu;
        if (rsGridItems(p) == null) return elapsed > 40 ? "the grid did not open" : "wait";
        if (s.stage == null) {
            s.rsBefore = Gui.inventory(p);
            s.rsQuiet = 0;
            s.rsLast = -1;
            s.stage = "go";
            s.stageTick = now() - 8;
            if (st.op.equals("take")) {
                Object[] ent = rsEntry(p, st.id);
                if (ent == null || (long) ent[1] <= 0) return "the RS network has no " + GuiCore.bareId(st.id);
                long amount = (long) ent[1];
                s.rsWant = (int) (st.n != null ? Math.min(st.n, amount) : Math.min(amount, (int) ent[2]));
                s.rsShort = st.n != null && amount < st.n ? (int) amount : null;
            }
        }
        if (now() - s.stageTick < 8) return "wait";
        s.stageTick = now();
        Map<String, Integer> inv = Gui.inventory(p);
        List<Map<String, Integer>> d = GuiCore.diff(s.rsBefore, inv);
        if (st.op.equals("take")) {
            int gained = d.get(0).getOrDefault(st.id, 0), need = s.rsWant - gained;
            if (need <= 0 || s.rsQuiet >= 3) {
                s.note = (gained >= s.rsWant ? "took " + gained + " " + GuiCore.bareId(st.id) : "only took " + gained + " of " + s.rsWant + " " + GuiCore.bareId(st.id) + " (my inventory is full?)")
                        + (s.rsShort != null ? " - the network had only " + s.rsShort : "");
                return gained > 0 ? "next" : "couldn't take any " + GuiCore.bareId(st.id) + " from the network (my inventory is full?)";
            }
            s.rsQuiet = gained == s.rsLast ? s.rsQuiet + 1 : 0;
            s.rsLast = gained;
            Object[] ent = rsEntry(p, st.id);
            if (ent == null) {
                s.rsQuiet = 3;
                return "wait";
            }
            if (need >= (int) ent[2]) call(m, "onExtract", ent[0], rsEnum(m, "GridExtractMode", "ENTIRE_RESOURCE"), false);
            else for (int i = 0; i < need && i < 16; i++) call(m, "onExtract", ent[0], rsEnum(m, "GridExtractMode", "SINGLE_RESOURCE"), false);
            return "wait";
        }
        // put: what should still go in, per item (st.keep = {id: how many to keep} for "all")
        Map<String, Integer> want = new LinkedHashMap<>();
        if (st.keep != null) {
            for (Map.Entry<String, Integer> e : st.keep.entrySet()) {
                int w = s.rsBefore.getOrDefault(e.getKey(), 0) - e.getValue();
                if (w > 0) want.put(e.getKey(), w);
            }
        } else {
            int have = s.rsBefore.getOrDefault(st.id, 0);
            want.put(st.id, st.n != null ? Math.min(st.n, have) : have);
        }
        int moved = 0;
        Map<String, Integer> left = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : want.entrySet()) {
            int lost = d.get(1).getOrDefault(e.getKey(), 0);
            moved += Math.min(lost, e.getValue());
            if (e.getValue() - lost > 0) left.put(e.getKey(), e.getValue() - lost);
        }
        if (left.isEmpty() || s.rsQuiet >= 3) {
            int total = 0;
            for (int v : want.values()) total += v;
            s.note = (moved >= total ? "put " : "only put ") + moved + (moved >= total ? "" : " of " + total) + " " + (st.keep != null ? "items" : GuiCore.bareId(st.id))
                    + " into the RS network" + (moved < total ? " (is it full?)" : st.n != null && st.n > total ? " (all I had)" : "");
            return moved > 0 ? "next" : "couldn't put anything into the network (is it full?)";
        }
        s.rsQuiet = moved == s.rsLast ? s.rsQuiet + 1 : 0;
        s.rsLast = moved;
        // whole stacks that fit in what is left go by a shift-click; one part stack by the cursor, single inserts
        int[] part = null;
        String partId = null;
        for (int i = 0; i < m.slots.size(); i++) {
            Slot sl = m.getSlot(i);
            if (sl.container != p.getInventory() || !sl.hasItem()) continue;
            String k = Gui.itemId(sl.getItem());
            Integer l = left.get(k);
            if (l == null || l <= 0) continue;
            int cnt = sl.getItem().getCount();
            if (cnt <= l) {
                mc.gameMode.handleInventoryMouseClick(m.containerId, i, 0, ClickType.QUICK_MOVE, p);    // the whole stack into the network
                left.put(k, l - cnt);
            } else if (part == null) {
                part = new int[]{i, l};
                partId = k;
                left.put(k, 0);
            }
        }
        if (part != null) {
            mc.gameMode.handleInventoryMouseClick(m.containerId, part[0], 0, ClickType.PICKUP, p);
            Object single = rsEnum(m, "GridInsertMode", "SINGLE_RESOURCE");
            for (int i = 0; i < part[1] && i < 64; i++) call(m, "onInsert", single, false);
            mc.gameMode.handleInventoryMouseClick(m.containerId, part[0], 0, ClickType.PICKUP, p);    // the rest back where it was
        }
        return "wait";
    }

    // ---- botany pots ----

    /** "pots [chests|rs]": walk to the base, empty every botany pot within 16, put the haul into RS (or the base chests). */
    String pots(LocalPlayer p, String text) {
        String t = text == null ? "" : text.trim().toLowerCase();
        if (!t.isEmpty() && !t.equals("chests") && !t.equals("rs")) return "error: usage pots [chests|rs]";
        JsonObject base = places().get("base");
        int[] me = Jobs.here(p);
        int[] center = base != null && Jobs.dimOf(base).equals(dim()) ? Jobs.pos(base) : me;
        List<Step> steps = new ArrayList<>();
        if (Jobs.distSq(center, me) > 12 * 12) steps.add(Step.walk(center, true));
        Step here = new Step("potshere");
        here.center = new Spot(center[0], center[1], center[2], dim(), null, null);
        here.to = t.equals("chests") || core.knowledge.rsGrid() == null ? "chests" : "rs";
        steps.add(here);
        steps.add(new Step("potsdone"));
        Seq s = new Seq(jobs, this, "emptying the botany pots", steps, "always");
        s.potsBefore = Gui.inventory(p);
        return jobs.startSeq(s, "always");
    }

    String potsStep(Seq s, Step st, LocalPlayer p) {
        Minecraft mc = Minecraft.getInstance();
        if (st.type.equals("potshere")) {
            int[] c = st.center.pos();
            List<int[]> found = new ArrayList<>();
            for (int dx = -POTS_R; dx <= POTS_R; dx++) for (int dy = -6; dy <= 6; dy++) for (int dz = -POTS_R; dz <= POTS_R; dz++) {
                if (mc.level.getBlockState(new BlockPos(c[0] + dx, c[1] + dy, c[2] + dz)).getBlock().getDescriptionId().contains("botany_pot")) {
                    found.add(new int[]{c[0] + dx, c[1] + dy, c[2] + dz});
                }
            }
            if (found.isEmpty()) return "no botany pots within " + POTS_R + " blocks of " + st.center.fmt();
            int[] me = Jobs.here(p);
            found.sort((a, b) -> Long.compare(Jobs.distSq(a, me), Jobs.distSq(b, me)));
            List<Step> add = new ArrayList<>();
            for (int i = 0; i < found.size() && i < 24; i++) {
                Step ops = new Step("ops");
                ops.pos = found.get(i);
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("op", "collect");
                ops.ops = List.of(op);
                add.add(Step.walk(found.get(i), false));
                add.add(Step.open(found.get(i), "yes"));
                add.add(ops);
                add.add(Step.close());
            }
            s.potsTo = st.to;
            s.potsCount = Math.min(found.size(), 24);
            s.splice(s.idx + 1, add);
            return "next";
        }
        // potsdone: what came out of the pots goes away; the report names it
        Map<String, Integer> gained = GuiCore.diff(s.potsBefore, Gui.inventory(p)).get(0), keep = new LinkedHashMap<>();
        for (String k : gained.keySet()) keep.put(k, s.potsBefore.getOrDefault(k, 0));
        if (gained.isEmpty()) {
            s.note = "the " + s.potsCount + " botany pots had nothing to take";
            return "next";
        }
        String got = "took " + GuiCore.top(gained, 4) + " from " + s.potsCount + " botany pots";
        if ("rs".equals(s.potsTo)) {
            Spot spot = StorageRules.resolveSpot(core.knowledge.rsGrid(), places(), dim());
            if (spot.err() == null) {
                Step mv = new Step("rsmove");
                mv.op = "put";
                mv.keep = keep;
                Step note = new Step("potsnote");
                note.got = got;
                Step read = new Step("rsread");
                read.key = spot.fmt();
                read.keepNote = true;
                s.splice(s.idx + 1, List.of(Step.walk(spot.pos(), false), Step.open(spot.pos(), "yes"), mv, note, read, Step.close()));
                return "next";
            }
        }
        DepositSteps dep = depositSteps(p, String.join(" ", gained.keySet()), true, null);
        if (dep.err() != null) {
            s.note = got + "; " + dep.err().replaceFirst("^error: ", "");
            return "next";
        }
        List<Step> add = new ArrayList<>(dep.steps());
        Step note = new Step("potsnote");
        note.got = got;
        add.add(note);
        s.splice(s.idx + 1, add);
        return "next";
    }

    // ---- points of interest ----

    /** "go poi <id>": near it (a spawner or a portal is no place to stand on). */
    String goPoi(LocalPlayer p, int id) {
        JsonObject poi = null;
        try {
            JsonArray list = JsonParser.parseString(core.pois.toJson().toString()).getAsJsonObject().getAsJsonArray("pois");
            for (JsonElement e : list) if (e.getAsJsonObject().get("id").getAsInt() == id) poi = e.getAsJsonObject();
        } catch (RuntimeException e) {
            return "error: points of interest need the bot mod (0.4 or newer)";
        }
        if (poi == null) return "error: no poi " + id + " (\"poi\" lists them)";
        String pd = poi.get("dim").getAsString();
        if (!pd.equals(dim())) return "error: poi " + id + " is in " + pd;
        int[] pos = Jobs.pos(poi);
        return jobs.startSeq(new Seq(jobs, this, "going to poi " + id + " (" + poi.get("kind").getAsString() + " at " + Jobs.fmt(pos) + ")", List.of(Step.walk(pos, true)), null), null);
    }
}
