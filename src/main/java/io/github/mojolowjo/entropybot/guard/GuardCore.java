package io.github.mojolowjo.entropybot.guard;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The guard's rules, free of Minecraft types so they can be unit-tested.
 *
 * <p>Two layers. The <b>floor</b> never moves: no break or placement in a denied dimension, inside a
 * protect box, on a block entity, or on a built block (unless a force lease covers it). The
 * <b>area and lease rules</b> on top are what the owner configures: a break or placement needs a lease
 * that contains the spot and lies inside an area; a walk goal needs an area. In {@link Mode#LOG} the
 * area and lease rules only record what they would have refused; the floor is enforced in every mode.
 *
 * <p>Fails closed: no policy, no areas or no lease means nothing breaks or is placed. Snapshots
 * ({@code policy}, {@code leases}) are immutable and swapped under {@code synchronized}, so the
 * pathfinder thread can call {@link #checkBoxes} without locks.
 */
public final class GuardCore {

    public enum Mode { STRICT, LOG }

    public static final Set<String> DENIED_DIMS = Set.of("minecraft:the_nether", "minecraft:the_end");
    /** The biggest box a lease may cover (the bridge's {@code dig} limit). */
    public static final long MAX_LEASE_VOLUME = 4096;
    /** A force lease (the owner's {@code dig ... force}) is smaller still. */
    public static final long MAX_FORCE_VOLUME = 64;
    /** Ticks without a heartbeat after which a lease dies. */
    public static final long LEASE_TIMEOUT_TICKS = 100;

    /** What the guard knows about the block at a spot; supplied by the Minecraft side. */
    public interface BlockInfo {
        /** False while the protected-block set is not built yet: then breaking is refused (fail closed). */
        boolean known();
        boolean hasBlockEntity();
        boolean isProtectedBlock();

        /** Water plan: the cell holds only air or water (what a seal lease may fill); true when not known. */
        default boolean airOrWater() { return true; }

        /** For a placement: the cell holds only air or water, or not. */
        static BlockInfo placing(boolean airOrWater) {
            return new BlockInfo() {
                public boolean known() { return true; }
                public boolean hasBlockEntity() { return false; }
                public boolean isProtectedBlock() { return false; }
                public boolean airOrWater() { return airOrWater; }
            };
        }

        BlockInfo UNKNOWN = of(false, false, false);
        BlockInfo PLAIN = of(true, false, false);

        static BlockInfo of(boolean known, boolean blockEntity, boolean protectedBlock) {
            return new BlockInfo() {
                public boolean known() { return known; }
                public boolean hasBlockEntity() { return blockEntity; }
                public boolean isProtectedBlock() { return protectedBlock; }
            };
        }
    }

    /** The answer to a check. {@code wouldVeto} is set in log mode when a rule other than the floor would have refused. */
    public record Verdict(boolean allowed, String reason, boolean floor, boolean wouldVeto) {
        static final Verdict OK = new Verdict(true, null, false, false);
        static Verdict floor(String reason) { return new Verdict(false, reason, true, false); }
        static Verdict rule(String reason, Mode mode) {
            return mode == Mode.LOG ? new Verdict(true, reason, false, true) : new Verdict(false, reason, false, false);
        }
    }

    /** The mode the guard starts in. B0 (0.1.x): LOG, while the bridge takes no leases yet; B1 flips it to STRICT. */
    public static final Mode DEFAULT_MODE = Mode.LOG;

    private volatile Policy policy = Policy.EMPTY;
    private volatile Mode mode = DEFAULT_MODE;
    private volatile Map<String, Lease> leases = Collections.emptyMap();
    private final VetoLog log = new VetoLog();
    private volatile long tick;
    private int nextId = 1;

    public Policy policy() { return policy; }

    public Mode mode() { return mode; }

    public VetoLog log() { return log; }

    public long tick() { return tick; }

    /** Replaces the policy. Every lease that no longer lies inside an area ends; the count is reported. */
    public synchronized String setPolicy(Policy p) {
        policy = p;
        int ended = 0;
        Map<String, Lease> m = new LinkedHashMap<>(leases);
        for (var it = m.values().iterator(); it.hasNext(); ) {
            Lease l = it.next();
            if (!p.areaCovers(l.box)) { it.remove(); ended++; }
        }
        leases = Collections.unmodifiableMap(m);
        return "ok: " + p.areas.size() + " areas, " + p.protect.size() + " protect boxes" + (ended > 0 ? ", ended " + ended + " leases now outside" : "");
    }

    public synchronized String setMode(Mode m) {
        mode = m;
        return "ok: guard mode " + m.name().toLowerCase();
    }

    /** T2: a one-cell lease at x y z would be granted and a placement there not refused by the floor (no lease taken). */
    public static boolean cellLeasable(Policy p, String dim, int x, int y, int z) {
        if (DENIED_DIMS.contains(dim) || p.protectAt(dim, x, y, z) != null) return false;
        return p.areaCovers(new Box(null, dim, x, y, z, x, y, z));
    }

    /** Takes a lease. Returns the id, or "error: ...". */
    public synchronized String lease(String owner, String task, Box box, boolean place, boolean force) {
        if (owner == null || owner.isEmpty()) return "error: no token";
        if (DENIED_DIMS.contains(box.dim)) return "error: no digging in " + box.dim;
        if (box.allY()) return "error: a lease needs y1 and y2";
        long max = force ? MAX_FORCE_VOLUME : MAX_LEASE_VOLUME;
        if (box.volume() > max) return "error: that box is " + box.volume() + " blocks, the most a lease may cover is " + max;
        if (!policy.areaCovers(box)) {
            return policy.areas.isEmpty() ? "error: no areas set" : "error: that box is not inside one of my areas";
        }
        String id = "L" + (nextId++);
        Map<String, Lease> m = new LinkedHashMap<>(leases);
        m.put(id, new Lease(id, owner, task, box, place, force, tick));
        leases = Collections.unmodifiableMap(m);
        return id;
    }

    /**
     * Water plan: a seal lease for one cell just outside the areas (a dig sealing water off at its edge: the ring around a
     * tunnel exactly as wide as its area). Granted only within one block of a break lease this owner holds (which lies
     * inside an area), never in a protect box or a denied dimension. A placement it covers may only fill water or air
     * ({@link #checkUnlogged} with {@link BlockInfo#airOrWater}). Returns the id, or "error: ...".
     */
    public synchronized String sealLease(String owner, String task, Box cell) {
        if (owner == null || owner.isEmpty()) return "error: no token";
        if (DENIED_DIMS.contains(cell.dim)) return "error: no placing in " + cell.dim;
        if (cell.volume() != 1) return "error: a seal lease is one block";
        int x = cell.x1, y = cell.y1, z = cell.z1;
        if (policy.protectAt(cell.dim, x, y, z) != null) return "error: " + x + " " + y + " " + z + " is in a protect box";
        boolean next = false;
        for (Lease l : leases.values()) {
            if (!l.owner.equals(owner) || l.place || l.seal || !l.box.dim.equals(cell.dim)) continue;
            Box b = l.box;
            if (x >= b.x1 - 1 && x <= b.x2 + 1 && y >= b.y1 - 1 && y <= b.y2 + 1 && z >= b.z1 - 1 && z <= b.z2 + 1) {
                next = true;
                break;
            }
        }
        if (!next) return "error: " + x + " " + y + " " + z + " is not within a block of my dig";
        String id = "L" + (nextId++);
        Map<String, Lease> m = new LinkedHashMap<>(leases);
        m.put(id, new Lease(id, owner, task, cell, true, false, tick, true));
        leases = Collections.unmodifiableMap(m);
        return id;
    }

    /**
     * P1 (survival plan): the place lease for putting back one block the bot itself broke (the restore ledger). Granted
     * in every mode, inside the areas or not, but never in a protect box or a denied dimension. It is a seal-kind lease:
     * the guard lets it fill only air or water. Returns the id, or "error: ...".
     */
    public synchronized String restoreLease(String owner, String task, Box cell) {
        if (owner == null || owner.isEmpty()) return "error: no token";
        if (DENIED_DIMS.contains(cell.dim)) return "error: no placing in " + cell.dim;
        if (cell.volume() != 1) return "error: a restore lease is one block";
        Box pr = policy.protectAt(cell.dim, cell.x1, cell.y1, cell.z1);
        if (pr != null) return "error: " + cell.x1 + " " + cell.y1 + " " + cell.z1 + " is in a protect box (" + (pr.name == null ? "box" : pr.name) + ")";
        String id = "L" + (nextId++);
        Map<String, Lease> m = new LinkedHashMap<>(leases);
        m.put(id, new Lease(id, owner, task, cell, true, false, tick, true));
        leases = Collections.unmodifiableMap(m);
        return id;
    }

    /** A placement at x y z is allowed only by a seal lease (it lies outside every area): it may only fill water or air. */
    public boolean sealOnly(String dim, int x, int y, int z) {
        Policy p = policy;
        if (p.areaAt(dim, x, y, z) != null) return false;
        for (Lease l : leases.values()) if (l.seal && l.box.contains(dim, x, y, z)) return true;
        return false;
    }

    public synchronized void release(String id) {
        if (id == null || !leases.containsKey(id)) return;
        Map<String, Lease> m = new LinkedHashMap<>(leases);
        m.remove(id);
        leases = Collections.unmodifiableMap(m);
    }

    public synchronized void releaseAll(String owner) {
        Map<String, Lease> m = new LinkedHashMap<>(leases);
        m.values().removeIf(l -> owner == null || l.owner.equals(owner));
        leases = Collections.unmodifiableMap(m);
    }

    /** Renews every lease of this owner. Leases without a heartbeat for {@link #LEASE_TIMEOUT_TICKS} die. */
    public synchronized int heartbeat(String owner) {
        int n = 0;
        for (Lease l : leases.values()) if (l.owner.equals(owner)) { l.lastHeartbeat = tick; n++; }
        return n;
    }

    /** Once per client tick: advances the clock and expires stale leases. */
    public synchronized void tick(long now) {
        tick = now;
        if (leases.isEmpty()) return;
        boolean stale = false;
        for (Lease l : leases.values()) if (now - l.lastHeartbeat > LEASE_TIMEOUT_TICKS) { stale = true; break; }
        if (!stale) return;
        Map<String, Lease> m = new LinkedHashMap<>(leases);
        m.values().removeIf(l -> now - l.lastHeartbeat > LEASE_TIMEOUT_TICKS);
        leases = Collections.unmodifiableMap(m);
    }

    public Map<String, Lease> leases() { return leases; }

    private Lease leaseAt(String dim, int x, int y, int z, boolean place) {
        for (Lease l : leases.values()) {
            if (!place && l.seal) continue;               // P1: a seal or restore lease only ever places
            if ((!place || l.place) && l.box.contains(dim, x, y, z)) return l;
        }
        return null;
    }

    /**
     * The box rules only (denied dimensions, protect boxes, areas, leases): cheap, lock-free, safe on
     * the pathfinder thread. {@code action} is "break", "place" or "go".
     */
    public Verdict checkBoxes(String dim, int x, int y, int z, String action) {
        Policy p = policy;
        Mode m = mode;
        if (DENIED_DIMS.contains(dim)) return Verdict.floor("no " + action + " in " + dim);
        boolean go = "go".equals(action), place = "place".equals(action);
        if (!go) {
            Box pr = p.protectAt(dim, x, y, z);
            if (pr != null) return Verdict.floor("protected (" + (pr.name == null ? "box" : pr.name) + ")");
        }
        if (p.areas.isEmpty()) return Verdict.rule("no areas set", m);
        if (p.areaAt(dim, x, y, z) == null) {
            // water plan: a seal lease lets a block in just outside the areas (the floor above still holds)
            if (place && sealOnly(dim, x, y, z)) return Verdict.OK;
            return Verdict.rule("outside every area", m);
        }
        if (go) return Verdict.OK;
        Lease l = leaseAt(dim, x, y, z, place);
        if (l == null) return Verdict.rule(place ? "no place lease here" : "no lease here", m);
        return Verdict.OK;
    }

    /** The full check for a real click: the floor's block rules first, then {@link #checkBoxes}. Records vetoes. */
    public Verdict check(String dim, int x, int y, int z, String action, BlockInfo block) {
        Verdict v = checkUnlogged(dim, x, y, z, action, block);
        if (!v.allowed() || v.wouldVeto()) log.record(tick, action, dim, x, y, z, v.reason(), v.floor(), !v.allowed());
        return v;
    }

    /** Like {@link #check} but never logs: for dry runs ({@code guard check}). */
    public Verdict checkUnlogged(String dim, int x, int y, int z, String action, BlockInfo block) {
        if ("break".equals(action)) {
            if (!block.known()) return Verdict.floor("protected blocks not loaded yet");
            if (block.hasBlockEntity()) return Verdict.floor("block entity (a chest, machine, bed...)");
            if (block.isProtectedBlock()) {
                Lease f = leaseAt(dim, x, y, z, false);
                if (f == null || !f.force) return Verdict.floor("built block");
            }
        }
        if ("place".equals(action) && !block.airOrWater() && sealOnly(dim, x, y, z)) {
            return Verdict.floor("a seal only fills water or air");
        }
        return checkBoxes(dim, x, y, z, action);
    }

    /**
     * Wave 1 (item 3): the tail "guard check ... break" adds when the block is next to a liquid ("lava" / "water",
     * Guard.liquidNextTo), "" for none: the guard only knows boxes, the digging never breaks such a block.
     */
    public static String liquidNote(String liquid) {
        return liquid == null ? "" : " - but it is next to " + liquid + ", and I never break a block next to water or lava";
    }

    /** The liquid in one cell: "lava", "water" or null (none, or not loaded). */
    public interface FluidAt {
        String at(int x, int y, int z);
    }

    /** "lava" or "water" next to x y z (above and the four sides: the cells the digging looks at), lava first; else null. */
    public static String liquidNextTo(FluidAt f, int x, int y, int z) {
        int[][] sides = {{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        boolean water = false;
        for (int[] s : sides) {
            String l = f.at(x + s[0], y + s[1], z + s[2]);
            if ("lava".equals(l)) return "lava";
            if (l != null) water = true;
        }
        return water ? "water" : null;
    }

    /** "guard check x y z <action>": the guard's answer, plus the liquid note for a break (not for an error). */
    public static String checkReply(String answer, String action, String liquid) {
        if (answer == null || !"break".equalsIgnoreCase(action) || answer.startsWith("error")) return answer;
        return answer + liquidNote(liquid);
    }

    public JsonObject status(int floorBlocks, boolean astarApplied, boolean clickApplied) {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode.name().toLowerCase());
        o.addProperty("floorBlocks", floorBlocks);
        o.add("deniedDims", toArray(DENIED_DIMS));
        o.add("areas", policy.toJson().getAsJsonArray("areas"));
        o.add("protect", policy.toJson().getAsJsonArray("protect"));
        JsonArray ls = new JsonArray();
        for (Lease l : leases.values()) ls.add(l.toJson());
        o.add("leases", ls);
        o.addProperty("vetoes", log.vetoes());
        o.addProperty("wouldVetoes", log.wouldVetoes());
        o.addProperty("astar", astarApplied);
        o.addProperty("click", clickApplied);
        o.addProperty("tick", tick);
        return o;
    }

    private static JsonArray toArray(Set<String> s) {
        JsonArray a = new JsonArray();
        for (String x : s.stream().sorted().toList()) a.add(x);
        return a;
    }
}
