package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.guard.Policy;

/**
 * 0.39.z26 fix (4): a test scan states its station policy like the live one (no default that allows): the owner's one
 * work area over the whole overworld, so every station there is "station, in my areas" through BreakPolicy.stationUse.
 */
public final class TestStations {
    private TestStations() {}

    public static final String OW = "minecraft:overworld";
    public static final Policy OPEN = Policy.parse("{\"areas\":[{\"name\":\"test world\",\"dim\":\"minecraft:overworld\",\"x1\":-30000000,\"z1\":-30000000,\"x2\":30000000,\"z2\":30000000}]}");
}
