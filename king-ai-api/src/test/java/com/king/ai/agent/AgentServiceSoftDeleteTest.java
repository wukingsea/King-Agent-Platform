package com.king.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.agent.entity.Agent;
import com.king.ai.agent.mapper.AgentKnowledgeMapper;
import com.king.ai.agent.mapper.AgentMapper;
import com.king.ai.agent.mapper.AgentMcpMapper;
import com.king.ai.agent.mapper.AgentSkillMapper;
import com.king.ai.agent.mapper.AgentVersionMapper;
import com.king.ai.common.BizException;
import com.king.ai.knowledge.mapper.KnowledgeBaseMapper;
import com.king.ai.mcp.mapper.McpServerMapper;
import com.king.ai.model.ModelRuntimeService;
import com.king.ai.model.mapper.SupplierModelMapper;
import com.king.ai.skill.mapper.SkillMapper;
import com.king.ai.system.mapper.SpaceMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceSoftDeleteTest {

    @Mock
    private AgentMapper agentMapper;
    @Mock
    private AgentVersionMapper agentVersionMapper;
    @Mock
    private AgentKnowledgeMapper agentKnowledgeMapper;
    @Mock
    private AgentMcpMapper agentMcpMapper;
    @Mock
    private AgentSkillMapper agentSkillMapper;
    @Mock
    private SupplierModelMapper modelMapper;
    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private McpServerMapper mcpServerMapper;
    @Mock
    private SkillMapper skillMapper;
    @Mock
    private SpaceMapper spaceMapper;
    @Mock
    private ModelRuntimeService modelRuntimeService;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private AgentService agentService;

    @Test
    void deleteArchivesAgentEvenWhenSessionsReferenceIt() {
        Agent agent = agent(0);
        when(agentMapper.selectById(9L)).thenReturn(agent);

        agentService.delete(2L, 9L);

        verify(agentKnowledgeMapper).delete(any());
        verify(agentMcpMapper).delete(any());
        verify(agentSkillMapper).delete(any());
        verify(agentMapper).softDeleteById(9L);
    }

    @Test
    void publishedAgentInArchivedSpaceIsNotPubliclyResolvable() {
        Agent agent = agent(1);
        agent.setStatus(0);
        agent.setPublicId("pub-archived");
        when(agentMapper.selectOne(any())).thenReturn(agent);
        when(spaceMapper.selectById(2L)).thenReturn(null);

        assertThatThrownBy(() -> agentService.requirePublished("pub-archived"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void deleteStillRejectsPublishedAgent() {
        when(agentMapper.selectById(9L)).thenReturn(agent(1));

        assertThatThrownBy(() -> agentService.delete(2L, 9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("下架");

        verify(agentMapper, never()).softDeleteById(9L);
    }

    private static Agent agent(int publishStatus) {
        Agent agent = new Agent();
        agent.setId(9L);
        agent.setSpaceId(2L);
        agent.setPublishStatus(publishStatus);
        return agent;
    }
}
