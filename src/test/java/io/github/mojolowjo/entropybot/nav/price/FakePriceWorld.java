package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.config.NavConfig;
import io.github.mojolowjo.entropybot.nav.rules.BlockRules;
import io.github.mojolowjo.entropybot.nav.rules.BodySnapshot;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.world.BlockView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * A PriceWorld over a test BlockView: the start and feet set by the test, the searches run inline (or held until
 * {@link #runHeld} when {@link #hold} is set), the lines kept. Test helper (nav step 7).
 */
final class FakePriceWorld implements PriceWorld {
    final BlockView view;
    NavConfig config = NavConfig.defaults();
    BodySnapshot body = BodySnapshot.bare();
    BetterBlockPos start;
    Object world = "world";
    java.nio.file.Path routesDir;
    boolean hold;
    final ArrayDeque<Runnable> held = new ArrayDeque<>();
    final List<String> debug = new ArrayList<>(), said = new ArrayList<>();
    int contexts;

    FakePriceWorld(BlockView view, BetterBlockPos start) {
        this.view = view;
        this.start = start;
    }

    @Override public boolean onGameThread() { return true; }
    @Override public BetterBlockPos start() { return start; }
    @Override public BetterBlockPos feet() { return start; }
    @Override public Object world() { return world; }
    @Override public BlockView view() { return view; }
    @Override public BlockRules rules() { return new BlockRules(config); }
    @Override public java.nio.file.Path routesDir() { return routesDir; }
    @Override public void debug(String line) { debug.add(line); }
    @Override public void say(String line) { said.add(line); }

    @Override
    public Priced context() {
        contexts++;
        SearchContext c = new SearchContext(config, rules(), view, body, null);
        return new Priced(c, PriceStamp.of(c, body));
    }

    @Override
    public PathMemory.Stamp stampNow() {
        return context().stamp();
    }

    @Override
    public Executor executor() {
        return r -> {
            if (hold) held.add(r);
            else r.run();
        };
    }

    /** Runs the held searches (they land at the next tick). */
    void runHeld() {
        while (!held.isEmpty()) held.poll().run();
    }
}
