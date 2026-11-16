package baritone.api;

/**
 * TEST ONLY (never in the mod's jar: src/test comes before the Baritone jar on the unit tests' classpath). The real
 * BaritoneAPI reads its settings file from the game directory and builds a Baritone for Minecraft.getInstance() in its
 * static init; with no game running that fails, and every read of Baritone's settings with it. The simulator's search
 * (sim/SimSearch) needs the settings: here Baritone's defaults and no provider. Loader note: plain Java.
 */
public final class BaritoneAPI {
    private static final Settings settings = new Settings();

    private BaritoneAPI() {}

    public static IBaritoneProvider getProvider() {
        throw new UnsupportedOperationException("no Baritone provider in a unit test");
    }

    public static Settings getSettings() {
        return settings;
    }
}
