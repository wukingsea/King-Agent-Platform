package com.king.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.agent.dto.AgentView;
import com.king.ai.agent.entity.Agent;
import com.king.ai.agent.mapper.AgentKnowledgeMapper;
import com.king.ai.agent.mapper.AgentMapper;
import com.king.ai.agent.mapper.AgentMcpMapper;
import com.king.ai.agent.mapper.AgentSkillMapper;
import com.king.ai.agent.mapper.AgentVersionMapper;
import com.king.ai.config.KingAiProperties;
import com.king.ai.knowledge.mapper.KnowledgeBaseMapper;
import com.king.ai.license.LicenseService;
import com.king.ai.mcp.mapper.McpServerMapper;
import com.king.ai.model.ModelRuntimeService;
import com.king.ai.model.mapper.SupplierModelMapper;
import com.king.ai.skill.mapper.SkillMapper;
import com.king.ai.system.mapper.SpaceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentServicePromptVisibilityTest {

    private AgentMapper agentMapper;
    private AgentKnowledgeMapper agentKnowledgeMapper;
    private AgentMcpMapper agentMcpMapper;
    private AgentSkillMapper agentSkillMapper;
    private SupplierModelMapper modelMapper;
    private AgentService service;

    @BeforeEach
    void setUp() {
        agentMapper = mock(AgentMapper.class);
        agentKnowledgeMapper = mock(AgentKnowledgeMapper.class);
        agentMcpMapper = mock(AgentMcpMapper.class);
        agentSkillMapper = mock(AgentSkillMapper.class);
        modelMapper = mock(SupplierModelMapper.class);
        service = new AgentService(agentMapper, mock(AgentVersionMapper.class), agentKnowledgeMapper,
                agentMcpMapper, agentSkillMapper, modelMapper, mock(KnowledgeBaseMapper.class),
                mock(McpServerMapper.class), mock(SkillMapper.class), mock(SpaceMapper.class),
                mock(ModelRuntimeService.class), new ObjectMapper(), new KingAiProperties(),
                mock(LicenseService.class));
        when(agentKnowledgeMapper.selectList(any())).thenReturn(List.of());
        when(agentMcpMapper.selectList(any())).thenReturn(List.of());
        when(agentSkillMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void withheldPromptIsBlankForReadOnlyCallers() {
        when(agentMapper.selectById(9L)).thenReturn(agent());

        AgentView view = service.detail(2L, 9L, false);

        assertThat(view.systemPrompt()).isEmpty();
    }

    @Test
    void fullPromptIsReturnedToEditors() {
        when(agentMapper.selectById(9L)).thenReturn(agent());

        AgentView view = service.detail(2L, 9L, true);

        assertThat(view.systemPrompt()).isEqualTo("proprietary instructions");
    }

    private Agent agent() {
        Agent agent = new Agent();
        agent.setId(9L);
        agent.setSpaceId(2L);
        agent.setName("agent");
        agent.setModelId(5L);
        agent.setSystemPrompt("proprietary instructions");
        agent.setTemperature(new java.math.BigDecimal("0.7"));
        agent.setMaxTokens(2048);
        return agent;
    }
}
