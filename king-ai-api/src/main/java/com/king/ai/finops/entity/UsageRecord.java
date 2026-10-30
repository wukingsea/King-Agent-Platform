package com.king.ai.finops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("king_ai_usage_ledger")
public class UsageRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long spaceId;
    private Long agentId;
    private Long sessionId;
    private Long messageId;
    private String model;
    private String sourceType;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Long costMicros;
    private String currency;
    private String status;
    private LocalDateTime createdAt;
}
