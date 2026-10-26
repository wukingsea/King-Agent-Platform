package com.king.ai.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_agent_knowledge")
public class AgentKnowledge extends BaseEntity {

    private Long agentId;
    private Long knowledgeBaseId;
}
