package com.king.ai.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_agent_eval_case")
public class AgentEvalCase extends BaseEntity {

    private Long spaceId;
    private Long agentId;
    private String name;
    private String prompt;
    /** Comma separated keywords; a case passes when all of them appear in the answer. */
    private String expectedKeywords;
    private Integer weight;
    private Integer status;
}
