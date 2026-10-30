package com.king.ai.agent.release;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CanaryRoutingTest {

    @Test
    void bucketIsStableForTheSameVisitorAndAgent() {
        int first = CanaryRouting.bucket("visitor-abc", 42L);
        int second = CanaryRouting.bucket("visitor-abc", 42L);

        assertThat(first).isEqualTo(second).isBetween(0, 99);
    }

    @Test
    void differentAgentsSpreadTheSameVisitor() {
        Set<Integer> buckets = new HashSet<>();
        for (long agentId = 1; agentId <= 20; agentId++) {
            buckets.add(CanaryRouting.bucket("visitor-abc", agentId));
        }

        assertThat(buckets).hasSizeGreaterThan(1);
    }

    @Test
    void selectionHonoursThePercentAndIsRoughlyEven() {
        int selected = 0;
        for (int i = 0; i < 1000; i++) {
            if (CanaryRouting.selected("visitor-" + i, 7L, 50)) {
                selected++;
            }
        }

        assertThat(selected).isBetween(400, 600);
        assertThat(CanaryRouting.selected("visitor-abc", 7L, 0)).isFalse();
        assertThat(CanaryRouting.selected(null, 7L, 50)).isFalse();
    }
}
