package com.king.ai.audit.dto;

import com.king.ai.audit.entity.AuditLog;

import java.time.LocalDateTime;

public record AuditLogView(
        Long id,
        Long spaceId,
        String actorType,
        Long actorId,
        String actorName,
        String action,
        String resourceType,
        String resourceId,
        String status,
        String detail,
        String ip,
        String userAgent,
        String requestId,
        LocalDateTime createdAt
) {

    public static AuditLogView of(AuditLog log) {
        return new AuditLogView(log.getId(), log.getSpaceId(), log.getActorType(), log.getActorId(),
                log.getActorName(), log.getAction(), log.getResourceType(), log.getResourceId(),
                log.getStatus(), log.getDetail(), log.getIp(), log.getUserAgent(), log.getRequestId(),
                log.getCreatedAt());
    }
}
