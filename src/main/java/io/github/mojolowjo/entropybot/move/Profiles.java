package io.github.mojolowjo.entropybot.move;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 0.23.3 movement: Baritone settings profiles. {@code walk}: a short first slice (primaryTimeoutMS 150) so the first
 * path of a leg comes quickly, sprinting on; {@code mine}: breaking jobs, a longer slice (Baritone's defaults);
 * {@code flee} (0.23.2's flight): the shortest slices. NONE puts back what was there before the first profile.
 * Settings used: {@code Settings.primaryTimeoutMS}, {@code failureTimeoutMS}, {@code planAheadPrimaryTimeoutMS},
 * {@code allowSprint}. Pure over {@link Access} (the game side reads/writes {@code BaritoneAPI.getSettings().byLowerName}).
 */
public final class Profiles {
    public enum Profile { NONE, WALK, MINE, FLEE }

    /** Get/set a Baritone setting by lower-case name. */
    public interface Access {
        Object get(String name);

        void set(String name, Object value);
    }

    public static Map<String, Object> values(Profile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (p) {
            case WALK -> {
                m.put("primarytimeoutms", 150L);
                m.put("failuretimeoutms", 1500L);
                m.put("planaheadprimarytimeoutms", 2000L);
                m.put("allowsprint", true);
            }
            case MINE -> {
                m.put("primarytimeoutms", 500L);
                m.put("failuretimeoutms", 2000L);
                m.put("planaheadprimarytimeoutms", 4000L);
                m.put("allowsprint", true);
            }
            case FLEE -> {
                m.put("primarytimeoutms", 100L);
                m.put("failuretimeoutms", 800L);
                m.put("planaheadprimarytimeoutms", 1000L);
                m.put("allowsprint", true);
            }
            default -> { }
        }
        return m;
    }

    /** Which profile fits now: fleeing beats breaking beats walking; nothing of these: NONE. */
    public static Profile choose(boolean walking, boolean breaking, boolean fleeing) {
        if (fleeing) return Profile.FLEE;
        if (breaking) return Profile.MINE;
        if (walking) return Profile.WALK;
        return Profile.NONE;
    }

    private Profile active = Profile.NONE;
    private final Map<String, Object> saved = new LinkedHashMap<>();
    private long switches;

    /** Originals of allowsprint/allowparkour while hunger is being conserved (null: not conserving). */
    private Map<String, Object> held;

    public boolean conserving() { return held != null; }

    /**
     * Hunger conserving (HungerConserve): sprint and parkour off, except that fleeing keeps sprint. Idempotent, called
     * every movement tick; the first call keeps the originals, {@code on == false} puts them back.
     */
    public void conserve(boolean on, boolean fleeing, Access a) {
        if (!on) {
            if (held != null) { held.forEach((k, v) -> { if (v != null) a.set(k, v); }); held = null; }
            return;
        }
        if (held == null) {
            held = new LinkedHashMap<>();
            held.put("allowsprint", a.get("allowsprint"));
            held.put("allowparkour", a.get("allowparkour"));
        }
        a.set("allowsprint", fleeing);
        a.set("allowparkour", false);
    }

    public Profile active() { return active; }

    public long switches() { return switches; }

    /** Switches to p (no-op when it is active): the first switch away from NONE saves the values it changes; NONE restores them. */
    public boolean apply(Profile p, Access a) {
        if (p == active) return false;
        if (active == Profile.NONE) {
            saved.clear();
            // conserving: the originals, not the values conserve() wrote
            for (String k : allNames()) saved.put(k, held != null && held.containsKey(k) ? held.get(k) : a.get(k));
        }
        if (p == Profile.NONE) {
            saved.forEach((k, v) -> { if (v != null) a.set(k, v); });
            saved.clear();
        } else {
            values(p).forEach(a::set);
        }
        active = p;
        switches++;
        return true;
    }

    static java.util.Set<String> allNames() {
        java.util.Set<String> s = new java.util.LinkedHashSet<>();
        for (Profile p : Profile.values()) s.addAll(values(p).keySet());
        return s;
    }
}
