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
 * protect box (a no-break or keep-out area), no walk goal inside a keep-out area, on a block entity, or on a
 * built block (unless a force lease covers it). The <b>lease rule</b> on top: a break or placement needs a lease
 * that contains the spot. AREAS (0.39.0, AREAS_PLAN 4): the world is open by default - no area at all means
 * walking, leases and jobs are fine anywhere; an area is a label, and only keep-out / no-break bind here. In
 * {@link Mode#LOG} the lease rule only records what it would have refused; the floor is enforced in every mode.
 *
 * <p>Fails closed on the floor and the lease: no lease means nothing breaks or is placed. Snapshots
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

        /** 0.23.1: for a placement: the item put down is a torch (the only placement roaming allows). */
        default boolean torch() { return false; }

        /** For a placement: the cell holds only air or water, or not. */
        static BlockInfo placing(boolean airOrWater) { return placing(airOrWater, false); }

        static BlockInfo placing(boolean airOrWater, boolean torch) {
            return new BlockInfo() {
                public boolean known() { return true; }
                public boolean hasBlockEntity() { return false; }
                public boolean isProtectedBlock() { return false; }
                public boolean airOrWater() { return airOrWater; }
                public boolean torch() { return torch; }
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

    /**
     * The policy every check reads: the owner's labelled areas. AREAS (0.39.0): the near-me zone ({@link NearZone}) no
     * longer joins it - it is a fact for AA ({@code area.nearOwner}), never a permission, so a stranger's base near the
     * owner stays as protected as anywhere else.
     */
    private volatile Policy policy = Policy.EMPTY;
    /** The near-me zone's settings and state (the commands feed it the owner's spot once a second); a fact only. */
    public final NearZone near = new NearZone();
    private volatile Mode mode = DEFAULT_MODE;
    private volatile Map<String, Lease> leases = Collections.emptyMap();
    private final VetoLog log = new VetoLog();
    private volatile long tick;
    private int nextId = 1;

    public Policy policy() { return policy; }

    /** The owner's areas and protect boxes (the same as {@link #policy} since 0.39.0; kept for the callers). */
    public Policy basePolicy() { return policy; }

    public Mode mode() { return mode; }

    public VetoLog log() { return log; }

    public long tick() { return tick; }

    /** Replaces the policy. Every lease that now touches a keep-out or no-break area ends; the count is reported. */
    public synchronized String setPolicy(Policy p) {
        policy = p;
        int ended = 0;
        Map<String, Lease> m = new LinkedHashMap<>(leases);
        for (var it = m.values().iterator(); it.hasNext(); ) {
            Lease l = it.next();
            if (p.safeTouching(l.box) != null) { it.remove(); ended++; }
        }
        leases = Collections.unmodifiableMap(m);
        return "ok: " + p.areas.size() + " areas, " + p.protect.size() + " protect boxes" + (ended > 0 ? ", ended " + ended + " leases now in a protected area" : "");
    }

    public synchronized String setMode(Mode m) {
        mode = m;
        return "ok: fence mode " + m.name().toLowerCase();
    }

    /** T2: a one-cell lease at x y z would be granted and a placement there not refused by the floor (no lease taken). */
    public static boolean cellLeasable(Policy p, String dim, int x, int y, int z) {
        return !DENIED_DIMS.contains(dim) && p.protectAt(dim, x, y, z) == null;
    }

    /** AREAS: the refusal for a lease box that touches a keep-out or no-break area, or null. */
    public static String protectedRefusal(Policy p, Box box) {
        Box pr = p.safeTouching(box);
        return pr == null ? null : "error: that box touches " + (pr.name == null ? "a protected area" : pr.name) + " (" + pr.type.word() + ")";
    }

    /** Takes a lease: anywhere but a denied dimension or a box touching a keep-out / no-break area. Returns the id, or "error: ...". */
    public synchronized String lease(String owner, String task, Box box, boolean place, boolean force) {
        if (owner == null || owner.isEmpty()) return "error: no token";
        if (DENIED_DIMS.contains(box.dim)) return "error: no digging in " + box.dim;
        if (box.allY()) return "error: a lease needs y1 and y2";
        long max = force ? MAX_FORCE_VOLUME : MAX_LEASE_VOLUME;
        if (box.volume() > max) return "error: that box is " + box.volume() + " blocks, the most a lease may cover is " + max;
        String prot = protectedRefusal(policy, box);
        if (prot != null) return prot;
        String id = "L" + (nextId++);
        Map<String, Lease> m = new LinkedHashMap<>(leases);
        m.put(id, new Lease(id, owner, task, box, place, force, tick, false, false));
        leases = Collections.unmodifiableMap(m);
        return id;
    }

    /**
     * V1a (0.22.0): a destroy lease: granted only for the owner's own "dig &lt;area&gt;" ({@code ownerDigArea}) when the box
     * lies wholly inside that one area and the area's type is destroy (AreaTypeRules.breakBuilt), at most
     * {@link #MAX_LEASE_VOLUME}. Its box may lose built blocks too; block entities stay floor. Granted and refused ones
     * go into the veto log's event list. Returns the id, or "error: ...".
     */
    public synchronized String destroyLease(String owner, String task, Box box, String areaName, boolean ownerDigArea) {
        if (owner == null || owner.isEmpty()) return "error: no token";
        if (DENIED_DIMS.contains(box.dim)) return "error: no digging in " + box.dim;
        if (box.allY()) return "error: a lease needs y1 and y2";
        if (box.volume() > MAX_LEASE_VOLUME) return "error: that box is " + box.volume() + " blocks, the most a lease may cover is " + MAX_LEASE_VOLUME;
        Box a = policy.destroyCovering(box, areaName == null ? "" : areaName);
        if (a == null || !AreaTypeRules.breakBuilt(a.type, ownerDigArea)) {
            String why = !ownerDigArea ? "built blocks go only with the owner's own dig <area>"
                    : "that box is not inside the destroy area " + areaName;
            log.record(tick, "destroy", box.dim, box.x1, box.y1, box.z1, "destroy lease refused: " + why, false, true);
            return "error: " + why;
        }
        String id = "L" + (nextId++);
        Lease l = new Lease(id, owner, task, box, false, false, tick, false, false);
        l.destroy = true;
        l.area = a.name;
        Map<String, Lease> m = new LinkedHashMap<>(leases);
        m.put(id, l);
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

    /** A break-capable force or destroy lease covers this cell (any of them, whatever else covers it too). */
    boolean forceLeaseAt(String dim, int x, int y, int z) {
        for (Lease l : leases.values()) {
            if (l.seal || !(l.force || l.destroy)) continue;
            if (l.box.contains(dim, x, y, z)) return true;
        }
        return false;
    }

    /**
     * The box rules only (denied dimensions, keep-out and no-break areas, leases): cheap, lock-free, safe on the
     * pathfinder thread. {@code action} is "break", "place" or "go". AREAS: no "outside every area" verdict any more -
     * the world is open; a keep-out box refuses a walk goal as the floor does, a no-break box is walked through.
     */
    public Verdict checkBoxes(String dim, int x, int y, int z, String action) {
        Policy p = policy;
        Mode m = mode;
        if (DENIED_DIMS.contains(dim)) return Verdict.floor("no " + action + " in " + dim);
        boolean go = "go".equals(action), place = "place".equals(action);
        Box pr = p.protectAt(dim, x, y, z);
        if (pr != null && (!go || pr.type == AreaType.KEEP_OUT)) {
            return Verdict.floor("in " + (pr.name == null ? "box" : pr.name) + " (" + pr.type.word() + ")");
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
                // 0.26.5: any covering force/destroy lease counts, not only the first lease found (the shelter's own place
                // leases on its wall cells came first and hid the one-cell force lease: the wall never broke)
                if (!forceLeaseAt(dim, x, y, z)) return Verdict.floor("built block");
            }
        }
        if ("place".equals(action) && !block.airOrWater() && sealOnly(dim, x, y, z)) {
            return Verdict.floor("a seal only fills water or air");
        }
        return checkBoxes(dim, x, y, z, action);
    }

    /**
     * Mining plan phase 1: a real break click under the TRAVEL intent. The floor and the box rules as before; when only
     * a box rule (no areas, outside every area, no lease here) would refuse and {@link BreakPolicy} allows the break
     * for travel, the click goes through: travel breaks need no lease, the rule set and the cost decide. Returns the
     * policy's verdict in {@code travel} when it decided (null otherwise), so the caller can log it. Never logs.
     */
    public record TravelCheck(Verdict guard, BreakPolicy.Verdict travel, boolean allowed) {}

    public TravelCheck checkTravelBreak(String dim, int x, int y, int z, BlockInfo block, BreakPolicy.Snapshot s,
                                        BreakPolicy.BlockFacts facts, BreakPolicy.Where me) {
        return checkPolicy(dim, x, y, z, "break", block, s, facts, me, BreakPolicy.Intent.TRAVEL);
    }

    /**
     * The owner's place rule (aa372): a real placement click while a job walks under the policy ({@code intent} TRAVEL,
     * MINE, DIG_OUT or BUILD). The floor and the box rules as before; where only a box rule (no place lease here, outside
     * every area) would refuse and the policy allows the placement (a refill of a cell the bot dug itself anywhere, a new
     * block where the rule set is lenient), the click goes through. {@code facts} carries {@code dugByMe}.
     */
    public TravelCheck checkPolicyPlace(String dim, int x, int y, int z, BlockInfo block, BreakPolicy.Snapshot s,
                                        BreakPolicy.BlockFacts facts, BreakPolicy.Where me, BreakPolicy.Intent intent) {
        return checkPolicy(dim, x, y, z, "place", block, s, facts, me, intent);
    }

    private TravelCheck checkPolicy(String dim, int x, int y, int z, String action, BlockInfo block, BreakPolicy.Snapshot s,
                                    BreakPolicy.BlockFacts facts, BreakPolicy.Where me, BreakPolicy.Intent intent) {
        Verdict v = checkUnlogged(dim, x, y, z, action, block);
        if (v.allowed() && !v.wouldVeto()) return new TravelCheck(v, null, true);
        if (v.floor()) return new TravelCheck(v, null, false);
        BreakPolicy.Action a = "place".equals(action) ? BreakPolicy.Action.PLACE : BreakPolicy.Action.BREAK;
        BreakPolicy.Verdict pv = BreakPolicy.judge(s, new BreakPolicy.Ask(a, intent, dim, x, y, z, facts, me));
        return new TravelCheck(v, pv, pv.allowed() || v.allowed());
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

    /**
     * 0.36.8 (owner 2026-10-18): the liquid that forbids breaking a block, given {@link #liquidNextTo}'s answer. While a
     * mine job runs ({@code waterOk}) water no longer forbids it; lava always does. Pure.
     */
    public static String breakLiquid(String liquid, boolean waterOk) {
        return waterOk && "water".equals(liquid) ? null : liquid;
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
        JsonObject n = new JsonObject();          // 0.21.2: the near-me zone (on, radius, the box now or none); AREAS: a fact only
        n.addProperty("on", near.on());
        n.addProperty("r", near.radius());
        Box nb = near.box();
        if (nb != null) n.add("box", nb.toJson());
        n.addProperty("errors", near.errors());
        o.add("near", n);
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
