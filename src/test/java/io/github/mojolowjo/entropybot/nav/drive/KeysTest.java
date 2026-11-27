package io.github.mojolowjo.entropybot.nav.drive;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

/** Nav step 8: the pretend keys and PlayerMovementInput's sums. */
class KeysTest {

    @Test
    void forwardAndBackCancelLeftAndRightCancel() {
        Keys k = new Keys();
        k.set(EnumSet.of(Keys.Key.FORWARD, Keys.Key.BACK, Keys.Key.LEFT));
        Keys.Impulse i = k.impulse(0.3f);
        assertEquals(0f, i.forward());
        assertEquals(1f, i.left());
        assertTrue(i.up() && i.down() && i.leftKey() && !i.rightKey());
        k.set(EnumSet.of(Keys.Key.RIGHT));
        assertEquals(-1f, k.impulse(0.3f).left());
    }

    @Test
    void sneakScalesByTheGamesMultiplier() {
        Keys k = new Keys();
        k.set(EnumSet.of(Keys.Key.FORWARD, Keys.Key.SNEAK, Keys.Key.JUMP));
        Keys.Impulse i = k.impulse(0.3f);
        assertEquals(0.3f, i.forward(), 1e-6);
        assertTrue(i.sneaking() && i.jumping());
        assertEquals(0.75f, k.impulse(0.75f).forward(), 1e-6, "Swift Sneak's larger multiplier is the game's to pass");
    }

    @Test
    void sprintAloneDoesNotMoveAndReleaseAllLetsGo() {
        Keys k = new Keys();
        k.press(Keys.Key.SPRINT);
        assertFalse(k.moving());
        k.press(Keys.Key.FORWARD);
        assertTrue(k.moving());
        k.releaseAll();
        assertFalse(k.moving());
        assertEquals(0f, k.impulse(0.3f).forward());
        assertTrue(k.down().isEmpty());
        assertEquals("no keys", k.toString());
    }
}
