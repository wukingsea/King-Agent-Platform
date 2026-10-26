package com.king.ai.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("king_ai_audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long spaceId;
    private String actorType;
    private Long actorId;
    private String actorName;
    private String action;
    private String resourceType;
    private String resourceId;
    private String status;
    private String detail;
    private String ip;
    private String userAgent;
    private String requestId;
    private LocalDateTime createdAt;
}
