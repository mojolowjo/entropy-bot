package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** S1: "goto me" (the owner typed it live) is "come" from the sender. */
class S1CommandsTest {
    @Test
    void gotoMeIsCome() {
        assertTrue(Texts.gotoMeansCome("me", "mojolowjo"));
        assertTrue(Texts.gotoMeansCome(" Me ", "mojolowjo"));
        assertTrue(Texts.gotoMeansCome("mojolowjo", "mojolowjo"));
        assertTrue(Texts.gotoMeansCome("MojoLowJo", "mojolowjo"));
        assertFalse(Texts.gotoMeansCome("10 64 20", "mojolowjo"));
        assertFalse(Texts.gotoMeansCome("someone", "mojolowjo"), "another player's name is no coordinates and no 'me'");
        assertFalse(Texts.gotoMeansCome("", "mojolowjo"));
        assertFalse(Texts.gotoMeansCome("", ""));
        assertFalse(Texts.gotoMeansCome("x", null));
    }
}
