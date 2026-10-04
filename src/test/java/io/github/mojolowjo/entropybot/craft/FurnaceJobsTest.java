package io.github.mojolowjo.entropybot.craft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.craft.FurnaceJobs.Collect;
import io.github.mojolowjo.entropybot.craft.FurnaceJobs.Job;
import io.github.mojolowjo.entropybot.craft.FurnaceJobs.Next;
import io.github.mojolowjo.entropybot.craft.FurnaceJobs.Slots;
import io.github.mojolowjo.entropybot.memory.Limits;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Package D: the remembered furnace jobs and the decisions at a furnace. */
class FurnaceJobsTest {
    static final String OW = "minecraft:overworld";
    static final int[] F1 = {-32, 53, 183}, F2 = {-30, 53, 183};
    static final Crafter.Smelt IRON = new Crafter.Smelt("minecraft:iron_ingot", "minecraft:iron_ingot", 40, "minecraft:raw_iron", 40, "minecraft:coal", 5);
    static final long T0 = 1_000_000_000L;

    @Test
    void aJobIsRememberedAcrossARestart() {
        JsonObject root = new JsonObject();
        int[] saves = {0};
        FurnaceJobs fj = new FurnaceJobs(root, () -> saves[0]++);
        Job j = fj.add(F1, OW, IRON, FurnaceJobs.CRAFT, "crafting 1 16k_storage_disk", T0);
        assertNotNull(j);
        assertEquals(T0 + 40 * 10_000 + 2_000, j.dueAt, "40 items, 10 s each, 2 s slack");
        fj.collected(j, 12);
        assertTrue(saves[0] >= 2, "every change is saved");
        // the game closes: commands.json is written, read back next time
        JsonObject reread = JsonParser.parseString(root.toString()).getAsJsonObject();
        FurnaceJobs after = new FurnaceJobs(reread, null);
        Job k = after.at(F1, OW);
        assertNotNull(k, "the job survived the restart");
        assertEquals(12, k.collected);
        assertEquals(28, k.remaining());
        assertEquals(j.dueAt, k.dueAt, "wall-clock time: the same due time after a restart");
        assertEquals("minecraft:raw_iron", k.input);
        assertEquals(FurnaceJobs.CRAFT, k.kind);
        assertTrue(after.due(T0 + 60_000, OW).isEmpty(), "not due a minute in");
        assertEquals(List.of(k), after.due(k.dueAt, OW));
        assertTrue(after.due(k.dueAt, "minecraft:the_nether").isEmpty(), "another dimension's furnaces wait");
        Job second = after.add(F2, OW, IRON, FurnaceJobs.SMELT, null, T0);
        assertTrue(second.id > k.id, "ids go on after a restart");
        assertTrue(after.list(T0).startsWith("furnace jobs: #1 40 iron_ingot at -32 53 183 (12 taken), ready in 6m 42s, for crafting 1 16k_storage_disk; #2"), after.list(T0));
        after.collected(k, 28);
        assertNull(after.get(k.id), "collected in full: forgotten");
    }

    @Test
    void aBusyFurnaceIsNeverClearedOrAddedTo() {
        assertEquals("it is smelting 12 raw_gold", FurnaceJobs.busy(new Slots("minecraft:raw_gold", 12, "minecraft:coal", 3, null, 0)), "another player's smelting");
        assertEquals("5 iron_ingot wait in its output", FurnaceJobs.busy(new Slots(null, 0, null, 0, "minecraft:iron_ingot", 5)), "output nobody took (an earlier job's)");
        assertNull(FurnaceJobs.busy(new Slots(null, 0, "minecraft:coal", 6, null, 0)), "only fuel in it: free (the fuel put is soft)");
        assertNull(FurnaceJobs.busy(Slots.EMPTY));
        // one job per furnace: an earlier job of ours there makes it busy for a new one
        FurnaceJobs fj = new FurnaceJobs(new JsonObject(), null);
        assertNotNull(fj.add(F1, OW, IRON, FurnaceJobs.SMELT, null, T0));
        assertNull(fj.add(F1, OW, IRON, FurnaceJobs.SMELT, null, T0), "never a second job in the same furnace");
        // back at our furnace: someone else's input replaced ours -> never taken, never cleared: our output is gone
        Job j = fj.at(F1, OW);
        Collect c = FurnaceJobs.collect(new Slots("minecraft:raw_gold", 8, null, 0, null, 0), j, 0, 40, T0, T0);
        assertEquals(Next.GONE, c.next());
        assertEquals("it is smelting 8 raw_gold now, not my raw_iron", c.why());
        c = FurnaceJobs.collect(new Slots(null, 0, null, 0, "minecraft:gold_ingot", 8), j, 0, 40, T0, T0);
        assertEquals(Next.GONE, c.next(), "someone else's output: not ours to take");
        assertEquals(0, c.take());
    }

    @Test
    void outputIsTakenEarlyWaitedForOrFoundGone() {
        FurnaceJobs fj = new FurnaceJobs(new JsonObject(), null);
        Job j = fj.add(F1, OW, IRON, FurnaceJobs.CRAFT, null, T0);
        // early collection: 9 done of 40, the next step needs 9 -> take them, the rest keeps smelting
        Collect c = FurnaceJobs.collect(new Slots("minecraft:raw_iron", 31, "minecraft:coal", 1, "minecraft:iron_ingot", 9), j, 0, 9, T0 + 90_000, T0);
        assertEquals(new Collect(Next.TAKE, 9, null), c);
        fj.collected(j, 9);
        assertEquals(Next.DONE, FurnaceJobs.collect(new Slots("minecraft:raw_iron", 31, null, 0, null, 0), j, 9, 9, T0 + 91_000, T0 + 91_000).next());
        // the rest: still smelting -> wait
        assertEquals(Next.WAIT, FurnaceJobs.collect(new Slots("minecraft:raw_iron", 31, null, 0, null, 0), j, 0, 31, T0 + 100_000, T0 + 91_000).next());
        // past due, input left, nothing new for a minute: stopped (out of fuel)
        Collect st = FurnaceJobs.collect(new Slots("minecraft:raw_iron", 20, null, 0, null, 0), j, 0, 31, j.dueAt + 120_000, j.dueAt);
        assertEquals(Next.STALLED, st.next());
        assertEquals("it stopped with 20 raw_iron left to smelt (out of fuel?)", st.why());
        // arrived and everything is gone (a player or a pipe took it)
        Collect gone = FurnaceJobs.collect(Slots.EMPTY, j, 0, 31, j.dueAt + 1000, T0);
        assertEquals(Next.GONE, gone.next());
        assertEquals("my 31 iron_ingot are gone (input and output empty)", gone.why());
        // finished output found on arrival: all of it taken at once
        assertEquals(new Collect(Next.TAKE, 31, null), FurnaceJobs.collect(new Slots(null, 0, null, 0, "minecraft:iron_ingot", 31), j, 0, 31, j.dueAt + 5000, T0));
    }

    @Test
    void capsAndExpiry() {
        FurnaceJobs fj = new FurnaceJobs(new JsonObject(), null);
        for (int i = 0; i < Limits.FURNACE_JOBS; i++) assertNotNull(fj.add(new int[]{i, 60, 0}, OW, IRON, FurnaceJobs.SMELT, null, T0));
        assertTrue(fj.full());
        assertNull(fj.add(new int[]{99, 60, 0}, OW, IRON, FurnaceJobs.SMELT, null, T0), "a 17th is refused, none dropped");
        assertEquals(Limits.FURNACE_JOBS, fj.all().size());
        assertTrue(FurnaceJobs.fullRefusal().startsWith("I remember 16 furnace jobs already"));
        long due = fj.all().get(0).dueAt;
        assertTrue(fj.expire(due + FurnaceJobs.EXPIRE_MS, T0).isEmpty(), "not before 30 min past due");
        // after a restart (the session started later), the 30 minutes count from the session's start
        assertTrue(fj.expire(due + FurnaceJobs.EXPIRE_MS + 1000, due + 20 * 60_000).isEmpty());
        List<Job> gone = fj.expire(due + 20 * 60_000 + FurnaceJobs.EXPIRE_MS + 1000, due + 20 * 60_000);
        assertEquals(Limits.FURNACE_JOBS, gone.size());
        assertTrue(fj.isEmpty());
        assertEquals("I stopped tracking 40 iron_ingot in the furnace at 0 60 0 (30 min past due) - they may still be in it", FurnaceJobs.forgotten(gone.get(0)));
        // a failed pickup is not tried again at once
        Job j = fj.add(F1, OW, IRON, FurnaceJobs.SMELT, null, T0);
        fj.later(List.of(j), j.dueAt);
        assertTrue(fj.due(j.dueAt + 1000, OW).isEmpty());
        assertEquals(1, fj.due(j.dueAt + FurnaceJobs.RETRY_MS, OW).size());
        assertEquals("1m 30s", FurnaceJobs.eta(90_000));
        assertEquals("45s", FurnaceJobs.eta(44_200));
        assertEquals("now", FurnaceJobs.eta(0));
    }

    @Test
    void aBrokenEntryIsSkipped() {
        JsonObject root = JsonParser.parseString("{\"furnaces\":{\"next\":7,\"jobs\":[{\"id\":3},{\"id\":4,\"pos\":[1,2,3],\"item\":\"minecraft:glass\",\"input\":\"minecraft:sand\",\"n\":2,\"want\":2,\"dueAt\":5}]}}").getAsJsonObject();
        FurnaceJobs fj = new FurnaceJobs(root, null);
        assertEquals(1, fj.all().size());
        assertEquals("minecraft:overworld", fj.all().get(0).dim);
        assertEquals(7, fj.add(F1, OW, IRON, FurnaceJobs.SMELT, null, T0).id);
    }
}
