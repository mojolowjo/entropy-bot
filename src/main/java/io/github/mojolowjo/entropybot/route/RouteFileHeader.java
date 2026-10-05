package io.github.mojolowjo.entropybot.route;

/**
 * The header of every route tile file ({@code entropybot\routes\<dim>\t.<tx>.<tz>.bin}, gzip): magic {@code ERT1},
 * then these fields.
 *
 * <p>On load: another {@code formatVersion} = the file is ignored (counted); another {@code settingsHash} = the boxes
 * load but are all stale; another {@code areasHash} (review R9) = the boxes load as they are, and the caller
 * should queue the areas' missing boxes (a grown area means missing boxes, not stale ones); another mod or Baritone
 * version = loaded as they are (the settings hash covers what changes costs), noted in the load result.
 *
 * @param settingsHash hash of the cost-relevant Baritone settings ({@link RouteHashes#settings}).
 * @param areasHash    hash of the owner's areas ({@link RouteHashes#areas}).
 */
public record RouteFileHeader(int formatVersion, String modVersion, String baritoneVersion,
                              long settingsHash, long areasHash) {
    public static final int FORMAT_VERSION = 1;
    public static final int MAGIC = ('E' << 24) | ('R' << 16) | ('T' << 8) | '1';

    public static RouteFileHeader current(String modVersion, String baritoneVersion, long settingsHash, long areasHash) {
        return new RouteFileHeader(FORMAT_VERSION, modVersion, baritoneVersion, settingsHash, areasHash);
    }
}
