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
 * Entropy Bot 2026-11-27: the fork's pricing settings (Baritone Settings priceAsk*, routeStore*, pathCost*,
 * simplifyUnloadedYCoord) as one value, the defaults the fork's.
 */

package io.github.mojolowjo.entropybot.nav.price;

/**
 * What the pricing reads besides {@link io.github.mojolowjo.entropybot.nav.config.NavConfig}: the price asks' own search
 * timeouts (the walk's own searches keep NavConfig's), how near two goals must be to share an answer, the route store's
 * size and reach, the synchronous estimate's budget, and Baritone's "goal in an unloaded chunk: search to its x z". Pure
 * (JUnit: PriceQueueTest). Loader notes: none.
 *
 * @param primaryTimeoutMs   priceAskPrimaryTimeoutMS: a segment search with a path stops here
 * @param failureTimeoutMs   priceAskFailureTimeoutMS: the hard stop
 * @param shareRadius        priceAskShareRadius: same-kind goals within this many blocks on each axis share (0: never)
 * @param routeStoreReach    routeStoreReach: a stored route is used when the feet are within this many blocks of a cell
 * @param routeStoreMax      routeStoreMax: routes kept per world and dimension
 * @param routeStorePerGoalCell routeStorePerGoalCell: routes kept per goal cell
 * @param costMaxNodes       pathCostMaxNodes: the synchronous estimate's node budget
 * @param costMaxMs          pathCostMaxMs: the synchronous estimate's time budget
 * @param simplifyUnloadedYCoord a goal whose chunk is not loaded is searched as its x z (Baritone's createPathfinder)
 */
public record PriceConfig(long primaryTimeoutMs, long failureTimeoutMs, int shareRadius, int routeStoreReach, int routeStoreMax,
                          int routeStorePerGoalCell, int costMaxNodes, long costMaxMs, boolean simplifyUnloadedYCoord) {

    /** The fork's defaults (Settings.java at dd8235b7). */
    public static PriceConfig defaults() {
        return new PriceConfig(1000L, 2000L, 2, 8, 64, 1, 20000, 40L, true);
    }
}
