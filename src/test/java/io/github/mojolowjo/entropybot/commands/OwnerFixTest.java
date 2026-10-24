package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class OwnerFixTest {
    static final String OW = "minecraft:overworld";
    static final OwnerFix.Fix FIX = new OwnerFix.Fix("mojolowjo", 10.7, 64.0, -30.2, OW, 100_000);

    @TempDir Path tmp;

    @Test void freshFixInTheBotsDimensionGivesBlockCoordinates() {
        assertArrayEquals(new int[]{10, 64, -31}, OwnerFix.pos(FIX, "mojolowjo", OW, 100_000));
        assertArrayEquals(new int[]{10, 64, -31}, OwnerFix.pos(FIX, "MojoLowjo", OW, 100_000 + OwnerFix.maxAgeMs()), "exactly 10 s still counts, any case");
    }

    @Test void staleFixGivesNothing() {
        assertNull(OwnerFix.pos(FIX, "mojolowjo", OW, 100_000 + OwnerFix.maxAgeMs() + 1));
    }

    @Test void otherDimensionGivesNothing() {
        assertNull(OwnerFix.pos(FIX, "mojolowjo", "minecraft:the_nether", 100_000));
        assertNull(OwnerFix.pos(FIX, "mojolowjo", null, 100_000));
    }

    @Test void otherPlayerGivesNothing() {
        assertNull(OwnerFix.pos(FIX, "someoneelse", OW, 100_000));
        assertNull(OwnerFix.pos(FIX, null, OW, 100_000));
        assertNull(OwnerFix.pos(null, "mojolowjo", OW, 100_000));
    }

    @Test void aFixFromTheFutureIsRefusedBeyondTheSlack() {
        assertNotNull(OwnerFix.pos(FIX, "mojolowjo", OW, 100_000 - OwnerFix.futureSlackMs()));
        assertNull(OwnerFix.pos(FIX, "mojolowjo", OW, 100_000 - OwnerFix.futureSlackMs() - 1));
    }

    @Test void parseReadsTheDashboardsFileAndRefusesBadOnes() {
        OwnerFix.Fix f = OwnerFix.parse("{\"name\":\"mojolowjo\",\"x\":1.5,\"y\":64.0,\"z\":-2.25,\"dim\":\"minecraft:overworld\",\"at\":5,\"received\":99}");
        assertEquals(new OwnerFix.Fix("mojolowjo", 1.5, 64.0, -2.25, OW, 99), f);
        assertNull(OwnerFix.parse("{\"name\":\"a\",\"x\":1,\"y\":2,\"z\":3,\"dim\":\"d\"}"), "no received");
        assertNull(OwnerFix.parse("{\"name\":\"a\",\"x\":\"far\",\"y\":2,\"z\":3,\"dim\":\"d\",\"received\":1}"));
        assertNull(OwnerFix.parse("{\"name\":\"\",\"x\":1,\"y\":2,\"z\":3,\"dim\":\"d\",\"received\":1}"));
        assertNull(OwnerFix.parse("{ half written"));
        assertNull(OwnerFix.parse(""));
        assertNull(OwnerFix.parse("[1,2]"));
    }

    @Test void fileIsCachedByMtimeAndReadAgainWhenItChanges() throws IOException {
        Path f = tmp.resolve("owner.json");
        OwnerFix of = new OwnerFix(() -> f);
        assertNull(of.current(), "no file yet");
        Files.writeString(f, "{\"name\":\"mojolowjo\",\"x\":1,\"y\":2,\"z\":3,\"dim\":\"minecraft:overworld\",\"received\":" + System.currentTimeMillis() + "}");
        assertArrayEquals(new int[]{1, 2, 3}, of.pos("mojolowjo", OW));
        // same mtime and size: not read again (the cached object itself comes back)
        OwnerFix.Fix a = of.current();
        assertEquals(a, of.current());
        // a new post (different content, new mtime) replaces it
        Files.writeString(f, "{\"name\":\"mojolowjo\",\"x\":7,\"y\":2,\"z\":3,\"dim\":\"minecraft:overworld\",\"received\":" + System.currentTimeMillis() + "}");
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertArrayEquals(new int[]{7, 2, 3}, of.pos("mojolowjo", OW));
    }

    @Test void aBrokenRewriteKeepsTheLastGoodFixButAgeStillRules() throws IOException {
        Path f = tmp.resolve("owner.json");
        OwnerFix of = new OwnerFix(() -> f);
        long now = System.currentTimeMillis();
        Files.writeString(f, "{\"name\":\"mojolowjo\",\"x\":1,\"y\":2,\"z\":3,\"dim\":\"minecraft:overworld\",\"received\":" + now + "}");
        assertNotNull(of.pos("mojolowjo", OW));
        Files.writeString(f, "{ torn");
        Files.setLastModifiedTime(f, FileTime.fromMillis(now + 5000));
        assertNotNull(of.pos("mojolowjo", OW), "the last good fix, still fresh");
        // a good file that is old: refused
        Files.writeString(f, "{\"name\":\"mojolowjo\",\"x\":1,\"y\":2,\"z\":3,\"dim\":\"minecraft:overworld\",\"received\":" + (now - 60_000) + "}");
        Files.setLastModifiedTime(f, FileTime.fromMillis(now + 9000));
        assertNull(of.pos("mojolowjo", OW));
    }

    @Test void noFolderYetGivesNothing() {
        java.util.List<String> said = new java.util.ArrayList<>();
        OwnerFix of = new OwnerFix(() -> null, said::add);
        assertNull(of.pos("mojolowjo", OW));
        assertNull(of.pos("mojolowjo", OW));
        assertEquals(OwnerFix.State.NOT_LOADED, of.state());
        assertEquals(0, said.size(), "an unknown folder is not said");
    }

    @Test void missingFileIsNoneSaidOnceThenReportSaidOnceThenNoneAgain() throws IOException {
        java.util.List<String> said = new java.util.ArrayList<>();
        Path f = tmp.resolve("owner.json");
        OwnerFix of = new OwnerFix(() -> f, said::add);
        for (int i = 0; i < 5; i++) assertNull(of.current());
        assertEquals(OwnerFix.State.NONE, of.state());
        assertEquals(1, said.size());
        assertEquals("no companion report (entropybot/owner.json): the owner's position comes from sight only", said.get(0));
        Files.writeString(f, "{\"name\":\"mojolowjo\",\"x\":1,\"y\":2,\"z\":3,\"dim\":\"minecraft:overworld\",\"received\":" + System.currentTimeMillis() + "}");
        for (int i = 0; i < 3; i++) assertNotNull(of.current());
        assertEquals(OwnerFix.State.REPORT, of.state());
        assertEquals(2, said.size());
        Files.delete(f);
        of.current();
        of.current();
        assertEquals(OwnerFix.State.NONE, of.state());
        assertEquals(3, said.size(), "the file going is said once");
    }

    @Test void brokenFileIsBrokenWithTheReasonSaidOncePerTextChange() throws IOException {
        java.util.List<String> said = new java.util.ArrayList<>();
        Path f = tmp.resolve("owner.json");
        OwnerFix of = new OwnerFix(() -> f, said::add);
        Files.writeString(f, "{ torn");
        for (int i = 0; i < 4; i++) of.current();
        assertEquals(OwnerFix.State.BROKEN, of.state());
        assertEquals(1, said.size());
        assertEquals(true, said.get(0).contains("broken: not a usable report"));
        Files.writeString(f, "{\"name\":\"a\",\"x\":1,\"y\":2,\"z\":3,\"dim\":\"d\"}");
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        of.current();
        of.current();
        assertEquals(OwnerFix.State.BROKEN, of.state());
        assertEquals(2, said.size(), "a different reason is said once more");
    }
}