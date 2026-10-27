package com.king.ai.knowledge;

import java.time.LocalDateTime;

public record QueueStats(long pending, long failed, LocalDateTime oldestPendingAt) {
}
