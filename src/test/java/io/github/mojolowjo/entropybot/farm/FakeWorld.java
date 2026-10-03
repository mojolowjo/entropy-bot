package io.github.mojolowjo.entropybot.farm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A small world for the farm tests: blocks by position (stone at y 52 and below, air above), crop ages, the bot, its bag and the item drops. */
final class FakeWorld implements FarmWorld {
    final Map<String, String> blocks = new HashMap<>();
    final Map<String, int[]> ages = new HashMap<>();          // "x y z" -> {age, max}
    double x, y, z;
    String dim = "minecraft:overworld";
    final String[] ids = new String[36];
    final int[] counts = new int[36];
    int selected;
    boolean pathing;
    final List<LiveDrop> live = new ArrayList<>();
    int nextDrop = 1;

    static final class LiveDrop {
        final String key, item;
        final double x, y, z;
        final int count;
        boolean alive = true;

        LiveDrop(String key, String item, double x, double y, double z, int count) {
            this.key = key;
            this.item = item;
            this.x = x;
            this.y = y;
            this.z = z;
            this.count = count;
        }
    }

    FakeWorld() {
        for (int i = 0; i < 36; i++) ids[i] = "";
    }

    static String k(int x, int y, int z) { return x + " " + y + " " + z; }

    void set(int x, int y, int z, String id) { blocks.put(k(x, y, z), id); }

    void crop(int x, int y, int z, int age) {
        set(x, y - 1, z, "minecraft:farmland");
        set(x, y, z, "mysticalagriculture:inferium_crop");
        ages.put(k(x, y, z), new int[]{age, 7});
    }

    void at(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    void give(int slot, String id, int n) {
        ids[slot] = n > 0 ? id : "";
        counts[slot] = n;
    }

    /** Adds n of id to part stacks first, then empty slots (64 a stack); what doesn't fit is returned. */
    int add(String id, int n) {
        for (int i = 0; i < 36 && n > 0; i++) {
            if (ids[i].equals(id) && counts[i] < 64) {
                int m = Math.min(n, 64 - counts[i]);
                counts[i] += m;
                n -= m;
            }
        }
        for (int i = 0; i < 36 && n > 0; i++) {
            if (ids[i].isEmpty()) {
                int m = Math.min(n, 64);
                ids[i] = id;
                counts[i] = m;
                n -= m;
            }
        }
        return n;
    }

    /** Removes n of id; returns how many it removed. */
    int remove(String id, int n) {
        int done = 0;
        for (int i = 35; i >= 0 && done < n; i--) {
            if (!ids[i].equals(id)) continue;
            int m = Math.min(n - done, counts[i]);
            counts[i] -= m;
            done += m;
            if (counts[i] == 0) ids[i] = "";
        }
        return done;
    }

    int count(String id) { return inventory().getOrDefault(id, 0); }

    LiveDrop drop(String item, double x, double y, double z, int count) {
        LiveDrop d = new LiveDrop(String.valueOf(nextDrop++), item, x, y, z, count);
        live.add(d);
        return d;
    }

    String block(int x, int y, int z) {
        String b = blocks.get(k(x, y, z));
        if (b != null) return b;
        return y <= 52 ? "minecraft:stone" : "minecraft:air";
    }

    boolean liquid(int x, int y, int z) { return block(x, y, z).equals("minecraft:water"); }

    boolean passable(int x, int y, int z) {
        String b = block(x, y, z);
        return b.equals("minecraft:air") || b.endsWith("_crop") || b.equals("minecraft:water");
    }

    @Override public boolean isAir(int x, int y, int z) { return block(x, y, z).equals("minecraft:air"); }

    @Override public String blockId(int x, int y, int z) { return block(x, y, z); }

    @Override public Age age(int x, int y, int z) {
        int[] a = ages.get(k(x, y, z));
        return a == null ? null : new Age(a[0], a[1]);
    }

    @Override public boolean standable(int x, int y, int z) {
        if (!passable(x, y, z) || liquid(x, y, z)) return false;
        if (!passable(x, y + 1, z) || liquid(x, y + 1, z)) return false;
        return !passable(x, y - 1, z) && !liquid(x, y - 1, z);
    }

    @Override public double[] pos() { return new double[]{x, y, z}; }

    @Override public double[] eye() { return new double[]{x, y + 1.62, z}; }

    @Override public String dim() { return dim; }

    @Override public List<Drop> drops() {
        List<Drop> out = new ArrayList<>();
        for (LiveDrop d : live) if (d.alive) out.add(new Drop(d.key, d.item, d.x, d.y, d.z));
        return out;
    }

    @Override public boolean dropAlive(String key) {
        for (LiveDrop d : live) if (d.key.equals(key)) return d.alive;
        return false;
    }

    @Override public boolean roomFor(Drop d) {
        for (int i = 0; i < 36; i++) if (ids[i].isEmpty() || (ids[i].equals(d.item()) && counts[i] < 64)) return true;
        return false;
    }

    @Override public List<String> slots() { return List.of(ids); }

    @Override public int selected() { return selected; }

    @Override public Map<String, Integer> inventory() {
        Map<String, Integer> out = new TreeMap<>();
        for (int i = 0; i < 36; i++) if (!ids[i].isEmpty()) out.merge(ids[i], counts[i], Integer::sum);
        return out;
    }

    @Override public boolean pathing() { return pathing; }

    List<Compact.Slot> bag() {
        List<Compact.Slot> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) out.add(new Compact.Slot(ids[i], counts[i], 64));
        return out;
    }
}
