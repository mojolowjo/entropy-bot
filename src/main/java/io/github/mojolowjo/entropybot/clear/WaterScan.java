package io.github.mojolowjo.entropybot.clear;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.mojolowjo.entropybot.clear.ClearWorld.FluidCell;

/**
 * Water plan (docs/WATER_PLAN.md, 2026-10-04), "telling sources from flowing water". Game-free: what the water a dig ran
 * into is. From the water cells of the work (the box and the one-block shell around it) near the spot that stopped the
 * dig, a bounded search goes upstream (to stronger flowing water, up falling columns) to the sources that feed it, and
 * counts the connected source blocks found there (stopping past {@link #LARGE_LIMIT}).
 *
 * <ul>
 * <li>{@link Kind#FLOWING_IN}: no source inside the work: only the cells where the water enters need a block
 * ({@link Scan#plug}); the rest drains by itself.</li>
 * <li>{@link Kind#SOURCES_INSIDE}: a source in the box or its shell: the shell method ({@link WaterShell}).</li>
 * <li>{@link Kind#LARGE}: more than {@link #LARGE_LIMIT} sources, or more water than the search may look at: the owner
 * confirms first.</li>
 * <li>{@link Kind#LAVA}: lava among it (not handled yet: lava has stricter rules).</li>
 * </ul>
 */
public final class WaterScan {
    private WaterScan() {}

    /** More connected source blocks than this is a large body of water (the owner confirms first). */
    public static final int LARGE_LIMIT = 64;
    /** The most fluid cells the upstream search looks at. */
    public static final int MAX_VISIT = 4096;
    /** How far (blocks, each axis) from the spot the search goes. */
    public static final int RADIUS = 32;
    /** How far (blocks, each axis) from the spot the work's own water cells are gathered. */
    public static final int NEAR = 12;

    public enum Kind { NONE, FLOWING_IN, SOURCES_INSIDE, LARGE, LAVA }

    /**
     * What the search found. visited: every fluid cell it looked at; sources: the source blocks among them (at most
     * LARGE_LIMIT + 1); inside: the sources in the box or its shell; plug: for FLOWING_IN, the water cells outside the
     * box next to one of its cells (where the water enters it); truncated: it hit {@link #MAX_VISIT} or {@link #RADIUS}.
     */
    public record Scan(Kind kind, Set<Pos> visited, Set<Pos> sources, int inside, List<Pos> plug, boolean truncated) {
        /** "3 source blocks inside the dig", "flowing in from outside", "a large body of water (65+ source blocks)". */
        public String describe() {
            return switch (kind) {
                case NONE -> "no water there any more";
                case FLOWING_IN -> "water flowing in from outside" + (plug.isEmpty() ? "" : " (" + plug.size() + " cell" + (plug.size() == 1 ? "" : "s") + " to plug)");
                case SOURCES_INSIDE -> inside + " source block" + (inside == 1 ? "" : "s") + " inside the dig";
                case LARGE -> "a large body of water (" + (truncated ? "more than I can look at" : sources.size() + "+ source blocks") + ")";
                case LAVA -> "lava (I don't handle lava yet)";
            };
        }
    }

    static final int[][] SIDES4 = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };

    /** The box and the one-block shell around it (corners and edges too): "the work". */
    public static ClearBox work(ClearBox box) {
        return box.grow(1);
    }

    /** The cell is outside the box and shares a face with one of its cells. */
    public static boolean faceShell(ClearBox box, int x, int y, int z) {
        if (box.contains(x, y, z)) return false;
        int out = 0;
        if (x < box.x1() || x > box.x2()) out += x == box.x1() - 1 || x == box.x2() + 1 ? 1 : 2;
        if (y < box.y1() || y > box.y2()) out += y == box.y1() - 1 || y == box.y2() + 1 ? 1 : 2;
        if (z < box.z1() || z > box.z2()) out += z == box.z1() - 1 || z == box.z2() + 1 ? 1 : 2;
        return out == 1;
    }

    /**
     * Classifies the water around {@code at} (a fluid cell next to the block that stopped the dig) for a dig of
     * {@code box}.
     */
    public static Scan scan(ClearWorld w, ClearBox box, Pos at) {
        ClearBox work = work(box);
        Set<Pos> visited = new LinkedHashSet<>(), sources = new LinkedHashSet<>();
        if (at == null || !w.fluid(at.x(), at.y(), at.z())) return new Scan(Kind.NONE, visited, sources, 0, List.of(), false);
        // 1. the spot and the box's water near it, joined to it through fluid cells inside the box (not the shell: water
        //    there that only flows away from the box is downstream, not part of what feeds it)
        List<Pos> seeds = new ArrayList<>();
        Set<Pos> seen = new LinkedHashSet<>();
        ArrayDeque<Pos> q = new ArrayDeque<>();
        q.add(at);
        seen.add(at);
        boolean lava = false, truncated = false;
        while (!q.isEmpty()) {
            Pos c = q.poll();
            if (!"water".equals(w.fluidKind(c.x(), c.y(), c.z()))) lava = true;
            seeds.add(c);
            for (int[] s : ClearEngine.SIDES6) {
                Pos n = new Pos(c.x() + s[0], c.y() + s[1], c.z() + s[2]);
                if (!box.contains(n.x(), n.y(), n.z()) || far(at, n, NEAR) || seen.contains(n) || !w.fluid(n.x(), n.y(), n.z())) continue;
                if (seen.size() >= MAX_VISIT) {
                    truncated = true;
                    continue;
                }
                seen.add(n);
                q.add(n);
            }
        }
        if (lava) return new Scan(Kind.LAVA, new LinkedHashSet<>(seeds), sources, 0, List.of(), truncated);
        // 2. upstream: stronger flowing water beside, the water above a falling cell; from a source, the sources joined to it
        visited.addAll(seeds);
        q.addAll(seeds);
        while (!q.isEmpty() && sources.size() <= LARGE_LIMIT) {
            Pos c = q.poll();
            FluidCell f = w.fluidCell(c.x(), c.y(), c.z());
            if (f == null) continue;
            if (!"water".equals(w.fluidKind(c.x(), c.y(), c.z()))) return new Scan(Kind.LAVA, visited, sources, 0, List.of(), truncated);
            List<Pos> up = new ArrayList<>();
            if (f.source()) {
                sources.add(c);
                for (int[] s : ClearEngine.SIDES6) up.add(new Pos(c.x() + s[0], c.y() + s[1], c.z() + s[2]));
            } else {
                if (f.falling()) up.add(new Pos(c.x(), c.y() + 1, c.z()));
                for (int[] s : SIDES4) up.add(new Pos(c.x() + s[0], c.y(), c.z() + s[2]));
            }
            for (Pos n : up) {
                if (visited.contains(n)) continue;
                FluidCell nf = w.fluidCell(n.x(), n.y(), n.z());
                if (nf == null) continue;
                boolean feeds;
                if (f.source()) feeds = nf.source();                                    // the body of sources
                else if (n.y() > c.y()) feeds = true;                                   // the column above a falling cell
                else feeds = nf.level() > f.level() || (nf.source() && n.y() == c.y());  // stronger water beside
                if (!feeds) continue;
                if (far(at, n, RADIUS) || visited.size() >= MAX_VISIT) {
                    truncated = true;
                    continue;
                }
                visited.add(n);
                q.add(n);
            }
        }
        int inside = 0;
        for (Pos s : sources) if (work.contains(s.x(), s.y(), s.z())) inside++;
        if (sources.size() > LARGE_LIMIT || truncated) return new Scan(Kind.LARGE, visited, sources, inside, List.of(), truncated);
        if (inside > 0) return new Scan(Kind.SOURCES_INSIDE, visited, sources, inside, List.of(), false);
        List<Pos> plug = new ArrayList<>();
        for (Pos c : visited) if (faceShell(box, c.x(), c.y(), c.z())) plug.add(c);
        return new Scan(Kind.FLOWING_IN, visited, sources, 0, plug, false);
    }

    private static boolean far(Pos a, Pos b, int r) {
        return Math.abs(a.x() - b.x()) > r || Math.abs(a.y() - b.y()) > r || Math.abs(a.z() - b.z()) > r;
    }
}
