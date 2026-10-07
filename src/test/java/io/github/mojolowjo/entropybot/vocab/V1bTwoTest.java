package io.github.mojolowjo.entropybot.vocab;

import io.github.mojolowjo.entropybot.commands.Texts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** V1b-2 (0.22.2): the fighting words: attack's rules (with the name test), defend/guard's spot, dismiss for guests. */
class V1bTwoTest {
    static AttackRules.Target mob(String kind, boolean hostile, String name, boolean typed) {
        return new AttackRules.Target(kind, false, false, hostile, name, typed);
    }

    @Test
    void attackParse() {
        assertEquals(AttackRules.How.NEAREST, AttackRules.parse("").how());
        assertEquals(AttackRules.How.NEAREST, AttackRules.parse("nearest").how());
        AttackRules.Arg t = AttackRules.parse("target 812");
        assertEquals(AttackRules.How.ID, t.how());
        assertEquals(812, t.id());
        assertFalse(t.confirm());
        assertTrue(AttackRules.parse("812 confirm").confirm());
        AttackRules.Arg n = AttackRules.parse("Cow");
        assertEquals(AttackRules.How.NAME, n.how());
        assertEquals("cow", n.word());
        assertNotNull(AttackRules.parse("the big cow").error());
        assertNotNull(AttackRules.parse("99999999999").error(), "too big an id");
    }

    @Test
    void attackRules() {
        assertEquals(AttackRules.Verdict.GO, AttackRules.decide(mob("zombie", true, null, false), false, false, 1).verdict(), "hostile: go");
        AttackRules.Answer cow = AttackRules.decide(mob("cow", false, null, false), false, false, 7);
        assertEquals(AttackRules.Verdict.CONFIRM, cow.verdict(), "passive by the point key: a confirm press");
        assertTrue(cow.text().contains("attack 7 confirm") && cow.text().contains("attack cow"), cow.text());
        assertEquals(AttackRules.Verdict.GO, AttackRules.decide(mob("cow", false, null, true), false, false, 7).verdict(), "typed out in full: go");
        assertEquals(AttackRules.Verdict.GO, AttackRules.decide(mob("cow", false, null, false), true, false, 7).verdict(), "confirmed: go");
        AttackRules.Target steve = new AttackRules.Target("Steve", true, false, false, null, true);
        AttackRules.Answer no = AttackRules.decide(steve, true, false, 3);
        assertEquals(AttackRules.Verdict.REFUSE, no.verdict());
        assertTrue(no.text().contains("defence players is off"), no.text());
        assertEquals(AttackRules.Verdict.GO, AttackRules.decide(steve, false, true, 3).verdict(), "players only with the setting");
        AttackRules.Target pet = new AttackRules.Target("wolf", false, true, false, null, true);
        assertEquals(AttackRules.Verdict.REFUSE, AttackRules.decide(pet, true, true, 4).verdict(), "pets never, even confirmed");
        // the name test: digits are a health display, letters a name
        assertFalse(AttackRules.isName("20/20"));
        assertFalse(AttackRules.isName("12"));
        assertFalse(AttackRules.isName(null));
        assertTrue(AttackRules.isName("Bob"));
        assertTrue(AttackRules.isName("Bob 20/20"));
        assertEquals(AttackRules.Verdict.GO, AttackRules.decide(mob("zombie", true, "20/20", false), false, false, 5).verdict(), "a health display isn't a name");
        AttackRules.Answer bob = AttackRules.decide(mob("zombie", true, "Bob", false), false, false, 5);
        assertEquals(AttackRules.Verdict.CONFIRM, bob.verdict(), "a named mob needs confirm");
        assertTrue(bob.text().contains("named 'Bob'"));
        assertEquals(AttackRules.Verdict.GO, AttackRules.decide(mob("zombie", true, "Bob", false), true, false, 5).verdict());
        assertEquals(Boolean.TRUE, AttackRules.playersSetting("players on"));
        assertEquals(Boolean.FALSE, AttackRules.playersSetting("PLAYERS OFF"));
        assertNull(AttackRules.playersSetting("on"));
    }

    @Test
    void holdSpot() {
        assertFalse(HoldRules.outside(null, 0, 0, 4, 3, 64, 2), "inside 4");
        assertTrue(HoldRules.outside(null, 0, 0, 4, 4, 64, 3), "out of 4");
        int[] box = {0, 60, 0, 10, 70, 10};
        assertFalse(HoldRules.outside(box, 0, 0, 0, 5, 65, 5));
        assertTrue(HoldRules.outside(box, 0, 0, 0, 11, 65, 5));
        assertTrue(HoldRules.outside(box, 0, 0, 0, 5, 80, 5), "above the box");
        int[] allY = {0, 1, 0, 10, 0, 10};
        assertFalse(HoldRules.outside(allY, 0, 0, 0, 5, 200, 5), "an all-heights box ignores y");
        assertArrayEquals(new int[]{5, 64, 5}, HoldRules.home(box, 0, 64, 0));
        assertTrue(HoldRules.fenceForm(""));
        assertTrue(HoldRules.fenceForm("mode strict"));
        assertFalse(HoldRules.fenceForm("base"));
    }

    @Test
    void guestsMayDismissAndEscortThemselves() {
        assertNull(Texts.guestRefusal("dismiss", "", "dismiss", "owner"));
        assertNull(Texts.guestRefusal("escort", "me", "escort me", "owner"));
        assertNotNull(Texts.guestRefusal("defend", "", "defend", "owner"));
        assertNotNull(Texts.guestRefusal("attack", "zombie", "attack zombie", "owner"));
    }
}
