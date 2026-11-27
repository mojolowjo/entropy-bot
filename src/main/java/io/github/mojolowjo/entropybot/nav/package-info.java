/**
 * Our own pathing system, lifted from Baritone v1.11.3 and our fork, step by step
 * ({@code ..\minecraft-bot\to-do\nav-steps-1-6.md}; the whole plan and the class-by-class reference are in the
 * project's research files {@code baritone-extraction-plan.md} and {@code baritone-classes/}). Nothing in the mod
 * calls this package yet: Baritone still drives.
 *
 * <p>Licence: files lifted from Baritone keep its LGPL-3 header and say what was changed and when, under it
 * (LGPL-3 / GPL-3 section 5a). The mod is LGPL-3 too.
 *
 * <p>Sub-packages: {@code util} (positions, rotations, small maths), {@code cost} (the tick price list and move
 * results), {@code world} (block kinds and, from step 3, the block view). Pure: game value types only (BlockPos,
 * Vec3, Mth, WorldBorder), no Level, no player, no loader API. JUnit: the {@code nav} tests. Loader notes: none
 * (Mojang names, common side; the same classes exist on Fabric).
 */
package io.github.mojolowjo.entropybot.nav;
