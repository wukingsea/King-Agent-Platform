package com.king.ai.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_knowledge_index_job")
public class KnowledgeIndexJob extends BaseEntity {

    private Long docId;
    private String status;
    private Integer attempts;
    private LocalDateTime nextRunAt;
    private String lastError;
}
