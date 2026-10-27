package com.king.ai.compliance.access;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_access_review")
public class AccessReview extends BaseEntity {

    private Long spaceId;
    private String status;
    private String summary;
    private Long generatedBy;
    private LocalDateTime generatedAt;
    private LocalDateTime completedAt;

    @Version
    private Integer version;
}
