package com.king.ai.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_knowledge_base")
public class KnowledgeBase extends BaseEntity {

    private Long spaceId;
    private String name;
    private String description;
    private Long embeddingModelId;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Integer topK;
    private String vectorCollection;
    private Integer embeddingDim;
    private Integer status;

    @Version
    private Integer version;
}
