package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.MiningTiers;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * simple-tools (0.39.w1): the one answer to "what is nearby". Baritone is asked first, through the same cached world scan its
 * mine process uses ({@code IWorldScanner.scanChunkRadius}); AA's own sightings are only the fallback when that scan finds
 * nothing. {@link NeedChain}, {@link RungOptions.Tools} and the planner's "a tree seen" all read this fact and no other.
 * <ul>
 * <li>{@link #log}: the nearest log block (any block the game tags as logs) that has leaves (any block tagged leaves) within
 *     {@code nearby.leafReach} blocks above or beside it, i.e. a tree and not a stack of logs in a build.</li>
 * <li>{@link #stone}: the nearest stone-like block (the game's data, {@link MiningTiers}) the bot may dig.</li>
 * </ul>
 * Pure (JUnit: NearbyTest); the live {@link Scan} is {@code commands.NearbyLive}. Loader notes: Baritone API only.
 */
public final class Nearby {
    private Nearby() {}

    /** What Baritone's scan and the level answer. Lists need not be sorted. */
    public interface Scan {
        /** Log blocks (tagged logs) within {@code radius}, as {x, y, z}. */
        List<int[]> logs(int radius);
        /** Stone-like blocks within {@code radius}, as {x, y, z}. */
        List<int[]> stones(int radius);
        /** A block tagged leaves within {@code reach} blocks beside or above the cell. */
        boolean leavesNear(int x, int y, int z, int reach);
        /** The policy lets me work at the cell. */
        boolean allowed(int x, int y, int z);
        /** The block id at the cell. */
        String blockAt(int x, int y, int z);
    }

    public static int scanR() { return Tuning.i("nearby.scanR"); }
    public static int maxChecks() { return Tuning.i("nearby.maxChecks"); }
    public static int leafReach() { return Tuning.i("nearby.leafReach"); }

    /** The nearest tree's log by Baritone's scan, else the fallback (AA's sightings); null: none. */
    public static Spot log(Scan s, int[] me, Supplier<Spot> fallback) {
        if (s != null) {
            int checked = 0;
            for (int[] p : sorted(s.logs(scanR()), me)) {
                if (checked++ >= maxChecks()) break;
                if (!s.allowed(p[0], p[1], p[2]) || !s.leavesNear(p[0], p[1], p[2], leafReach())) continue;
                return new Spot(p[0], p[1], p[2], dist(p, me), s.blockAt(p[0], p[1], p[2]));
            }
        }
        return fallback == null ? null : fallback.get();
    }

    /** The nearest stone-like block I may dig by Baritone's scan, else the fallback; null: none. */
    public static Spot stone(Scan s, int[] me, Supplier<Spot> fallback) {
        if (s != null) {
            int checked = 0;
            for (int[] p : sorted(s.stones(scanR()), me)) {
                if (checked++ >= maxChecks()) break;
                String id = s.blockAt(p[0], p[1], p[2]);
                if (id == null || MiningTiers.blockTierOf(id) < 1 || !s.allowed(p[0], p[1], p[2])) continue;
                return new Spot(p[0], p[1], p[2], dist(p, me), id);
            }
        }
        return fallback == null ? null : fallback.get();
    }

    private static List<int[]> sorted(List<int[]> in, int[] me) {
        List<int[]> out = new ArrayList<>(in == null ? List.of() : in);
        out.sort(Comparator.comparingDouble(p -> dist(p, me)));
        return out;
    }

    private static double dist(int[] p, int[] me) {
        double dx = p[0] - me[0], dy = p[1] - me[1], dz = p[2] - me[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
