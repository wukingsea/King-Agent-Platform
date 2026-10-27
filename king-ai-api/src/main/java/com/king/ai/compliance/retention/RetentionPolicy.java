package com.king.ai.compliance.retention;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_retention_policy")
public class RetentionPolicy extends BaseEntity {

    private Long spaceId;
    private String resourceType;
    private Integer retentionDays;
    private Integer enabled;
    private Integer archiveBeforeDelete;

    @Version
    private Integer version;
}
