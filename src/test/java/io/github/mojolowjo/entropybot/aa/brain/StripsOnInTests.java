package io.github.mojolowjo.entropybot.aa.brain;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 0.39.z78 strip-off review: StripWays' default is OFF (a game must not strip before its world's switch is read); the
 * tests price the strip way as before - this extension (auto-detected: junit-platform.properties, META-INF/services) turns
 * it on before each test, and the strip-off tests turn it off themselves.
 *
 * <p>Deposit-goal review 7 (point 5, the order dependence of the full runs: AaLiveTest and AaTreeTest failed in one full
 * run and passed in the next; NeedTest and NightShelterTest earlier): {@code OrderLineSim} and {@code SimGame} set the
 * static {@link Only} switch to orders only in their constructors (as the runtime's default), and a class that built one
 * without setting it back (PutWatchTest, SimDepositGoalTest, SimWakeTest, SimWorldsTest) left every need of the next
 * class at score 0. A run with JUnit's random class order showed it at once (11 failures in 6 classes). Every test now
 * starts on the switch's static default, the full set, so no test reads another's leftover.
 */
public final class StripsOnInTests implements BeforeEachCallback {
    @Override
    public void beforeEach(ExtensionContext context) {
        StripWays.set(true);
        Only.set(null);
    }
}
