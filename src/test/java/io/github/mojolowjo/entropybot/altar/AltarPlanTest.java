package io.github.mojolowjo.entropybot.altar;

import io.github.mojolowjo.entropybot.craft.RecipeData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.mojolowjo.entropybot.altar.FakeAltar.*;
import static org.junit.jupiter.api.Assertions.*;

/** Package E: the altar plan against item 14's hand run (the ground truth). */
class AltarPlanTest {
    /** Silicon seeds as the game lists an infusion recipe: the altar's input first, then the 8 pedestal items. */
    static RecipeData silicon() {
        List<List<String>> cells = new ArrayList<>();
        cells.add(List.of(BASE));
        for (int i = 0; i < 4; i++) {
            cells.add(List.of(SILICON));
            cells.add(List.of(PRUD));
        }
        return new RecipeData(MA + "infusion/silicon_seeds", MA + "infusion", SEEDS, 1, 0, 0, true, cells);
    }

    static AltarPlan.Pick pick() {
        AltarPlan.Split s = AltarPlan.split(silicon(), id -> true);
        return AltarPlan.pick(s, Map.of(SILICON, 100, PRUD, 100, BASE, 2));
    }

    static AltarPlan.Layout layout(FakeAltar a) {
        return AltarPlan.layout(ALTAR, a, p -> true, 8);
    }

    @Test
    void theRecipeIsSplitIntoAltarAndPedestals() {
        assertTrue(AltarPlan.infusion(silicon()));
        AltarPlan.Split s = AltarPlan.split(silicon(), id -> true);
        assertTrue(s.ok());
        assertEquals(List.of(BASE), s.center());
        assertEquals(8, s.pedestals().size());
        assertFalse(s.guessed());
        // the seed base wherever it is listed
        List<List<String>> cells = new ArrayList<>(silicon().cells());
        cells.add(cells.remove(0));
        AltarPlan.Split s2 = AltarPlan.split(new RecipeData("x", MA + "infusion", SEEDS, 1, 0, 0, true, cells), id -> true);
        assertEquals(List.of(BASE), s2.center());
        // item 14: "recipe" hid the center - 8 cells: the prosperity base is assumed and said so
        AltarPlan.Split s3 = AltarPlan.split(new RecipeData("x", MA + "infusion", SEEDS, 1, 0, 0, true, silicon().cells().subList(1, 9)), id -> true);
        assertTrue(s3.guessed());
        assertEquals(List.of(AltarPlan.PROSPERITY_BASE), s3.center());
        assertEquals(8, s3.pedestals().size());
        // mob seeds sit on the soulium base
        AltarPlan.Split s4 = AltarPlan.split(new RecipeData("x", MA + "infusion", MA + "zombie_seeds", 1, 0, 0, true,
                List.of(List.of(MA + "zombie_chunk"), List.of(PRUD))), id -> true);
        assertEquals(List.of(AltarPlan.SOULIUM_BASE), s4.center());
        // too many for 8 pedestals
        List<List<String>> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) ten.add(List.of(SILICON));
        assertFalse(AltarPlan.split(new RecipeData("x", MA + "infusion", SEEDS, 1, 0, 0, true, ten), id -> true).ok());
        assertFalse(AltarPlan.infusion(RecipeData.shapeless("x", SEEDS, 1, List.of(List.of(SILICON)))), "a crafting recipe is not an infusion");
    }

    @Test
    void thePedestalItemsAreGroupedAsTheHandRunHadThem() {
        AltarPlan.Pick p = pick();
        assertEquals(BASE, p.center());
        assertEquals(List.of(SILICON, SILICON, SILICON, SILICON, PRUD, PRUD, PRUD, PRUD), p.pedestals());
        assertEquals(Map.of(BASE, 2, SILICON, 8, PRUD, 8), p.times(2));
        // an ingredient with alternatives takes the one in stock
        AltarPlan.Split s = new AltarPlan.Split(List.of(BASE), List.of(List.of("a:x", "b:x"), List.of("a:x", "b:x")), false, null);
        assertEquals(List.of("b:x", "b:x"), AltarPlan.pick(s, Map.of("b:x", 5)).pedestals());
        assertEquals(List.of("a:x", "a:x"), AltarPlan.pick(s, Map.of()).pedestals(), "none anywhere: the first");
    }

    @Test
    void item14sAltarIsLaidOutAsTheHandRanIt() {
        FakeAltar a = FakeAltar.item14();
        AltarPlan.Layout l = layout(a);
        assertTrue(l.ok(), l.error());
        assertArrayEquals(BUTTON, l.button());
        assertArrayEquals(new int[]{-22, 53, 157}, l.stand(), "where item 14 stood");
        for (int i = 0; i < 8; i++) assertArrayEquals(PEDS[i], l.pedestals().get(i));
        List<AltarPlan.Target> t = AltarPlan.targets(l, pick());
        assertEquals(9, t.size());
        assertTrue(t.get(0).altar());
        assertEquals(BASE, t.get(0).item());
        // silicon on the first 4 pedestals, prudentium on the other 4 (item 14, step 7)
        for (int i = 1; i <= 4; i++) assertEquals(SILICON, t.get(i).item());
        for (int i = 5; i <= 8; i++) assertEquals(PRUD, t.get(i).item());
        assertArrayEquals(new int[]{-20, 53, 156}, t.get(8).pos());
        for (int[] p : PEDS) assertTrue(AltarPlan.reaches(l.stand(), p), "reach " + FakeAltar.k(p));
        assertFalse(AltarPlan.reaches(new int[]{-20, 53, 158}, PEDS[0]), "one block further out the far pedestals are out of reach");
    }

    @Test
    void anAltarItCantUseIsRefusedWithTheReason() {
        FakeAltar a = FakeAltar.item14();
        a.blocks.remove(FakeAltar.k(BUTTON));
        assertTrue(layout(a).error().contains("no button"), layout(a).error());
        FakeAltar b = FakeAltar.item14();
        b.blocks.remove(FakeAltar.k(PEDS[3]));
        assertEquals("the altar at -23 53 156 has 7 pedestals and this needs 8", layout(b).error());
        assertTrue(AltarPlan.layout(ALTAR, b, p -> true, 4).ok(), "4 pedestal items: 7 pedestals do");
        FakeAltar c = FakeAltar.item14();
        assertTrue(AltarPlan.layout(ALTAR, c, p -> false, 8).error().contains("no spot next to the altar"));
        assertTrue(AltarPlan.layout(new int[]{0, 60, 0}, c, p -> true, 8).error().contains("no infusion altar"));
    }

    @Test
    void whatIsOnTheAltarIsOnlyTheBotsWhenItSaysSo() {
        FakeAltar a = FakeAltar.item14();
        AltarMemory mem = new AltarMemory(new com.google.gson.JsonObject(), null);
        AltarPlan.Layout l = layout(a);
        List<AltarPlan.Target> t = AltarPlan.targets(l, pick());
        assertNull(AltarPlan.survey(l, t, a, mem).foreign(), "empty: fine");
        a.put(PEDS[6], 0, "minecraft:iron_ingot");
        assertEquals("the pedestal at -21 53 158 holds 1 iron_ingot that isn't mine - I never take things off the altar or its pedestals; please empty it and run infuse again",
                AltarPlan.survey(l, t, a, mem).foreign());
        // the same item noted as the bot's: its own, but not what this round wants there -> taken back first
        mem.place(PEDS[6], "minecraft:iron_ingot");
        AltarPlan.Survey sv = AltarPlan.survey(l, t, a, mem);
        assertNull(sv.foreign());
        assertEquals(1, sv.retrieve().size());
        // the bot's silicon where silicon goes: ready
        a.put(PEDS[0], 0, SILICON);
        mem.place(PEDS[0], SILICON);
        assertEquals(1, AltarPlan.survey(l, t, a, mem).ready().size());
        // an output that isn't the bot's seed
        FakeAltar b = FakeAltar.item14().put(ALTAR, 1, MA + "iron_seeds");
        assertTrue(AltarPlan.survey(l, t, b, mem).foreign().startsWith("the infusion altar at -23 53 156 has 1 mysticalagriculture:iron_seeds in its output that isn't mine"));
        mem.pressed(MA + "iron_seeds");
        assertTrue(AltarPlan.survey(l, t, b, mem).ownOutput(), "the bot pressed for it before a stop: its own");
    }

    @Test
    void theRefusalsSayWhatIsMissing() {
        assertEquals("error: I can't infuse 2 mysticalagriculture:silicon_seeds - missing 3 refinedstorage:silicon (I have 5 in my bag, chests and the RS network; 4 a seed)"
                + " and I can't craft it: there is no crafting recipe for refinedstorage:silicon",
                AltarPlan.missing(2, SEEDS, SILICON, 5, 4, "there is no crafting recipe for refinedstorage:silicon"));
        assertTrue(AltarPlan.noRecipe("dirt").startsWith("error: I know no infusion recipe for dirt"));
        assertEquals("error: I can't find an infusion altar within 16 blocks of me or 24 of the base", AltarPlan.noAltar(true));
    }
}
