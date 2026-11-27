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
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>. *
 * Modified for Entropy Bot 2026-11-27: package moved; holds our Path and the fork's stop reason; an EXCEPTION result
 * carries the exception's text
 */

package io.github.mojolowjo.entropybot.nav.search;

import java.util.Objects;
import java.util.Optional;

/**
 * The result of one search: what kind of answer, the path (if any), why the search stopped, and for EXCEPTION the
 * exception as text (never a silent failure).
 *
 * <p>Pure (JUnit: SearchMatchForkTest). Loader notes: none.
 */
public class PathCalculationResult {

    private final Path path;
    private final Type type;
    private final Stop stop;
    private final String failure;

    public PathCalculationResult(Type type, Path path, Stop stop, String failure) {
        Objects.requireNonNull(type);
        this.path = path;
        this.type = type;
        this.stop = stop;
        this.failure = failure;
    }

    public final Optional<Path> getPath() {
        return Optional.ofNullable(this.path);
    }

    public final Type getType() {
        return this.type;
    }

    /** Why the search stopped. */
    public final Stop stop() {
        return stop;
    }

    /** For EXCEPTION: the exception as text; else null. */
    public final String failure() {
        return failure;
    }

    public enum Type {
        SUCCESS_TO_GOAL,
        SUCCESS_SEGMENT,
        FAILURE,
        CANCELLATION,
        EXCEPTION,
    }
}
