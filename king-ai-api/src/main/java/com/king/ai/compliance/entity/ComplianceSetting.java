package com.king.ai.compliance.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_compliance_setting")
public class ComplianceSetting extends BaseEntity {

    private Long spaceId;
    private Integer piiEnabled;
    private String moderationMode;
    private String moderationInputAction;
    private String moderationOutputAction;
    private String blocklist;
    private String piiCustomPatterns;

    @Version
    private Integer version;
}
