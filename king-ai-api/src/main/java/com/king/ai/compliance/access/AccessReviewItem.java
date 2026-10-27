package com.king.ai.compliance.access;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_access_review_item")
public class AccessReviewItem extends BaseEntity {

    private Long reviewId;
    private Long spaceId;
    private String subjectType;
    private Long subjectId;
    private String subjectName;
    private String role;
    private LocalDateTime lastActivityAt;
    private String flags;
    private String decision;
    private Long decidedBy;
    private LocalDateTime decidedAt;
    private String note;

    @Version
    private Integer version;
}
