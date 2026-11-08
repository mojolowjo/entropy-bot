package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** dep-early (run JAN 02:26, runs JS-JX): a main chest set before the item data; the book and the mains agree (rule 23). */
class DepositEarlyTest {
    @AfterEach
    void clear() {
        MainStations.current = null;
        MainStations.saver = null;
        StationBook.current = new StationBook();
        StationBook.saver = null;
        TestData.clear();
    }

    @Test
    void earlyMarkThenRebuiltLookup() {
        TestData.clear();
        MainStations ms = new MainStations();
        MainStations.current = ms;
        MainStations.Main m = new MainStations.Main(MainStations.CONTAINER, -53, 69, -8, "minecraft:chest");
        assertEquals("main chest: minecraft:chest at -53 69 -8", ms.mark(m));
        assertFalse(ItemWorth.hasData());
        ms.waitForData(m);
        assertEquals(m, ms.of(MainStations.CONTAINER), "the mark stands before the data");
        List<String> said = new ArrayList<>();
        Runnable listener = () -> {
            MainStations.Main w = MainStations.now().takeWaiting();
            if (w != null) said.add(MainStations.listedLine(w, ItemWorth.hasData(), "item data not loaded yet"));
        };
        ItemWorth.onData(listener);
        ItemWorth.onData(listener);                 // kept once
        TestData.vanilla().install();
        TestData.vanilla().install();               // a second load says nothing more
        assertEquals(List.of("deposit lookup: main chest at -53 69 -8 listed"), said);
    }

    @Test
    void answerTexts() {
        assertEquals("main chest set; listed when the item data loads (60 s at most)", MainStations.earlyLine(60));
        MainStations.Main m = new MainStations.Main(MainStations.CONTAINER, 1, 2, 3, "minecraft:chest");
        assertEquals("deposit lookup: main chest at 1 2 3 NOT listed after the item data loaded: none known",
                MainStations.listedLine(m, false, "none known"));
    }

    @Test
    void bookAndMainsAgreeAfterSetAndGone() {
        MainStations ms = new MainStations();
        MainStations.current = ms;
        StationBook book = new StationBook();
        StationBook.current = book;
        int[] saved = {0};
        MainStations.saver = () -> saved[0]++;
        ms.mark(new MainStations.Main(MainStations.CONTAINER, 5, 64, 5, "minecraft:chest"));
        assertEquals(List.of("main chest at 5 64 5 added to the station book"), ms.agree(book, 10));
        assertTrue(book.at(5, 64, 5).present());
        assertEquals(List.of(), ms.agree(book, 11));
        String line = book.gone(5, 64, 5, "broken by a creeper", 12);
        assertTrue(line.endsWith("; main chest at 5 64 5 is no longer the main: the station book marks it gone (broken by a creeper)"), line);
        assertNull(ms.of(MainStations.CONTAINER));
        assertEquals(1, saved[0]);
        // at a load: a main the book holds gone is dropped, the reason said
        ms.mark(new MainStations.Main(MainStations.CONTAINER, 5, 64, 5, "minecraft:chest"));
        assertEquals(List.of("main chest at 5 64 5 is no longer the main: the station book marks it gone (broken by a creeper)"), ms.agree(book, 13));
        assertNull(ms.of(MainStations.CONTAINER));
    }
}
