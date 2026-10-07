package io.github.mojolowjo.entropybot.engine;

import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The game side of {@link HostileRules} (0.19.1): the owner's hostile list (kept in {@code entropybot\defence.json}),
 * who hit the bot lately (retaliation), and the never-attack rule for players and pets. Used by {@link Reflexes},
 * {@link CreeperDuel} and {@code debug mobs}, so all of them judge a mob the same way. Client thread only.
 *
 * <p>Who hit the bot: {@code ClientPacketListener.handleDamageEvent} calls {@code LivingEntity.handleDamageEvent(source)},
 * which stores a new {@link DamageSource} (built from the packet's cause and direct entity ids on the client level) and
 * the game time; {@code getLastDamageSource()} returns it for 40 ticks. Polled once a tick: a new source object is a new
 * hit, and its {@code getEntity()} (the causer: the shooter for an arrow) is remembered for {@link HostileRules#RETALIATE_TICKS}.
 */
public final class Hostility {
    private static final Logger LOG = LogUtils.getLogger();
    public static final Hostility INSTANCE = new Hostility();

    private volatile List<String> ids = HostileRules.DEFAULTS;
    private volatile Set<String> idSet = Set.copyOf(HostileRules.DEFAULTS);
    private volatile boolean loaded, loadFailed;
    private volatile String note = "not loaded yet";
    // retaliation: entity id -> the tick of its last hit
    private final Map<Integer, Long> hitBy = new HashMap<>();
    private DamageSource lastSource;
    private long now;
    private String lastAttacker;

    private Hostility() {}

    // ---- who hit the bot ----

    /**
     * Once a tick (Reflexes.step): notes a new hit's attacker. True when a hit by a living non-player was seen this tick
     * (the caller counts the bot as hurt then, even when absorption took the damage). Never throws.
     */
    public boolean observe(net.minecraft.client.player.LocalPlayer p, long tick) {
        now = tick;
        hitBy.values().removeIf(t -> !HostileRules.retaliating(t, now));
        try {
            DamageSource src = p.getLastDamageSource();
            if (src == null || src == lastSource) return false;
            lastSource = src;
            Entity a = src.getEntity();
            boolean record = HostileRules.recordAttacker(a != null, a == p, a instanceof LivingEntity, a instanceof Player);
            if (!record) return false;
            if (hitBy.size() >= HostileRules.MAX_ATTACKERS && !hitBy.containsKey(a.getId())) {
                Integer oldest = null;
                for (Map.Entry<Integer, Long> e : hitBy.entrySet()) if (oldest == null || e.getValue() < hitBy.get(oldest)) oldest = e.getKey();
                hitBy.remove(oldest);
            }
            hitBy.put(a.getId(), now);
            lastAttacker = idOf(a);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** It hit the bot within the retaliation window. */
    public boolean hitMe(Entity e) {
        return HostileRules.retaliating(hitBy.get(e.getId()), now);
    }

    /** The last attacker's type id (for status), or null. */
    public String lastAttacker() { return lastAttacker; }

    // ---- the verdict ----

    /** Never attacked: a player, a tamed mob, or anything with an owner. */
    public static boolean protectedMob(Entity e) {
        boolean tame = e instanceof TamableAnimal t && t.isTame();
        boolean owned = false;
        try {
            owned = e instanceof OwnableEntity o && o.getOwnerUUID() != null;
        } catch (RuntimeException ignored) {
            owned = true;       // can't tell: treat as a pet
        }
        return HostileRules.protectedMob(e instanceof Player, tame, owned);
    }

    /** The final check before any attack: false for a player or a pet. */
    public static boolean mayAttack(Entity e) {
        return e != null && !protectedMob(e);
    }

    static boolean peaceful(Entity e) {
        return e instanceof AbstractVillager || e instanceof IronGolem || e instanceof SnowGolem;
    }

    public boolean onList(Entity e) {
        return idSet.contains(idOf(e));
    }

    /** What the fight code makes of this living mob right now (recentlyHurt: the bot's "hurt" window). */
    public HostileRules.Kind kind(Entity e, boolean recentlyHurt) {
        boolean tame = e instanceof TamableAnimal t && t.isTame();
        boolean owned;
        try {
            owned = e instanceof OwnableEntity o && o.getOwnerUUID() != null;
        } catch (RuntimeException ex) {
            owned = true;
        }
        return HostileRules.classify(e instanceof Player, tame, owned, onList(e), e instanceof Enemy, e instanceof NeutralMob,
                recentlyHurt, hitMe(e), peaceful(e));
    }

    /** A retaliation target too strong to fight (HostileRules.strong with the bot's best weapon). */
    public static boolean strong(net.minecraft.client.player.LocalPlayer p, LivingEntity e) {
        return HostileRules.strong(e.getMaxHealth(), weaponHit(p));
    }

    /** The bot's best sword (else axe) hit: 1 + its main-hand attack damage modifiers (ATTACK_DAMAGE is not synced). */
    public static double weaponHit(net.minecraft.client.player.LocalPlayer p) {
        var inv = p.getInventory();
        double best = 1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!(s.getItem() instanceof SwordItem) && !(s.getItem() instanceof AxeItem)) continue;
            double[] add = {0};
            s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).forEach(EquipmentSlot.MAINHAND, (attr, mod) -> {
                if (attr.value() == Attributes.ATTACK_DAMAGE.value() && mod.operation() == AttributeModifier.Operation.ADD_VALUE) add[0] += mod.amount();
            });
            best = Math.max(best, 1 + add[0]);
        }
        return best;
    }

    static String idOf(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    // ---- the list and its file ----

    public List<String> ids() { return ids; }

    public boolean loadFailed() { return loadFailed; }

    public String note() { return note; }

    /** Once the bot's folder is known (the first tick in a world): read defence.json. Never throws. */
    public void ensureLoaded() {
        if (loaded) return;
        try {
            io.github.mojolowjo.entropybot.io.BotFiles files = io.github.mojolowjo.entropybot.Core.INSTANCE.files();
            if (files == null) return;
            loaded = true;
            String text = files.readJson(HostileRules.FILE);
            HostileRules.Parsed p;
            if (text != null && text.startsWith("error: ")) p = new HostileRules.Parsed(HostileRules.DEFAULTS, "couldn't read (" + text.substring(7) + "), using the default hostile list", true);
            else p = HostileRules.parse(text);
            set(p.ids());
            loadFailed = p.failed();
            if (p.failed()) keepBroken(files.root());
            note = HostileRules.FILE + ": " + (text == null ? "none yet (default list)" : p.note() == null ? "loaded" : p.note());
            if (p.note() != null) LOG.warn("[entropybot] hostile list: {}: {}", HostileRules.FILE, p.note());
            else LOG.info("[entropybot] hostile list: {} ({} ids)", note, ids.size());
        } catch (Throwable t) {
            loadFailed = true;
            note = HostileRules.FILE + ": couldn't load (" + t + "), using the default hostile list";
            LOG.warn("[entropybot] hostile list: {}", note);
        }
    }

    /** A broken file is copied aside before the next save overwrites it. */
    private static void keepBroken(Path root) {
        try {
            Path f = root.resolve(HostileRules.FILE);
            if (Files.exists(f)) Files.copy(f, root.resolve(HostileRules.FILE + ".bad"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOG.warn("[entropybot] hostile list: couldn't keep the broken file: {}", e.toString());
        }
    }

    private void set(List<String> list) {
        ids = List.copyOf(list);
        idSet = Set.copyOf(list);
    }

    /** "defend hostile ...": shows or changes the list (saved at once). Never throws. */
    public String command(String rest) {
        ensureLoaded();
        HostileRules.Result r;
        try {
            r = HostileRules.command(rest, ids, id -> {
                ResourceLocation rl = ResourceLocation.tryParse(id);
                return rl != null && BuiltInRegistries.ENTITY_TYPE.containsKey(rl);
            });
        } catch (RuntimeException e) {
            return "error: " + e;
        }
        if (r.changed() == null) return r.text() + (loadFailed ? " (" + note + ")" : "");
        set(r.changed());
        return r.text() + save();
    }

    private String save() {
        try {
            io.github.mojolowjo.entropybot.io.BotFiles files = io.github.mojolowjo.entropybot.Core.INSTANCE.files();
            if (files == null) return " (not saved: not in a world yet)";
            loaded = true;
            String w = files.writeJson(HostileRules.FILE, HostileRules.toJson(ids));
            if (w.startsWith("ok")) {
                loadFailed = false;
                note = HostileRules.FILE + ": saved";
                return "";
            }
            note = HostileRules.FILE + ": couldn't write (" + w + ")";
            LOG.warn("[entropybot] hostile list: {}", note);
            return " (not saved: " + w + ")";
        } catch (Throwable t) {
            note = HostileRules.FILE + ": couldn't write (" + t + ")";
            LOG.warn("[entropybot] hostile list: {}", note);
            return " (not saved: " + t + ")";
        }
    }

    /** For "check": a finding when defence.json was broken or unreadable. */
    public List<io.github.mojolowjo.entropybot.commands.SelfCheck.Finding> findings() {
        if (!loadFailed) return List.of();
        return List.of(new io.github.mojolowjo.entropybot.commands.SelfCheck.Finding("hostilelist",
                "the hostile list file is broken: " + note + " (a copy is kept as " + HostileRules.FILE + ".bad)",
                "defence hostile list, then defence hostile add <id> to write a fresh file"));
    }
}
