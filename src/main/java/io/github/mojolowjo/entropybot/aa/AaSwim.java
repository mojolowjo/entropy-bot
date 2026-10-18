package io.github.mojolowjo.entropybot.aa;

/** 0.36.a2: pure decision for AA's swim-up (hold jump in water). Only the air danger, never while the air reflex or another danger has the bot. */
public final class AaSwim {
    private AaSwim() {}

    /**
     * @param dangerDrives   AA's danger branch drives
     * @param inWater        the bot is in water
     * @param air            air supply now
     * @param maxAir         max air supply
     * @param reflexHolds    the air reflex holds jump itself
     * @param otherDanger    a danger that is not low air (hostile close, lava/fire, creeper)
     * @param wasSwimming    AA held jump on the last tick (hysteresis: keep going until the air is full)
     */
    public static boolean hold(boolean dangerDrives, boolean inWater, int air, int maxAir, boolean reflexHolds,
                               boolean otherDanger, boolean wasSwimming) {
        if (!dangerDrives || !inWater || reflexHolds || otherDanger) return false;
        if (air >= maxAir) return false;
        return wasSwimming || air < 100;
    }
}
