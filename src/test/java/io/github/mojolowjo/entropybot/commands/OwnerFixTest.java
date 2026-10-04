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
        assertArrayEquals(new int[]{10, 64, -31}, OwnerFix.pos(FIX, "MojoLowjo", OW, 100_000 + OwnerFix.MAX_AGE_MS), "exactly 10 s still counts, any case");
    }

    @Test void staleFixGivesNothing() {
        assertNull(OwnerFix.pos(FIX, "mojolowjo", OW, 100_000 + OwnerFix.MAX_AGE_MS + 1));
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
        assertNotNull(OwnerFix.pos(FIX, "mojolowjo", OW, 100_000 - OwnerFix.FUTURE_SLACK_MS));
        assertNull(OwnerFix.pos(FIX, "mojolowjo", OW, 100_000 - OwnerFix.FUTURE_SLACK_MS - 1));
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
        assertNull(new OwnerFix(() -> null).pos("mojolowjo", OW));
    }
}