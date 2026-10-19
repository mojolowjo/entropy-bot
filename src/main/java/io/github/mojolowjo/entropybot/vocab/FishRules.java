package io.github.mojolowjo.entropybot.vocab;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * aa-kelpfish: {@code fish <n|minutes>}, the food way "fishing" (brain-b1: skipped, no verb). Pure.
 *
 * <p>Game rules (verified in FishingHook.java, 1.21.1): using the rod throws a bobber ({@code Player.fishing}); using it
 * again while it floats reels it in and catches what hangs on it. A fish bites after 5 to 30 s ({@code timeUntilHooked},
 * lure shortens it) and the bite lasts {@code nibble} = 20 to 40 ticks, during which the synced flag DATA_BITING is
 * true and the bobber dips. Reeling in during the nibble catches a fish; too early or late catches nothing. The flag is
 * private, but the client's {@code SynchedEntityData.getNonDefaultValues()} is public and lists it as {@code true} only
 * while it differs from its default (false): {@link #bite} reads that. A bobber in open water (a 5x4x5 volume around it
 * with water and air only) can also catch treasure; plain water catches fish and junk, so the spot rule asks for a 3x3
 * water surface, not the full open-water volume. The rod is {@code 3 sticks + 2 string} (shaped recipe).
 *
 * <p>Loader notes: none here; the game side is {@code commands.Fishing} (vanilla Player.fishing, FishingHook entity
 * data, Hotbar.toHand, {@code MultiPlayerGameMode.useItem}).
 */
public final class FishRules {
    private FishRules() {}

    public static final String ROD = "minecraft:fishing_rod";
    /** What counts as a fish for the count (pufferfish poisons, so it is no food). */
    public static final List<String> FISH = List.of("minecraft:cod", "minecraft:salmon", "minecraft:tropical_fish");
    public static final int DEFAULT_N = 4, MAX_MINUTES = 30;
    public static final int RANGE = 16, MIN_CAST = 3, MAX_CAST = 9;
    /** Ticks: no bite for this long after a cast = reel in and cast again (the bobber may sit in a bad spot). */
    public static final long BITE_WAIT = 40 * 20L;
    /** Ticks between a reel and the next cast (the bobber is gone). */
    public static final long RECAST_DELAY = 15;
    /** A count run stops after this long (ticks), like hunt's 10 minutes, but fishing is slow: 15 minutes. */
    public static final long COUNT_MAX = 15 * 60 * 20L;

    /** n fish, or minutes (n = 0). */
    public record Parsed(int n, int minutes) {
        public boolean timed() { return n == 0; }
    }

    /** "fish", "fish 5" (5 fish), "fish 3m" or "fish 3 minutes" (3 minutes); null = bad. "status" is handled by the caller. */
    public static Parsed parse(String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase(Locale.ROOT);
        if (r.isEmpty()) return new Parsed(DEFAULT_N, 0);
        String[] w = r.split("\\s+");
        if (w.length == 1 && w[0].matches("^\\d{1,3}$")) {
            int n = Integer.parseInt(w[0]);
            return n < 1 ? null : new Parsed(n, 0);
        }
        String m = null;
        if (w.length == 1 && w[0].matches("^\\d{1,2}(m|min|mins)$")) m = w[0].replaceAll("[^0-9]", "");
        else if (w.length == 2 && w[0].matches("^\\d{1,2}$") && w[1].matches("^(m|min|mins|minute|minutes)$")) m = w[0];
        if (m == null) return null;
        int minutes = Integer.parseInt(m);
        return minutes < 1 || minutes > MAX_MINUTES ? null : new Parsed(0, minutes);
    }

    public static int fish(Map<String, Integer> bag) {
        int n = 0;
        for (String f : FISH) n += bag.getOrDefault(f, 0);
        return n;
    }

    /** A rod can be crafted: 3 sticks and 2 string. */
    public static boolean canCraftRod(int sticks, int string) {
        return sticks >= 3 && string >= 2;
    }

    /** What is missing for a rod (the reason in the status), null when a rod is in the bag or can be crafted. */
    public static String rodWhy(boolean haveRod, int sticks, int string) {
        if (haveRod || canCraftRod(sticks, string)) return null;
        return "no fishing rod and the recipe needs 3 sticks + 2 string (I have " + sticks + " sticks, " + string + " string)";
    }

    /**
     * The bite flag from the bobber's non-default synced values (SynchedEntityData.getNonDefaultValues, each item's
     * {@code value()}): DATA_BITING is the only Boolean on the hook and it is non-default only while true.
     */
    public static boolean bite(Collection<?> nonDefaultValues) {
        if (nonDefaultValues == null) return false;
        for (Object v : nonDefaultValues) if (Boolean.TRUE.equals(v)) return true;
        return false;
    }

    public enum Act { DONE_COUNT, DONE_TIME, TIMED_OUT, CRAFT_ROD, NO_ROD, WAIT, GO_WATER, NO_WATER, CAST, REEL_BITE, REEL_TIMEOUT, WAIT_BITE }

    /**
     * @param hookOut   the bobber is in the water ({@code Player.fishing != null})
     * @param biting    {@link #bite}
     * @param sinceCast ticks since the cast (0 when none)
     * @param sinceReel ticks since the last reel
     * @param spotOk    a water surface to cast at is known and the cast distance is {@link #MIN_CAST}..{@link #MAX_CAST}
     * @param spotFar   a water surface is known but out of cast distance (walk first)
     * @param caught    fish in the bag now minus at the start
     * @param elapsed   ticks since the start
     */
    public record State(boolean haveRod, boolean canCraft, boolean craftTried, boolean hookOut, boolean biting, long sinceCast,
                        long sinceReel, boolean spotOk, boolean spotFar, int caught, Parsed goal, long elapsed) {}

    /** The next thing to do, in order. Pure. */
    public static Act next(State s) {
        if (!s.goal().timed() && s.caught() >= s.goal().n()) return Act.DONE_COUNT;
        if (s.goal().timed() && s.elapsed() >= s.goal().minutes() * 60 * 20L) return Act.DONE_TIME;
        if (!s.goal().timed() && s.elapsed() >= COUNT_MAX) return Act.TIMED_OUT;
        if (!s.haveRod()) return s.canCraft() && !s.craftTried() ? Act.CRAFT_ROD : Act.NO_ROD;
        if (s.hookOut()) {
            if (s.biting()) return Act.REEL_BITE;
            if (s.sinceCast() >= BITE_WAIT) return Act.REEL_TIMEOUT;
            return Act.WAIT_BITE;
        }
        if (s.sinceReel() < RECAST_DELAY) return Act.WAIT;
        if (s.spotOk()) return Act.CAST;
        return s.spotFar() ? Act.GO_WATER : Act.NO_WATER;
    }

    /**
     * The water surface to cast at: cells {x,y,z} that are water with air above ({@code surface}), with a 3x3 water
     * surface around them (so a pond edge or a one-block puddle is no spot), between {@link #MIN_CAST} and {@link #MAX_CAST}
     * from {mx,mz} (flat distance), passing {@code inAreas}; the nearest in the cast window wins, else the nearest one
     * overall (spot far). Returns {x,y,z,farFlag(0|1)} or null.
     */
    public static int[] pick(List<int[]> surface, int mx, int mz, Predicate<int[]> inAreas) {
        java.util.Set<Long> set = new java.util.HashSet<>();
        for (int[] c : surface) set.add(key(c[0], c[2], c[1]));
        int[] near = null, far = null;
        double nd = Double.MAX_VALUE, fd = Double.MAX_VALUE;
        for (int[] c : surface) {
            if (inAreas != null && !inAreas.test(c)) continue;
            boolean open = true;
            for (int dx = -1; dx <= 1 && open; dx++)
                for (int dz = -1; dz <= 1 && open; dz++) if (!set.contains(key(c[0] + dx, c[2] + dz, c[1]))) open = false;
            if (!open) continue;
            double d = Math.hypot(c[0] - mx, c[2] - mz);
            if (d >= MIN_CAST && d <= MAX_CAST) { if (d < nd) { nd = d; near = c; } }
            else if (d < fd && d <= RANGE) { fd = d; far = c; }
        }
        if (near != null) return new int[]{near[0], near[1], near[2], 0};
        if (far != null) return new int[]{far[0], far[1], far[2], 1};
        return null;
    }

    private static long key(int x, int z, int y) {
        return ((long) x & 0x3ffffff) << 38 | ((long) z & 0x3ffffff) << 12 | ((long) y & 0xfff);
    }

    /** Where to stand for a far spot: 5 blocks short of it on the line from me (the dry side), y as the spot's. */
    public static int[] standPoint(int[] spot, int mx, int mz) {
        double dx = mx - spot[0], dz = mz - spot[2], d = Math.hypot(dx, dz);
        if (d < 1) return new int[]{spot[0], spot[1], spot[2]};
        double f = 5.0 / d;
        return new int[]{(int) Math.round(spot[0] + dx * f), spot[1], (int) Math.round(spot[2] + dz * f)};
    }
}

