package com.king.ai.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_agent_version")
public class AgentVersion extends BaseEntity {

    private Long agentId;
    private Long spaceId;
    private Integer versionNo;
    private String configJson;
    private String status;
    private Long requestedBy;
    private LocalDateTime requestedAt;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private String reviewComment;
    private Integer canaryPercent;
    private LocalDateTime publishedAt;
    private Integer evalTotal;
    private Integer evalPassed;
    private BigDecimal evalScore;
    private String evalSummary;
    private LocalDateTime evalRunAt;
    private String configHash;
}
