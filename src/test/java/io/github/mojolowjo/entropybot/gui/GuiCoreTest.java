package io.github.mojolowjo.entropybot.gui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GuiCoreTest {
    /** A container menu with vanilla's click rules: container slots first, then 27 inventory and 9 hotbar slots. */
    static final class Fake implements GuiMenu {
        static final class S {
            String id;
            int n;
            final String kind;          // plain, fuel, output, other (container) or mine
            S(String kind) { this.kind = kind; }
        }

        final List<S> slots = new ArrayList<>();
        String cid;
        int cn;
        int clicks;

        Fake(String... kinds) {
            for (String k : kinds) slots.add(new S(k));
            for (int i = 0; i < 36; i++) slots.add(new S("mine"));
        }

        static Fake chest(int n) {
            String[] k = new String[n];
            java.util.Arrays.fill(k, "plain");
            return new Fake(k);
        }

        Fake set(int i, String id, int n) {
            slots.get(i).id = id;
            slots.get(i).n = n;
            return this;
        }

        int firstMine() { return slots.size() - 36; }

        static int max(String id) { return id != null && id.endsWith("ender_pearl") ? 16 : 64; }

        boolean accepts(int i, String id) {
            String k = slots.get(i).kind;
            return k.equals("mine") || k.equals("plain") || k.equals("other") || (k.equals("fuel") && id.endsWith("coal"));
        }

        @Override public int size() { return slots.size(); }
        @Override public String id(int i) { return slots.get(i).n > 0 ? slots.get(i).id : null; }
        @Override public int count(int i) { return slots.get(i).n; }
        @Override public int stackMax(int i) { return id(i) == null ? 0 : max(id(i)); }
        @Override public boolean mine(int i) { return slots.get(i).kind.equals("mine"); }
        @Override public boolean armor(int i) { return false; }
        @Override public String probe(int i) { return slots.get(i).kind; }
        @Override public boolean mayPlace(int to, int from) { return id(from) != null && accepts(to, id(from)); }
        @Override public int slotLimit(int to, int from) { return max(id(from)); }
        @Override public boolean same(int a, int b) { return id(a) != null && id(a).equals(id(b)); }
        @Override public Boolean cursorHas() { return cn > 0; }
        @Override public boolean cursorSame(int i) { return cn > 0 && cid.equals(id(i)); }

        @Override public void click(int i, int button, String type) {
            clicks++;
            S s = slots.get(i);
            switch (type) {
                case "THROW" -> {
                    if (s.n == 0) return;
                    s.n -= button == 1 ? s.n : 1;
                }
                case "QUICK_MOVE" -> {
                    if (s.n == 0) return;
                    boolean fromMine = s.kind.equals("mine");
                    for (int pass = 0; pass < 2 && s.n > 0; pass++) {
                        for (int j = 0; j < slots.size() && s.n > 0; j++) {
                            S d = slots.get(j);
                            if (j == i || (fromMine ? !d.kind.equals("plain") : !d.kind.equals("mine"))) continue;
                            if (pass == 0 && (d.n == 0 || !s.id.equals(d.id))) continue;
                            if (pass == 1 && d.n > 0) continue;
                            int mv = Math.min(s.n, max(s.id) - d.n);
                            if (mv <= 0) continue;
                            d.id = s.id;
                            d.n += mv;
                            s.n -= mv;
                        }
                    }
                }
                case "PICKUP" -> {
                    if (cn == 0) {
                        if (s.n == 0) return;
                        int take = button == 0 ? s.n : (s.n + 1) / 2;
                        cid = s.id;
                        cn = take;
                        s.n -= take;
                        return;
                    }
                    if (!accepts(i, cid)) return;
                    if (s.n == 0 || s.id.equals(cid)) {
                        int room = max(cid) - s.n, put = Math.min(button == 0 ? cn : 1, room);
                        if (put <= 0) return;
                        s.id = cid;
                        s.n += put;
                        cn -= put;
                    } else if (button == 0) {                // swap
                        String ti = s.id;
                        int tn = s.n;
                        s.id = cid;
                        s.n = cn;
                        cid = ti;
                        cn = tn;
                    }
                }
                default -> throw new IllegalArgumentException(type);
            }
        }

        int total(String id, boolean mine) {
            int t = 0;
            for (S s : slots) if (s.n > 0 && s.id.equals(id) && s.kind.equals("mine") == mine) t += s.n;
            return t;
        }
    }

    @Test
    void rolesTellStorageMachinesAndUpgradeSlotsApart() {
        Map<String, List<Integer>> chest = GuiCore.roles(Fake.chest(27));
        assertEquals(27, chest.get("storage").size());
        assertTrue(chest.get("input").isEmpty());
        Map<String, List<Integer>> furnace = GuiCore.roles(new Fake("plain", "fuel", "output"));
        assertEquals(List.of(0), furnace.get("input"));
        assertEquals(List.of(1), furnace.get("fuel"));
        assertEquals(List.of(2), furnace.get("output"));
        String[] k = new String[30];
        java.util.Arrays.fill(k, "plain");
        k[27] = k[28] = k[29] = "other";
        Map<String, List<Integer>> sophisticated = GuiCore.roles(new Fake(k));
        assertEquals(27, sophisticated.get("storage").size());
        assertEquals(List.of(27, 28, 29), sophisticated.get("other"));
    }

    @Test
    void takeMovesExactlyWhatWasAsked() {
        Fake m = Fake.chest(27).set(0, "minecraft:cobblestone", 64).set(1, "minecraft:cobblestone", 20);
        String r = GuiCore.transferVerb(m, "cobblestone 30", false, null);
        assertEquals("ok: took 30 cobblestone (54 left in the container)", r);
        assertEquals(30, m.total("minecraft:cobblestone", true));
        assertEquals(54, m.total("minecraft:cobblestone", false));
        assertFalse(m.cursorHas());
        // 70: one whole stack by shift-click plus 6 from the other by the cursor
        Fake m2 = Fake.chest(27).set(0, "minecraft:cobblestone", 64).set(1, "minecraft:cobblestone", 20);
        assertEquals("ok: took 70 cobblestone (14 left in the container)", GuiCore.transferVerb(m2, "cobblestone 70", false, null));
        assertEquals(70, m2.total("minecraft:cobblestone", true));
    }

    @Test
    void takeSaysWhenTheContainerHadLessOrNone() {
        Fake m = Fake.chest(27).set(0, "minecraft:charcoal", 150 - 86).set(1, "minecraft:charcoal", 64).set(2, "minecraft:charcoal", 22);
        assertEquals("only took 150 of 200 charcoal - the container had no more", GuiCore.transferVerb(m, "charcoal 200", false, null));
        Fake e = Fake.chest(27).set(0, "minecraft:dirt", 5);
        assertEquals("error: no stone in this container (it holds: 5 dirt)", GuiCore.transferVerb(e, "stone 3", false, null));
        assertEquals("error: the container is empty", GuiCore.transferVerb(Fake.chest(27), "all", false, null));
    }

    @Test
    void takeWithAFullInventoryTakesWhatFits() {
        Fake m = Fake.chest(27).set(0, "minecraft:charcoal", 64).set(1, "minecraft:charcoal", 64).set(2, "minecraft:charcoal", 64);
        int first = m.firstMine();
        for (int i = 0; i < 36; i++) m.set(first + i, "minecraft:dirt", 64);
        m.set(first, "minecraft:charcoal", 24);         // room for 40 charcoal in the inventory
        String r = GuiCore.transferVerb(m, "charcoal 200", false, null);
        assertEquals("error: my inventory is full - took 40 of 200 charcoal", r);
        assertEquals(64, m.total("minecraft:charcoal", true));
    }

    @Test
    void putIsExactAndSaysWhenTheChestIsFull() {
        Fake m = Fake.chest(27);
        int first = m.firstMine();
        m.set(first, "minecraft:cobblestone", 21);
        assertEquals("ok: put 15 cobblestone (the container now has 15)", GuiCore.transferVerb(m, "cobblestone 15", true, null));
        assertEquals(6, m.total("minecraft:cobblestone", true));
        Fake full = Fake.chest(9);
        for (int i = 0; i < 9; i++) full.set(i, "minecraft:stone", 64);
        full.set(full.firstMine(), "minecraft:cobblestone", 10);
        assertEquals("error: the container is full - I could not put any cobblestone", GuiCore.transferVerb(full, "cobblestone", true, null));
        assertEquals("error: I carry no diamond", GuiCore.transferVerb(Fake.chest(27), "diamond", true, null));
    }

    @Test
    void putNeverTouchesUpgradeSlotsOrShiftClicksIntoSuchAMenu() {
        String[] k = new String[12];
        java.util.Arrays.fill(k, "plain");
        k[9] = k[10] = k[11] = "other";
        Fake m = new Fake(k);
        m.set(m.firstMine(), "minecraft:cobblestone", 64).set(m.firstMine() + 1, "minecraft:cobblestone", 64);
        String r = GuiCore.transferVerb(m, "all", true, null);
        assertTrue(r.startsWith("ok: put 128 cobblestone"), r);
        for (int i = 9; i < 12; i++) assertNull(m.id(i), "upgrade slot " + i + " stays empty");
    }

    @Test
    void namesWorkInAnyModNamespace() {
        Fake m = Fake.chest(27);
        m.set(m.firstMine(), "leafscopperbackport:copper_pickaxe", 1);
        assertEquals("ok: put 1 leafscopperbackport:copper_pickaxe (the container now has 1)", GuiCore.transferVerb(m, "copper_pickaxe", true, null));
        assertEquals("ok: took 1 leafscopperbackport:copper_pickaxe (nothing left in the container)", GuiCore.transferVerb(m, "copper_pickaxe", false, null));
        assertEquals("ok: dropped 1 copper_pickaxe", GuiCore.drop(m, "copper_pickaxe"));
        assertEquals("minecraft:torch", GuiCore.resolve("torches", List.of("minecraft:torch")));
        assertEquals("minecraft:stone", GuiCore.resolve("stone", List.of("minecraft:dirt")));
    }

    @Test
    void dropThrowsExactCounts() {
        Fake m = Fake.chest(0);
        m.set(m.firstMine(), "minecraft:dirt", 10).set(m.firstMine() + 1, "minecraft:dirt", 64);
        assertEquals("ok: dropped 12 dirt", GuiCore.drop(m, "dirt 12"));
        assertEquals(62, m.total("minecraft:dirt", true));
        assertEquals("error: no stone in inventory", GuiCore.drop(m, "stone"));
    }

    @Test
    void wrongScreensAreNamed() {
        assertEquals("that is a crafting table, not a chest", GuiCore.wrongScreen("CraftingScreen CraftingMenu"));
        assertEquals("that is an anvil, not a chest", GuiCore.wrongScreen("AnvilScreen AnvilMenu"));
        assertTrue(GuiCore.wrongScreen("CorpseScreen CorpseMenu").startsWith("that is a corpse"));
        assertNull(GuiCore.wrongScreen("ContainerScreen ChestMenu"));
    }

    @Test
    void topAndDiff() {
        assertEquals("96 rotten_flesh, 40 bone, +1 more", GuiCore.top(Map.of("minecraft:bone", 40, "minecraft:rotten_flesh", 96, "minecraft:string", 3), 2));
        List<Map<String, Integer>> d = GuiCore.diff(Map.of("a", 5, "b", 2), Map.of("a", 3, "c", 4));
        assertEquals(Map.of("c", 4), d.get(0));
        assertEquals(Map.of("a", 2, "b", 2), d.get(1));
    }
}
