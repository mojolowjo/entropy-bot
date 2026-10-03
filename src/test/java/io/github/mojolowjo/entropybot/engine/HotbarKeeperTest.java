package io.github.mojolowjo.entropybot.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** Package B round 2: when the hotbar keeper swaps (idle, at a job's start, a refill mid-job) and when it never does. */
class HotbarKeeperTest {
    static final Map<Integer, String> LAYOUT = new TreeMap<>(Map.of(1, "pickaxe", 2, "sword", 3, "food", 4, "torch"));

    /** A bag the test changes and the keeper looks at, plus the flags of one look. */
    static final class Bot {
        final TreeMap<Integer, HotbarRules.Item> slots = new TreeMap<>();
        int selected;
        boolean busy, reflex, menu, using, carried, swinging, destroying;
        Object job;
        final HotbarKeeper keeper = new HotbarKeeper();
        final List<String> moves = new ArrayList<>();

        Bot put(int slot, String id, int n) {
            slots.put(slot, new HotbarRules.Item(slot, id, n, id.endsWith("bread") ? 55 : -1));
            return this;
        }

        String at(int slot) {
            HotbarRules.Item it = slots.get(slot);
            return it == null ? "-" : it.id().replace("minecraft:", "");
        }

        /** One look at tick t; a move is applied like the SWAP click (and the selection) would. */
        HotbarKeeper.Move look(long t) {
            HotbarKeeper.Move m = keeper.decide(LAYOUT, new HotbarKeeper.Look(t, busy || reflex, reflex, job, menu, using, carried, swinging, destroying,
                    selected, new ArrayList<>(slots.values())));
            if (m != null) {
                HotbarRules.Item a = slots.remove(m.swap().from()), b = slots.remove(m.swap().to());
                if (a != null) slots.put(m.swap().to(), new HotbarRules.Item(m.swap().to(), a.id(), a.count(), a.food()));
                if (b != null) slots.put(m.swap().from(), new HotbarRules.Item(m.swap().from(), b.id(), b.count(), b.food()));
                selected = m.select();
                moves.add(t + ":" + m.why());
            }
            return m;
        }

        /** Looks every tick from t0 to t1 (inclusive); the number of swaps made. */
        int run(long t0, long t1) {
            int n = 0;
            for (long t = t0; t <= t1; t++) if (look(t) != null) n++;
            return n;
        }
    }

    static Bot kit() {
        return new Bot().put(0, "minecraft:dirt", 64).put(1, "minecraft:cobblestone", 30).put(12, "minecraft:stone_pickaxe", 1)
                .put(13, "minecraft:iron_sword", 1).put(14, "minecraft:bread", 8).put(15, "minecraft:torch", 32);
    }

    @Test
    void idleKeepsTheRoundOneRules() {
        Bot b = kit();
        assertEquals(0, b.run(0, HotbarKeeper.IDLE_TICKS - 1), "nothing before 2 s of quiet");
        assertEquals(4, b.run(HotbarKeeper.IDLE_TICKS, 200), "then the four laid-out slots, one swap at a time");
        assertEquals(List.of("40:idle", "50:idle", "60:idle", "70:idle"), b.moves, "at least 10 ticks apart");
        assertEquals("stone_pickaxe iron_sword bread torch", b.at(0) + " " + b.at(1) + " " + b.at(2) + " " + b.at(3));
        // a swing while idle restarts the quiet
        Bot c = kit();
        c.run(0, 30);
        c.swinging = true;
        c.look(31);
        c.swinging = false;
        assertEquals(0, c.run(32, 31 + HotbarKeeper.IDLE_TICKS - 1));
        assertNotNull(c.look(31 + HotbarKeeper.IDLE_TICKS));
    }

    @Test
    void aSwapAtTheStartOfAJob() {
        Bot b = kit();
        b.busy = true;
        b.job = "bridge:mine#7";
        assertEquals(4, b.run(0, 60), "the whole layout in the job's first seconds, though the job is busy");
        assertEquals(List.of("0:job start", "10:job start", "20:job start", "30:job start"), b.moves);
        assertEquals("stone_pickaxe", b.at(0), "the mine starts with its pickaxe in the pickaxe slot");
        // later in the same job a wrong slot is left alone (no window, nothing emptied)
        b.slots.remove(1);
        b.put(1, "minecraft:gravel", 4).put(20, "minecraft:stone_sword", 1);
        assertEquals(0, b.run(61, 400), "mid-job, only an emptied slot is refilled");
        // the next job opens a new window
        b.job = "bridge:mine#8";
        b.slots.remove(13);
        b.run(401, 420);
        assertEquals("stone_sword", b.at(1));
        // a walk or a wait (job null, busy false) is idle: no window, the idle rule
        Bot w = kit();
        w.job = null;
        assertEquals(0, w.run(0, 39));
    }

    @Test
    void theStartWindowWaitsForAMenuAndEnds() {
        Bot b = kit();
        b.busy = true;
        b.job = "mod-seq";
        b.menu = true;                      // a restock opens a chest first
        assertEquals(0, b.run(0, 100), "never with a menu open");
        b.menu = false;
        assertEquals(4, b.run(101, 199), "the rest of the window once it closes");
        Bot late = kit();
        late.busy = true;
        late.job = "mod-seq";
        late.menu = true;
        late.run(0, HotbarKeeper.START_TICKS);
        late.menu = false;
        assertEquals(0, late.run(HotbarKeeper.START_TICKS + 1, 600), "after 10 s the window is over: the rest waits for idle");
    }

    @Test
    void aRefillWhenThePickaxeBreaksMidJob() {
        Bot b = kit();
        b.busy = true;
        b.job = "bridge:mine#9";
        b.run(0, 300);                      // the window applied the layout and closed
        b.put(16, "minecraft:stone_pickaxe", 1);
        b.selected = 0;
        b.swinging = true;
        b.destroying = true;
        assertEquals(0, b.run(301, 320), "mining with the pickaxe: nothing to do");
        b.slots.remove(0);                  // it broke in the hand mid-break
        HotbarKeeper.Move m = b.look(321);
        assertNotNull(m, "a broken tool is refilled even mid-break: the break lost its tool anyway");
        assertEquals("refill", m.why());
        assertEquals(new HotbarRules.Swap(16, 0), m.swap());
        assertEquals(0, b.selected, "the new pickaxe is in the hand");
        assertEquals("stone_pickaxe", b.at(0));
        assertNull(b.look(322));
        assertTrue(b.keeper.refills().isEmpty());
        // no spare pickaxe: the slot waits; one crafted mid-job is brought when it shows up
        b.slots.remove(0);
        assertEquals(0, b.run(323, 340));
        assertEquals(java.util.Set.of(1), b.keeper.refills());
        b.put(30, "minecraft:iron_pickaxe", 1);
        b.destroying = false;
        b.swinging = false;
        assertNotNull(b.look(341));
        assertEquals("iron_pickaxe", b.at(0));
    }

    @Test
    void neverMidSwingMidBreakOrWithAMenu() {
        Bot b = kit();
        b.busy = true;
        b.job = "bridge:stripmine#3";
        b.run(0, 300);
        b.put(17, "minecraft:torch", 16);
        b.slots.remove(3);                  // the last torch was placed
        b.selected = 0;
        b.swinging = true;
        assertEquals(0, b.run(301, 340), "not mid-swing");
        b.swinging = false;
        b.destroying = true;
        assertEquals(0, b.run(341, 380), "not while a block is being broken");
        b.destroying = false;
        b.menu = true;
        assertEquals(0, b.run(381, 400), "not with a menu open");
        b.menu = false;
        b.carried = true;
        assertEquals(0, b.run(401, 420), "not with an item on the cursor");
        b.carried = false;
        b.using = true;
        assertEquals(0, b.run(421, 440), "not while eating");
        b.using = false;
        b.reflex = true;
        assertEquals(0, b.run(441, 460), "not during a reflex");
        b.reflex = false;
        HotbarKeeper.Move m = b.look(461);
        assertNotNull(m);
        assertEquals("refill", m.why());
        assertEquals("torch", b.at(3));
        assertEquals(0, b.selected, "the hand is left alone");

        // a broken tool with a menu open still waits
        Bot c = kit();
        c.busy = true;
        c.job = "j";
        c.run(0, 300);
        c.put(16, "minecraft:stone_pickaxe", 1);
        c.slots.remove(0);
        c.menu = true;
        assertEquals(0, c.run(301, 330));
        c.menu = false;
        assertNotNull(c.look(331));

        // the spare pickaxe is the one in the hand (Baritone switched to it): not while it is breaking; then the
        // selection moves along with it
        Bot d = kit();
        d.busy = true;
        d.job = "j";
        d.run(0, 300);
        d.put(6, "minecraft:stone_pickaxe", 1);
        d.selected = 6;
        d.slots.remove(0);
        d.destroying = true;
        assertEquals(0, d.run(301, 330), "the held pickaxe is not taken mid-break");
        d.destroying = false;
        HotbarKeeper.Move m2 = d.look(331);
        assertEquals(new HotbarRules.Swap(6, 0), m2.swap());
        assertEquals(0, d.selected, "the bot still holds the pickaxe");
    }

    @Test
    void foodEatenIsRefilledAndToolSlotsAreKnown() {
        Bot b = kit();
        b.busy = true;
        b.job = "j";
        b.run(0, 300);
        b.put(18, "minecraft:bread", 5);
        b.slots.remove(2);
        assertNotNull(b.look(301));
        assertEquals("bread", b.at(2));
        assertTrue(HotbarRules.isTool("pickaxe"));
        assertTrue(HotbarRules.isTool("minecraft:iron_pickaxe"));
        assertFalse(HotbarRules.isTool("torch"));
        assertFalse(HotbarRules.isTool("minecraft:cobblestone"));
        // a refill never raids a slot that holds its own item
        assertNull(HotbarRules.plan(Map.of(1, "pickaxe", 6, "pickaxe"), List.of(new HotbarRules.Item(5, "minecraft:stone_pickaxe", 1, -1)), List.of(1)));
    }
}
