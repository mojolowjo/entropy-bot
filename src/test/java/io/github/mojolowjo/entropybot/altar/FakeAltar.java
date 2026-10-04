package io.github.mojolowjo.entropybot.altar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Package E: Mystical Agriculture's infusion altar as item 14 found it, for the tests. A pedestal holds one item: a
 * right-click takes it back if it has one, else puts one of the held item on it. The altar: a click takes the output
 * if there is one, else the input, else puts one of the held item on it. The button starts it when what is on it
 * matches the recipe (the pedestals in any order); the craft takes {@link #craftTicks}. Clicks land {@link #lag} ticks
 * later, as the server's answer does.
 */
final class FakeAltar implements AltarWorld {
    static final String MA = "mysticalagriculture:";
    static final String BASE = MA + "prosperity_seed_base", SILICON = "refinedstorage:silicon", PRUD = MA + "prudentium_essence",
            SEEDS = MA + "silicon_seeds", PEDESTAL = MA + "infusion_pedestal", ALTAR_ID = MA + "infusion_altar";
    /** Item 14's altar, pedestals and button. */
    static final int[] ALTAR = {-23, 53, 156}, BUTTON = {-23, 53, 157};
    static final int[][] PEDS = {{-26, 53, 156}, {-25, 53, 154}, {-25, 53, 158}, {-23, 53, 153}, {-23, 53, 159}, {-21, 53, 154}, {-21, 53, 158}, {-20, 53, 156}};

    final Map<String, String> blocks = new HashMap<>();
    final Map<String, Map<Integer, Stack>> held = new HashMap<>();
    final Map<String, Integer> bag = new LinkedHashMap<>();
    int free = 20;
    String center = BASE, output = SEEDS;
    List<String> peds = List.of(SILICON, SILICON, SILICON, SILICON, PRUD, PRUD, PRUD, PRUD);
    int craftTicks = 60, lag = 2;
    long now;
    boolean active;
    long craftDone = -1;
    final List<String> clicks = new ArrayList<>();
    int presses;
    boolean menuOpen;
    private final List<Object[]> pending = new ArrayList<>();

    static String k(int[] p) { return p[0] + " " + p[1] + " " + p[2]; }

    static FakeAltar item14() {
        FakeAltar a = new FakeAltar();
        a.blocks.put(k(ALTAR), ALTAR_ID);
        a.held.put(k(ALTAR), new HashMap<>());
        for (int[] p : PEDS) {
            a.blocks.put(k(p), PEDESTAL);
            a.held.put(k(p), new HashMap<>());
        }
        a.blocks.put(k(BUTTON), "minecraft:oak_button");
        return a;
    }

    FakeAltar give(String id, int n) {
        bag.merge(id, n, Integer::sum);
        return this;
    }

    /** Puts an item straight on a pedestal (slot 0) or the altar (slot 0 input, 1 output), as someone else would. */
    FakeAltar put(int[] pos, int slot, String id) {
        held.get(k(pos)).put(slot, new Stack(id, 1));
        return this;
    }

    String on(int[] pos, int slot) {
        Stack s = held.get(k(pos)).get(slot);
        return s == null ? null : s.id();
    }

    @Override public String block(int[] pos) { return blocks.getOrDefault(k(pos), "minecraft:air"); }

    @Override public Map<Integer, Stack> items(int[] pos) {
        Map<Integer, Stack> m = held.get(k(pos));
        return m == null ? null : new HashMap<>(m);
    }

    @Override public Boolean active(int[] altar) { return active; }

    @Override public int bag(String id) { return bag.getOrDefault(id, 0); }

    @Override public int freeSlots() { return free; }

    @Override public boolean ready() {
        if (!menuOpen) return true;
        menuOpen = false;
        return false;
    }

    /** Where the bot stands (feet, block centre); a click beyond the reach (eye to the block's box) is refused, as in game. */
    double[] at = {-21.5, 53, 157.5};

    @Override public String use(int[] pos, String item) {
        double d = AltarPlan.toBox(at[0], at[1] + AltarPlan.EYE, at[2], pos);
        if (d > AltarPlan.REACH) return "it is out of my reach (" + Math.round(d * 10) / 10.0 + " blocks)";
        clicks.add(k(pos) + " " + (item == null ? "-" : item.substring(item.indexOf(':') + 1)));
        if (item != null && bag(item) < 1) return "couldn't get " + item + " into my hand";
        pending.add(new Object[]{now + lag, pos, item});
        return null;
    }

    private void take(Map<Integer, Stack> m, int slot) {
        Stack s = m.remove(slot);
        if (s != null) bag.merge(s.id(), s.count(), Integer::sum);
    }

    private void apply(int[] pos, String item) {
        String b = block(pos);
        Map<Integer, Stack> m = held.get(k(pos));
        if (b.equals(PEDESTAL)) {
            if (m.get(0) != null) take(m, 0);
            else if (item != null && bag(item) > 0) {
                bag.merge(item, -1, Integer::sum);
                m.put(0, new Stack(item, 1));
            }
        } else if (b.equals(ALTAR_ID)) {
            if (m.get(1) != null) take(m, 1);
            else if (m.get(0) != null) take(m, 0);
            else if (item != null && bag(item) > 0) {
                bag.merge(item, -1, Integer::sum);
                m.put(0, new Stack(item, 1));
            }
        } else if (b.contains("button")) {
            presses++;
            if (!active && matches()) {
                active = true;
                craftDone = now + craftTicks;
            }
        }
    }

    boolean matches() {
        Stack in = held.get(k(ALTAR)).get(0);
        if (in == null || !in.id().equals(center)) return false;
        List<String> got = new ArrayList<>();
        for (int[] p : PEDS) {
            Stack s = held.get(k(p)).get(0);
            if (s != null) got.add(s.id());
        }
        List<String> want = new ArrayList<>(peds);
        Collections.sort(got);
        Collections.sort(want);
        return got.equals(want);
    }

    /** The server's tick: clicks land, a craft finishes. */
    void tick(long t) {
        now = t;
        for (int i = 0; i < pending.size(); i++) {
            Object[] e = pending.get(i);
            if ((Long) e[0] <= t) {
                pending.remove(i--);
                apply((int[]) e[1], (String) e[2]);
            }
        }
        if (active && t >= craftDone) {
            active = false;
            Map<Integer, Stack> alt = held.get(k(ALTAR));
            alt.remove(0);
            for (int[] p : PEDS) held.get(k(p)).remove(0);
            alt.put(1, new Stack(output, 1));
        }
    }

    /** How often a block was clicked with an item in hand (placements). */
    int placements(int[] pos) {
        int n = 0;
        for (String c : clicks) if (c.startsWith(k(pos) + " ") && !c.endsWith(" -")) n++;
        return n;
    }
}
