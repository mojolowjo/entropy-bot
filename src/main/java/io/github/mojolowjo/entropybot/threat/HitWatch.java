package io.github.mojolowjo.entropybot.threat;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

/**
 * 0.24.4 damage log, the game side. The client never sees LivingDamageEvent (server only); it sees the synced health
 * and, through vanilla's ClientboundDamageEventPacket, {@code LivingEntity.handleDamageEvent}, which sets the last
 * damage source (type and attacker id resolved to an entity) that {@code getLastDamageSource()} returns for 40 ticks.
 * So a drop in health + absorption is one hit, its source the last damage source (else the last attacker mob,
 * {@code getLastHurtByMob}). Idempotent per health value: the reflexes and the recorder both call {@link #observe}
 * before their death checks, so the killing blow is in the list whichever ticks first.
 * Loader notes: vanilla getters only (getHealth, getAbsorptionAmount, getLastDamageSource, getLastHurtByMob); no mixin.
 * Errors: counted, the first 5 logged.
 */
public final class HitWatch {
    private static final Logger LOG = LogUtils.getLogger();
    public static final HitWatch INSTANCE = new HitWatch();
    static final String FILE = "mobdamage.json";

    private float last = -1;
    private LocalPlayer lastP;
    private boolean loaded;
    private long savedAt, errors;
    private boolean deathTaken;

    private HitWatch() {}

    public synchronized void observe(LocalPlayer p) {
        try {
            if (!loaded) load();
            if (p != lastP) {                      // a new world or a respawn: a fresh player object, no hit
                lastP = p;
                last = -1;
            }
            float hp = p.getHealth() + p.getAbsorptionAmount();
            boolean dead = p.isDeadOrDying();
            if (last >= 0 && hp < last - 0.01f) {
                DamageSource src = p.getLastDamageSource();
                Entity by = src != null ? src.getEntity() : null;
                if (by == null && src == null) by = p.getLastHurtByMob();
                String type = src != null ? src.type().msgId() : (by != null ? "mob_attack" : "unknown");
                String id = by == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(by.getType()).getPath();
                String name = by == null ? null : by.getName().getString();
                double amt = last - Math.max(0, hp);
                String job = null;
                try {
                    var j = Core.INSTANCE.commands.jobs.job;
                    job = j != null && !j.done ? j.type + " " + j.label : null;
                } catch (RuntimeException ignored) {
                    // no job
                }
                HitLog.Hit h = new HitLog.Hit(System.currentTimeMillis(), type, id, name, amt, Math.max(0, hp), p.getBlockX(), p.getBlockY(), p.getBlockZ(), job);
                HitLog.INSTANCE.add(h);
                io.github.mojolowjo.entropybot.summary.DaySummary.INSTANCE.damage(h.source(), amt);
                // a mob's melee hit at full value (not the killing blow, which may be capped by the health left)
                if (id != null && hp > 0 && src != null && src.getDirectEntity() == by) MobDamage.INSTANCE.add(id, amt);
            }
            if (dead && !deathTaken) {
                deathTaken = true;
                HitLog.INSTANCE.death(System.currentTimeMillis());
            } else if (!dead) deathTaken = false;
            last = dead ? 0 : hp;
            long now = System.currentTimeMillis();
            if (now - savedAt > 10_000 && MobDamage.INSTANCE.takeDirty()) {
                savedAt = now;
                var f = Core.INSTANCE.files();
                if (f != null) {
                    String r = f.writeJson(FILE, MobDamage.INSTANCE.toJson().toString());
                    if (!r.startsWith("ok")) LOG.warn("[entropybot] hits: couldn't write {}: {}", FILE, r);
                }
            }
        } catch (RuntimeException e) {
            if (errors++ < 5) LOG.warn("[entropybot] hits: {}", e.toString());
        }
    }

    private void load() {
        var f = Core.INSTANCE.files();
        if (f == null) return;
        loaded = true;
        String t = f.readJson(FILE);
        if (t == null) return;
        try {
            MobDamage.INSTANCE.load(com.google.gson.JsonParser.parseString(t).getAsJsonObject());
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] hits: {} can't be read ({}), starting empty", FILE, e.toString());
        }
    }
}
