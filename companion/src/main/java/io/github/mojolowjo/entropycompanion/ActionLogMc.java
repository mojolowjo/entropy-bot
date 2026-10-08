package io.github.mojolowjo.entropycompanion;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Companion 0.4.0: the Minecraft side of the action log. Most events come from comparing a few values each client tick
 * (held item, armour, food, health, sleeping, dimension, position: no allocation unless something changed); the rest
 * from NeoForge events (join/leave, respawn, screens, eating) and the companion's mixins (break/place/use, attack,
 * inventory clicks, item pickup, Q-drop). Lines are written and posted by one daemon thread every 2 s.
 *
 * <p>Loader notes (docs/PLANNING.md): join/leave/respawn: NeoForge {@code ClientPlayerNetworkEvent.LoggingIn/LoggingOut/Clone}
 * (Fabric: {@code ClientPlayConnectionEvents.JOIN/DISCONNECT}, respawn via a mixin); screens: {@code ScreenEvent.Opening/Closing}
 * (Fabric: {@code ScreenEvents}); eating: {@code LivingEntityUseItemEvent.Finish} (Fabric: a mixin on completeUsingItem);
 * everything else: vanilla classes and loader-neutral mixins. Errors: each entry catches its own, logs the first one per
 * place, counts the rest ({@code /bot log status}); nothing throws into the game.
 */
public final class ActionLogMc {
    static ActionLogMc INSTANCE;

    /** For the mixins (another package). */
    public static ActionLogMc instance() { return INSTANCE; }
    private static final Logger LOG = LogUtils.getLogger();
    static final long POS_EVERY_MS = 5000, KILL_WINDOW_MS = 5000, TP_BLOCKS = 16;

    final ActionLog log = new ActionLog();
    final LogStore store;
    final LogUploader uploader;
    private final Supplier<CompanionConfig> config;
    private final HttpSender http;
    private final Companion companion;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "entropy-companion-log");
        t.setDaemon(true);
        return t;
    });
    private final java.util.Set<String> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();
    volatile long errors;

    // tick state (client thread)
    private boolean inWorld;
    private Item held;
    private final Item[] armour = new Item[4];
    private int food = -1;
    private float health = -1;
    private boolean dead, sleeping;
    private String dim;
    private double px, py, pz, sx, sy, sz;
    private long lastPosAt;
    // attack -> kill
    private Entity target;
    private String targetWeapon = "";
    private long targetAt;
    // the last block used (for a container's position)
    private String usedBlock = "";
    private int ux, uy, uz;
    private long usedAt;
    // the open container
    private Map<String, Integer> openTotals;
    private String openType = "";
    private boolean openCrafting;

    ActionLogMc(Supplier<CompanionConfig> config, HttpSender http, Companion companion, Path dir) {
        this.config = config;
        this.http = http;
        this.companion = companion;
        this.store = new LogStore(dir, ZoneId.systemDefault(), LogStore.CAP_BYTES);
        this.uploader = new LogUploader(store, companion::say);
        ActionLog.INSTANCE = log;
        worker.scheduleWithFixedDelay(this::flush, 2, 2, TimeUnit.SECONDS);
    }

    private boolean on() {
        CompanionConfig c = config.get();
        return c.enabled && c.actionLog;
    }

    /** The worker: buffered lines to the file, then a post when due. */
    private void flush() {
        try {
            long now = System.currentTimeMillis();
            store.append(log.drain(), now);
            CompanionConfig c = config.get();
            uploader.step(now, c.active() && c.actionLog, body -> {
                java.net.URI uri = c.apiUri("/api/ownerlog");
                if (uri == null) throw new java.io.IOException("no dashboard url");
                return http.postBytes(uri, c.key, body, true, 5000);
            });
        } catch (RuntimeException e) {
            error("flush", e);
        }
    }

    void error(String where, Throwable t) {
        errors++;
        if (warned.add(where)) LOG.warn("[entropycompanion] action log {}: {} (further errors here only counted)", where, t.toString());
    }

    private static String id(Item i) { return BuiltInRegistries.ITEM.getKey(i).toString(); }

    private static String id(ItemStack s) { return s.isEmpty() ? "" : id(s.getItem()); }

    private ActionLog.LogEvent ev(String type) { return log.ev(type, System.currentTimeMillis()); }

    // ---- the tick ----

    void tick(Minecraft mc) {
        try {
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null || !on()) {
                inWorld = false;
                return;
            }
            String d = mc.level.dimension().location().toString();
            log.context(mc.level.getDayTime(), d, p.getX(), p.getY(), p.getZ());
            if (!inWorld) {
                inWorld = true;
                resetState(p, d);
                return;
            }
            if (!d.equals(dim)) {
                log.add(ev("dimension").str("from", dim));
                dim = d;
                px = p.getX(); py = p.getY(); pz = p.getZ();
                sx = px; sy = py; sz = pz;
            }
            double dx = p.getX() - px, dy = p.getY() - py, dz = p.getZ() - pz;
            if (dx * dx + dy * dy + dz * dz > TP_BLOCKS * TP_BLOCKS)
                log.add(ev("tp").num("fx", (long) Math.floor(px)).num("fy", (long) Math.floor(py)).num("fz", (long) Math.floor(pz)));
            px = p.getX(); py = p.getY(); pz = p.getZ();
            long now = System.currentTimeMillis();
            if (now - lastPosAt >= POS_EVERY_MS) {
                double mx = px - sx, my = py - sy, mz = pz - sz;
                if (mx * mx + my * my + mz * mz >= 1) {
                    int near = 0;
                    for (Player o : mc.level.players()) if (o != p && o.distanceToSqr(p) <= 256) near++;
                    log.add(ev("pos").num("players", near).bool("sprint", p.isSprinting()).num("free", freeSlots(p.getInventory()))
                            .dec("hp", p.getHealth()).num("food", p.getFoodData().getFoodLevel()));
                    sx = px; sy = py; sz = pz;
                }
                lastPosAt = now;
            }
            Item h = p.getMainHandItem().getItem();
            if (h != held) {
                held = h;
                log.add(ev("held").str("item", p.getMainHandItem().isEmpty() ? "" : id(h)));
            }
            for (EquipmentSlot s : EquipmentSlot.values()) {
                if (s.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) continue;
                int i = s.getIndex();
                Item a = p.getItemBySlot(s).getItem();
                if (a != armour[i]) {
                    armour[i] = a;
                    log.add(ev("equip").str("slot", s.getName()).str("item", p.getItemBySlot(s).isEmpty() ? "" : id(a)));
                }
            }
            int f = p.getFoodData().getFoodLevel();
            int m = ActionLog.hungerCrossed(food, f);
            if (m >= 0) log.add(ev("hunger").num("food", f).num("mark", m).str("dir", f < food ? "down" : "up"));
            food = f;
            float hp = p.getHealth();
            if (hp < health - 0.01f) {
                DamageSource src = p.getLastDamageSource();
                log.add(ev("damage").str("source", src == null ? "unknown" : src.getMsgId()).dec("amount", health - hp).dec("health", hp));
            }
            health = hp;
            boolean dd = p.isDeadOrDying();
            if (dd && !dead) {
                DamageSource src = p.getLastDamageSource();
                log.add(ev("death").str("source", src == null ? "unknown" : src.getMsgId()));
            }
            dead = dd;
            boolean sl = p.isSleeping();
            if (sl != sleeping) log.add(ev("sleep").str("what", sl ? "start" : "wake"));
            sleeping = sl;
            if (target != null) {
                if (now - targetAt > KILL_WINDOW_MS) target = null;
                else if (target instanceof LivingEntity le && (le.isDeadOrDying()
                        || target.getRemovalReason() == Entity.RemovalReason.KILLED)) {
                    log.add(ev("kill").str("mob", BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString()).str("weapon", targetWeapon));
                    target = null;
                }
            }
        } catch (RuntimeException e) {
            error("tick", e);
        }
    }

    private void resetState(LocalPlayer p, String d) {
        dim = d;
        px = sx = p.getX(); py = sy = p.getY(); pz = sz = p.getZ();
        held = p.getMainHandItem().getItem();
        for (EquipmentSlot s : EquipmentSlot.values())
            if (s.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) armour[s.getIndex()] = p.getItemBySlot(s).getItem();
        food = p.getFoodData().getFoodLevel();
        health = p.getHealth();
        dead = p.isDeadOrDying();
        sleeping = p.isSleeping();
        lastPosAt = System.currentTimeMillis();
    }

    // ---- NeoForge events ----

    void onLogin(ClientPlayerNetworkEvent.LoggingIn e) {
        try {
            if (!on()) return;
            Minecraft mc = Minecraft.getInstance();
            log.newSession();
            inWorld = false;
            boolean sp = mc.hasSingleplayerServer();
            String name;
            if (sp) name = mc.getSingleplayerServer().getWorldData().getLevelName();
            else {
                ServerData sd = mc.getCurrentServer();
                name = sd == null ? "" : sd.name;            // the name in the server list, never the address field
            }
            LocalPlayer p = e.getPlayer();
            if (p != null && mc.level != null) log.context(mc.level.getDayTime(), mc.level.dimension().location().toString(), p.getX(), p.getY(), p.getZ());
            log.add(ev("join").str("world", ActionLog.hash(name)).str("mode", sp ? "singleplayer" : "multiplayer").str("companion", Companion.VERSION));
        } catch (RuntimeException ex) {
            error("join", ex);
        }
    }

    void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        try {
            if (!inWorld) return;
            inWorld = false;
            target = null;
            openTotals = null;
            log.add(ev("leave"));
        } catch (RuntimeException ex) {
            error("leave", ex);
        }
    }

    void onClone(ClientPlayerNetworkEvent.Clone e) {
        try {
            if (!on() || !e.getOldPlayer().isDeadOrDying()) return;          // a dimension change also clones
            log.add(ev("respawn"));
            inWorld = false;                                                   // fresh tick state next tick
        } catch (RuntimeException ex) {
            error("respawn", ex);
        }
    }

    void onEat(LivingEntityUseItemEvent.Finish e) {
        try {
            if (!on() || e.getEntity() != Minecraft.getInstance().player) return;
            ItemStack s = e.getItem();
            if (s.get(DataComponents.FOOD) == null) return;
            log.add(ev("eat").str("item", id(s)).num("food", food >= 0 ? food : ((Player) e.getEntity()).getFoodData().getFoodLevel()));   // the food bar before eating (last tick)
        } catch (RuntimeException ex) {
            error("eat", ex);
        }
    }

    void onScreenOpen(ScreenEvent.Opening e) {
        try {
            if (!on()) return;
            Screen s = e.getNewScreen();
            if (!(s instanceof AbstractContainerScreen<?> cs) || s instanceof InventoryScreen || s instanceof CreativeModeInventoryScreen) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            AbstractContainerMenu menu = cs.getMenu();
            String type;
            try {
                type = String.valueOf(BuiltInRegistries.MENU.getKey(menu.getType()));
            } catch (RuntimeException noType) {
                type = menu.getClass().getSimpleName();
            }
            openType = type;
            openCrafting = menu instanceof CraftingMenu;
            openTotals = totals(mc.player.getInventory());
            ActionLog.LogEvent ev = ev("chest").str("what", "open").str("container", type).num("free", freeSlots(mc.player.getInventory()));
            if (System.currentTimeMillis() - usedAt < 3000) ev.str("block", usedBlock).num("bx", ux).num("by", uy).num("bz", uz);
            log.add(ev);
        } catch (RuntimeException ex) {
            error("screen open", ex);
        }
    }

    void onScreenClose(ScreenEvent.Closing e) {
        try {
            if (openTotals == null || !(e.getScreen() instanceof AbstractContainerScreen<?>)) return;
            Minecraft mc = Minecraft.getInstance();
            Map<String, Integer> before = openTotals;
            openTotals = null;
            if (mc.player == null || !on()) return;
            ActionLog.LogEvent ev = ev("chest").str("what", "close").str("container", openType);
            if (!openCrafting) {                      // a crafting table's moves are the craft events
                Map<String, Integer> in = new LinkedHashMap<>(), out = new LinkedHashMap<>();
                ActionLog.diff(before, totals(mc.player.getInventory()), in, out);
                ev.counts("in", in).counts("out", out);
            }
            if (System.currentTimeMillis() - usedAt < 600_000) ev.str("block", usedBlock).num("bx", ux).num("by", uy).num("bz", uz);
            log.add(ev);
        } catch (RuntimeException ex) {
            error("screen close", ex);
        }
    }

    /** Empty slots of the 36 main ones (the bag's fullness). */
    static int freeSlots(Inventory inv) {
        int n = 0;
        for (int i = 0; i < inv.items.size(); i++) if (inv.items.get(i).isEmpty()) n++;
        return n;
    }

    private static Map<String, Integer> totals(Inventory inv) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) m.merge(id(s.getItem()), s.getCount(), Integer::sum);
        }
        return m;
    }

    // ---- from the mixins / OwnerEvents (client thread) ----

    void broke(String block, BlockPos pos) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!on() || mc.player == null) return;
            log.add(ev("break").str("block", block).str("tool", id(mc.player.getMainHandItem()))
                    .num("bx", pos.getX()).num("by", pos.getY()).num("bz", pos.getZ()));
        } catch (RuntimeException e) {
            error("break", e);
        }
    }

    void placed(String item, BlockPos pos) {
        try {
            if (!on()) return;
            log.add(ev("place").str("block", item).num("bx", pos.getX()).num("by", pos.getY()).num("bz", pos.getZ()));
        } catch (RuntimeException e) {
            error("place", e);
        }
    }

    void used(String block, BlockPos pos) {
        try {
            usedBlock = block;
            ux = pos.getX(); uy = pos.getY(); uz = pos.getZ();
            usedAt = System.currentTimeMillis();
            if (!on()) return;
            log.add(ev("interact").str("block", block).num("bx", ux).num("by", uy).num("bz", uz));
        } catch (RuntimeException e) {
            error("interact", e);
        }
    }

    void attacked(Entity e) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (e == null || e instanceof Player || mc.player == null) return;      // never other players
            target = e;
            targetWeapon = id(mc.player.getMainHandItem());
            targetAt = System.currentTimeMillis();
        } catch (RuntimeException ex) {
            error("attack", ex);
        }
    }

    /** An inventory click (HEAD of handleInventoryMouseClick): crafts, furnace output, throws. */
    public void click(int slotId, int button, ClickType type, Player player) {
        try {
            if (!on() || player == null) return;
            AbstractContainerMenu menu = player.containerMenu;
            if (slotId == -999) {
                ItemStack c = menu.getCarried();
                if (!c.isEmpty()) log.add(ev("drop").str("item", id(c)).num("count", button == 0 ? c.getCount() : 1));
                return;
            }
            if (slotId < 0 || slotId >= menu.slots.size()) return;
            Slot s = menu.getSlot(slotId);
            ItemStack st = s.getItem();
            if (st.isEmpty()) return;
            if (type == ClickType.THROW) {
                log.add(ev("drop").str("item", id(st)).num("count", button == 1 ? st.getCount() : 1));
                return;
            }
            if (type == ClickType.QUICK_CRAFT || type == ClickType.CLONE) return;
            if (s instanceof ResultSlot)
                log.add(ev("craft").str("item", id(st)).num("count", st.getCount()).bool("quick", type == ClickType.QUICK_MOVE));
            else if (s instanceof FurnaceResultSlot)
                log.add(ev("smelt").str("item", id(st)).num("count", st.getCount()).str("container", openType));
        } catch (RuntimeException e) {
            error("click", e);
        }
    }

    /** The server said an item entity went to a player (client thread). */
    public void pickup(Minecraft mc, int itemId, int playerId, int amount) {
        try {
            if (!on() || mc.player == null || mc.level == null || playerId != mc.player.getId()) return;
            Entity e = mc.level.getEntity(itemId);
            if (!(e instanceof ItemEntity ie)) return;
            log.add(ev("pickup").str("item", id(ie.getItem())).num("count", amount));
        } catch (RuntimeException ex) {
            error("pickup", ex);
        }
    }

    /** Q / ctrl-Q with the held item. */
    public void dropHeld(LocalPlayer p, boolean full) {
        try {
            if (!on() || p == null) return;
            ItemStack s = p.getMainHandItem();
            if (!s.isEmpty()) log.add(ev("drop").str("item", id(s)).num("count", full ? s.getCount() : 1));
        } catch (RuntimeException e) {
            error("drop", e);
        }
    }

    /** The owner's own /bot line (never anyone else's chat). */
    static void chat(String line) {
        ActionLogMc l = INSTANCE;
        if (l == null) return;
        Minecraft.getInstance().execute(() -> {
            try {
                if (l.on() && Minecraft.getInstance().player != null) l.log.add(l.ev("chat").str("bot", line));
            } catch (RuntimeException e) {
                l.error("chat", e);
            }
        });
    }

    // ---- /bot log ----

    void command(String arg) {
        String a = arg.trim();
        String lower = a.toLowerCase(java.util.Locale.ROOT);
        if (lower.isEmpty() || lower.equals("status")) {
            worker.execute(() -> companion.say(statusLine()));
            return;
        }
        if (lower.equals("on") || lower.equals("off")) {
            boolean on = lower.equals("on");
            String err = Companion.configFile == null ? "no config file" : CompanionConfig.saveBoolean(Companion.configFile, "actionLog", on);
            companion.say(err != null ? "action log: couldn't save the config (" + err + ")"
                    : on ? "action log on: your actions go to config/entropy-companion/log/ (and the dashboard when set up)"
                    : "action log off: nothing more is recorded (the files stay)");
            return;
        }
        if (lower.startsWith("mark")) {
            String note = a.substring(4).trim();
            if (note.isEmpty()) {
                companion.say("/bot log mark <note>, e.g. /bot log mark going home, the bag is full");
                return;
            }
            if (!addNow("mark", "note", note)) return;
            companion.say("marked: " + note);
            return;
        }
        if (lower.startsWith("session")) {
            String name = a.substring(7).trim();
            if (name.isEmpty()) {
                companion.say("/bot log session <name>");
                return;
            }
            if (!addNow("session", "name", name)) return;
            companion.say("this session is now called: " + name);
            return;
        }
        companion.say("/bot log status | on | off | mark <note> | session <name>");
    }

    private boolean addNow(String type, String field, String text) {
        if (!on()) {
            companion.say("action log is off (/bot log on)");
            return false;
        }
        if (Minecraft.getInstance().player == null) {
            companion.say("join a world first");
            return false;
        }
        log.add(ev(type).str(field, text.length() > 200 ? text.substring(0, 200) : text));
        return true;
    }

    /** Worker thread (reads file sizes). */
    String statusLine() {
        long now = System.currentTimeMillis();
        CompanionConfig c = config.get();
        long last = uploader.lastOkAt;
        String post = !c.active() ? "not posting (no dashboard url/key): local only"
                : uploader.failing() ? "dashboard unreachable (" + uploader.lastError + "), keeping events here"
                : last == 0 ? "no post yet" : "last post " + (now - last) / 1000 + " s ago";
        return "action log " + (on() ? "on" : "off") + ": events today " + store.linesToday(now) + " (this run " + log.emitted
                + (log.dropped + store.dropped > 0 ? ", dropped " + (log.dropped + store.dropped) : "") + "), queued "
                + kb(uploader.queuedBytes()) + " (+" + log.buffered() + " in memory), " + post + "; files " + store.files().size()
                + " days, " + kb(store.totalBytes()) + " of " + LogStore.CAP_BYTES / (1024 * 1024) + " MB, session " + log.session()
                + (errors > 0 ? "; errors " + errors : "") + ("none".equals(store.lastError) ? "" : "; file error " + store.lastError);
    }

    private static String kb(long b) {
        return b < 1024 ? b + " B" : b < 1024 * 1024 ? (b / 1024) + " KB" : String.format(java.util.Locale.ROOT, "%.1f MB", b / 1048576.0);
    }
}
