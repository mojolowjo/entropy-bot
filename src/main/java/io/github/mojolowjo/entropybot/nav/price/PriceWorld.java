/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Entropy Bot 2026-11-27: what the fork's PricedPath read from Baritone (PathingBehavior.pathStart, ctx.world(),
 * CalculationContext under the walk lease, Baritone's executor, WorldData's folder) as one interface, so the pricing
 * runs in JUnit on a fake world and in the game on the live one.
 */

package io.github.mojolowjo.entropybot.nav.price;

import io.github.mojolowjo.entropybot.nav.rules.BlockRules;
import io.github.mojolowjo.entropybot.nav.rules.SearchContext;
import io.github.mojolowjo.entropybot.nav.util.BetterBlockPos;
import io.github.mojolowjo.entropybot.nav.world.BlockView;

import java.util.concurrent.Executor;

/**
 * The pricing's view of the game. Game-thread calls but {@link #executor}'s work. Pure interface (JUnit: a fake one in
 * PriceQueueTest). Loader notes: the live one (later, with the live block view) reads the client level and the player.
 */
public interface PriceWorld {

    /** A search's context and the stamp of what it prices with, read together (the fork's WalkContext). */
    record Priced(SearchContext context, PathMemory.Stamp stamp) {}

    /** True on the game thread (the fork: minecraft().isSameThread()). */
    boolean onGameThread();

    /** Where a search starts now (Baritone's PathingBehavior.pathStart); null: no player. */
    BetterBlockPos start();

    /** The player's feet (the A*'s real start when within one block of {@link #start}, as createPathfinder); null: none. */
    BetterBlockPos feet();

    /** The world now, compared by identity (a new world drops every kept answer); null: none. */
    Object world();

    /** A fresh context for a search (another thread may run it) and its stamp. */
    Priced context();

    /** The stamp now, for comparing a kept answer (may build a context; read when the memory compares). */
    PathMemory.Stamp stampNow();

    /** The block view the cells are read from now (the same one a fresh context would get). */
    BlockView view();

    /** The block rules a fresh context would get (the cells are classified with them). */
    BlockRules rules();

    /** Runs the A* off the game thread (a test's may run it inline). */
    Executor executor();

    /** This world and dimension's folder for the route store; null: none. */
    java.nio.file.Path routesDir();

    /** A line for the debug log (the fork's logDebug). */
    void debug(String line);

    /** A line the owner reads (the fork's logDirect). */
    void say(String line);
}
