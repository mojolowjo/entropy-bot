package io.github.mojolowjo.entropybot.clear;

import io.github.mojolowjo.entropybot.guard.Box;
import io.github.mojolowjo.entropybot.guard.GuardCore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * B7d D1: the guard leases one clear (or placement, or build) holds: the bridge's takeLease / leaseBox / leaseSlices /
 * placeLease / releaseLeases over the guard's {@link GuardCore}. A box bigger than one lease is cut into slices; a force
 * lease (dig ... force) is one box of at most 64. In strict mode a refusal is the error the job starts (or stops) with;
 * in log mode it is logged once and the job goes on (the guard only records then). Every lease is released however the
 * job ends ({@link #releaseAll}); one that died (no heartbeat for 5 s during a long trip, a bridge reload, a new policy)
 * is taken again ({@link #ensure}). Game-free: JUnit drives it over a real GuardCore.
 */
public final class LeaseSet {
    private final GuardCore core;
    private final String owner, dim;
    private final Consumer<String> log;
    public final List<String> ids = new ArrayList<>();
    private final Set<String> placeKeys = new LinkedHashSet<>();
    /** What {@link #ensure} takes again: {task, box, place, force} of each take. */
    private final List<Object[]> taken = new ArrayList<>();
    public boolean warned;

    public LeaseSet(GuardCore core, String owner, String dim, Consumer<String> log) {
        this.core = core;
        this.owner = owner;
        this.dim = dim;
        this.log = log == null ? s -> {} : log;
    }

    private Box box(String task, ClearBox b) {
        return new Box(task, dim, b.x1(), b.y1(), b.z1(), b.x2(), b.y2(), b.z2());
    }

    /** takeLease: null when fine (or refused in log mode), else "error: the guard refused: ... - area add ...". */
    public String take(String task, ClearBox b, boolean place, boolean force) {
        String r = takeQuiet(task, b, place, force);
        if (r == null) taken.add(new Object[]{task, b, place, force});
        return r;
    }

    private String takeQuiet(String task, ClearBox b, boolean place, boolean force) {
        List<ClearBox> slices = force ? List.of(b) : PlaceRules.leaseSlices(b, PlaceRules.LEASE_MAX);
        List<String> got = new ArrayList<>();
        for (ClearBox sl : slices) {
            String r = core.lease(owner, task, box(task, sl), place, force);
            if (r.startsWith("error")) {
                for (String id : got) core.release(id);
                String why = r.replaceFirst("^error: ", "");
                if (core.mode() == GuardCore.Mode.STRICT) return "error: the guard refused: " + why + " - " + AREA_HINT;
                if (!warned) log.accept("lease refused (log mode): " + why + " (" + task + ")");
                warned = true;
                return null;
            }
            got.add(r);
            log.accept("lease " + r + " for " + task + ": " + sl + (place ? " (place)" : "") + (force ? " (force)" : ""));
        }
        ids.addAll(got);
        return null;
    }

    public static final String AREA_HINT = "area add <name> here <r>";

    /** placeLease for a block item: the cell alone (T1), once per cell while this set lives. */
    public String placeLease(int x, int y, int z, String task) {
        return placeLease(PlaceRules.placeLeaseBox(x, y, z), task);
    }

    /** placeLease over the box {@link PlaceRules#placeLeaseBox} gave (the cell, or the cell and the clicked block), once per box. */
    public String placeLease(ClearBox b, String task) {
        String key = b.toString();
        if (placeKeys.contains(key)) return null;
        String r = takeQuiet(task, b, true, false);
        if (r == null) placeKeys.add(key);
        return r;
    }

    /** Every lease still held (true with none taken, or refused in log mode). */
    public boolean alive() {
        return core.leases().keySet().containsAll(ids);
    }

    /** Renews them (the guard drops a lease without a heartbeat for 100 ticks). */
    public void beat() {
        core.heartbeat(owner);
    }

    /** One died: all of them again (the per-cell place leases come back with the next placement). Null or the error. */
    public String ensure() {
        if (alive()) return null;
        releaseAll();
        List<Object[]> again = new ArrayList<>(taken);
        taken.clear();
        for (Object[] t : again) {
            String r = take((String) t[0], (ClearBox) t[1], (Boolean) t[2], (Boolean) t[3]);
            if (r != null) {
                releaseAll();
                return r;
            }
        }
        return null;
    }

    /** Every lease of this set ends (others' stay). */
    public void releaseAll() {
        for (String id : ids) core.release(id);
        ids.clear();
        placeKeys.clear();
    }
}
