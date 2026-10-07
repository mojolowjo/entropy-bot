package io.github.mojolowjo.entropybot.commands;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OutboxRouteTest {
    @Test
    void aWhisperToSomeoneOfflineGoesToTheBotsOwnChat() {
        // 0.22.3: singleplayer gave "No player was found" for the brain's whispers to the owner
        assertTrue(Outbox.canMsg("Mojolowjo", List.of("0Entropy1", "mojolowjo")));
        assertFalse(Outbox.canMsg("mojolowjo", List.of("0Entropy1")));
        assertFalse(Outbox.canMsg("mojolowjo", List.of()));
        assertFalse(Outbox.canMsg(null, List.of("x")));
    }
}