package io.github.mojolowjo.entropybot.recorder;

import io.github.mojolowjo.entropybot.recorder.Recorder.Change;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The recorder's costs, measured (printed; the asserts are loose so a slow machine never fails them). The game-thread
 * share of a baseline is the section copies (PalettedContainer.copy, not measurable without the game: a few long[]
 * clones); what is measured here is the background share (palette + gzip) and a change's whole game-thread cost.
 */
class RecorderCostTest {
    static final String OW = "minecraft:overworld";

    @TempDir
    Path dir;

    /** A plausible overworld chunk: 24 sections, the lower 8 stone/deepslate with ores and caves, the top air. */
    static String[][] cells() {
        Random r = new Random(42);
        String[] ores = {"minecraft:coal_ore", "minecraft:iron_ore", "minecraft:copper_ore", "minecraft:deepslate_diamond_ore",
                "minecraft:gravel", "minecraft:dirt", "minecraft:andesite", "minecraft:granite", "minecraft:water", "minecraft:lava"};
        String[][] out = new String[24][];
        for (int s = 0; s < 24; s++) {
            String[] c = new String[4096];
            for (int i = 0; i < 4096; i++) {
                if (s >= 9) c[i] = "minecraft:air";
                else if (s == 8) c[i] = (i >> 8) < 4 ? "minecraft:grass_block" : "minecraft:air";
                else {
                    int k = r.nextInt(100);
                    c[i] = k < 3 ? ores[r.nextInt(ores.length)] : k < 8 ? "minecraft:cave_air" : s < 4 ? "minecraft:deepslate" : "minecraft:stone";
                }
            }
            out[s] = c;
        }
        return out;
    }

    @Test
    void baselineAndChangeCosts() throws Exception {
        String[][] cells = cells();
        long best = Long.MAX_VALUE;
        int bytes = 0;
        for (int run = 0; run < 15; run++) {
            long t = System.nanoTime();
            String[][] pal = new String[24][];
            short[][] idx = new short[24][];
            for (int s = 0; s < 24; s++) ChunkBase.section(cells[s], pal, idx, s);
            ChunkBase b = new ChunkBase(OW, 0, 0, -64, 0, pal, idx);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.write(out);
            best = Math.min(best, System.nanoTime() - t);
            bytes = out.size();
        }
        System.out.printf("[recorder cost] baseline (background: palettes + gzip): %.2f ms, %d bytes on disk%n", best / 1e6, bytes);
        assertTrue(bytes < 64_000, "a chunk baseline stays small: " + bytes);
        assertTrue(best < 500_000_000L);

        RecStore s = new RecStore(dir, System::currentTimeMillis, RecStore.DEFAULT_CAP);
        long bestAdd = Long.MAX_VALUE, bestFlush = Long.MAX_VALUE;
        for (int run = 0; run < 10; run++) {
            long t = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                s.addChange(new Change(System.currentTimeMillis(), OW, i, 64, -i, "minecraft:stone", "minecraft:air", (i & 1) == 0));
            }
            bestAdd = Math.min(bestAdd, System.nanoTime() - t);
            t = System.nanoTime();
            assertEquals(1000, s.flush());
            bestFlush = Math.min(bestFlush, System.nanoTime() - t);
        }
        System.out.printf("[recorder cost] 1000 changes: game thread %.3f ms, background write %.2f ms%n", bestAdd / 1e6, bestFlush / 1e6);
        assertTrue(bestAdd < 200_000_000L);
        long t = System.nanoTime();
        assertFalse(s.changes(OW, 500, 64, -500, 3, 0, 50).isEmpty());
        System.out.printf("[recorder cost] a changes() query over 10000 changes: %.2f ms%n", (System.nanoTime() - t) / 1e6);
    }
}
