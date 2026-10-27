package com.king.ai.compliance.retention;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_legal_hold")
public class LegalHold extends BaseEntity {

    private Long spaceId;
    private String resourceType;
    private Long resourceId;
    private String reason;
    private LocalDateTime until;
    private LocalDateTime releasedAt;
    private Long releasedBy;

    @Version
    private Integer version;
}
