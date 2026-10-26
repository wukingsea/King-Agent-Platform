package com.king.ai.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_knowledge_doc")
public class KnowledgeDoc extends BaseEntity {

    private Long spaceId;
    private Long knowledgeBaseId;
    private String name;
    private String sourceType;
    private String content;
    private Integer chunkCount;
    private String status;
    private String errorMessage;
    private String allowedRoles;

    @Version
    private Integer version;
}
