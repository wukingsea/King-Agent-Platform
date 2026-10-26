package com.king.ai.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_knowledge_chunk")
public class KnowledgeChunk extends BaseEntity {

    private Long spaceId;
    private Long knowledgeBaseId;
    private Long docId;
    private Integer chunkIndex;
    private String content;
    private Integer charCount;
}
