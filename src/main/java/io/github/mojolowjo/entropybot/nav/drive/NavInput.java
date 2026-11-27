package io.github.mojolowjo.entropybot.nav.drive;

import net.minecraft.client.player.Input;

/**
 * The movement input while our driver holds keys: Baritone's PlayerMovementInput, reading {@link Keys} instead of the
 * keyboard. {@link DriveLive} puts it in {@code LocalPlayer.input} while a key is down and the keyboard's input back
 * after. Pure (JUnit: KeysTest covers the sums; this class only copies them). Loader notes: vanilla 1.21.1
 * {@code Input.tick(boolean, float)} and its public fields (1.21.2 replaced them with a key-press record); Fabric the same.
 */
public final class NavInput extends Input {
    private final Keys keys;

    NavInput(Keys keys) {
        this.keys = keys;
    }

    @Override
    public void tick(boolean isSneaking, float sneakingSpeedMultiplier) {
        Keys.Impulse i = keys.impulse(sneakingSpeedMultiplier);
        this.up = i.up();
        this.down = i.down();
        this.left = i.leftKey();
        this.right = i.rightKey();
        this.jumping = i.jumping();
        this.shiftKeyDown = i.sneaking();
        this.forwardImpulse = i.forward();
        this.leftImpulse = i.left();
    }
}
