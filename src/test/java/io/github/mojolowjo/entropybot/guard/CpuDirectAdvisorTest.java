package io.github.mojolowjo.entropybot.guard;

import baritone.pathing.movement.CostAdvisor;
import io.github.mojolowjo.entropybot.baritone.CostAdvisorHook;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CPU item 5: CostAdvisorHook.Direct implements the fork's CostAdvisor (no Proxy) and answers what the static hook answers:
 * Direct forwards every call to the same static methods the Proxy dispatched to. CostAdvisorHook links Minecraft classes
 * (Level, Player, BlockState) the JUnit classpath lacks, so its answers for lava, MINE and water are covered by the rule
 * tests (Z77, DrownRisk, LavaNear), not called here.
 */
class CpuDirectAdvisorTest {
    static final String OW = "minecraft:overworld";
    static final Policy AREAS = Policy.parse("{\"areas\":[{\"name\":\"neighbour\",\"type\":\"keep-out\",\"x1\":0,\"z1\":0,\"x2\":10,\"z2\":10}]}");

    @Test
    void theHookIsACostAdvisor() {
        CostAdvisor a = new CostAdvisorHook.Direct();
        assertFalse(java.lang.reflect.Proxy.isProxyClass(a.getClass()), "the fork's advisorKind() says direct");
    }

    @Test
    void theKeepOutRuleTheHookForwardsToStillRefuses() {
        // Direct.moveMultiplier forwards to CostAdvisorHook.moveMultiplier, which is MoveRules.moveMultiplier (the hook class
        // itself cannot load in JUnit: it links Minecraft classes)
        assertTrue(MoveRules.moveMultiplier(AREAS, OW, new int[]{500, 64, 500}, 5, 64, 5, 10.7) >= MoveRules.COST_INF, "keep-out: impassable");
        assertTrue(Double.isNaN(MoveRules.moveMultiplier(AREAS, OW, new int[]{500, 64, 500}, 500, 64, 500, 10.7)), "outside: no opinion");
    }
}
