package com.king.ai.chat;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryToolApprovalRegistryTest {

    private final ToolApprovalRegistry registry = new MemoryToolApprovalRegistry();

    @Test
    void ownerCanApproveOwnPendingCall() {
        CompletableFuture<Boolean> decision = registry.register(
                "req-1", "call-1", 7L, "user:42", "mcp__github__create_issue", "MCP", "{}");

        ToolApprovalRegistry.Pending pending = registry.decide(
                "req-1", "call-1", 7L, "user:42", false, true);

        assertThat(pending).isNotNull();
        assertThat(pending.toolName()).isEqualTo("mcp__github__create_issue");
        assertThat(pending.source()).isEqualTo("MCP");
        assertThat(decision).isCompletedWithValue(true);
    }

    @Test
    void otherMemberCannotDecideWithoutManagePermission() {
        CompletableFuture<Boolean> decision = registry.register(
                "req-1", "call-1", 7L, "user:42", "tool", "MCP", "{}");

        assertThat(registry.decide("req-1", "call-1", 7L, "user:99", false, true)).isNull();
        assertThat(decision).isNotDone();
    }

    @Test
    void managerCanDecideForAnotherMemberInSameSpace() {
        CompletableFuture<Boolean> decision = registry.register(
                "req-1", "call-1", 7L, "user:42", "tool", "MCP", "{}");

        assertThat(registry.decide("req-1", "call-1", 7L, "user:1", true, false)).isNotNull();
        assertThat(decision).isCompletedWithValue(false);
    }

    @Test
    void callFromAnotherSpaceCannotBeDecided() {
        CompletableFuture<Boolean> decision = registry.register(
                "req-1", "call-1", 7L, "user:42", "tool", "MCP", "{}");

        assertThat(registry.decide("req-1", "call-1", 8L, "user:42", true, true)).isNull();
        assertThat(decision).isNotDone();
    }

    @Test
    void secondDecisionIsRejectedAfterCompletion() {
        registry.register("req-1", "call-1", 7L, "user:42", "tool", "MCP", "{}");

        assertThat(registry.decide("req-1", "call-1", 7L, "user:42", false, true)).isNotNull();
        assertThat(registry.decide("req-1", "call-1", 7L, "user:42", false, false)).isNull();
    }

    @Test
    void removingARequestReleasesPendingCalls() {
        registry.register("req-1", "call-1", 7L, "user:42", "tool", "MCP", "{}");

        registry.removeRequest("req-1");

        assertThat(registry.decide("req-1", "call-1", 7L, "user:42", false, true)).isNull();
    }
}
