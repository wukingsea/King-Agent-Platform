package com.king.ai.agent.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_agent")
public class Agent extends BaseEntity {

    private Long spaceId;
    private String name;
    private String intro;
    private Long modelId;
    private String systemPrompt;
    private BigDecimal temperature;
    private Integer maxTokens;
    private Integer publishStatus;
    private String publicId;
    private Integer status;
    private Integer publishedVersion;
    private String allowedTools;
    private String toolApprovalMode;
    private Long fallbackModelId;

    @Version
    private Integer version;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
