package com.king.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.agent.dto.AgentSaveRequest;
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
import com.king.ai.model.entity.SupplierModel;
import com.king.ai.model.mapper.SupplierModelMapper;
import com.king.ai.skill.mapper.SkillMapper;
import com.king.ai.system.mapper.SpaceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceContextWindowTest {

    private AgentMapper agentMapper;
    private SupplierModelMapper modelMapper;
    private LicenseService licenseService;
    private ModelRuntimeService modelRuntimeService;
    private AgentService service;

    @BeforeEach
    void setUp() {
        agentMapper = mock(AgentMapper.class);
        AgentKnowledgeMapper knowledge = mock(AgentKnowledgeMapper.class);
        AgentMcpMapper mcp = mock(AgentMcpMapper.class);
        AgentSkillMapper skill = mock(AgentSkillMapper.class);
        modelMapper = mock(SupplierModelMapper.class);
        licenseService = mock(LicenseService.class);
        modelRuntimeService = mock(ModelRuntimeService.class);
        when(knowledge.selectList(any())).thenReturn(List.of());
        when(mcp.selectList(any())).thenReturn(List.of());
        when(skill.selectList(any())).thenReturn(List.of());
        when(modelMapper.selectById(any())).thenReturn(new SupplierModel());
        when(modelRuntimeService.resolveChat(any(), any())).thenReturn(mock(ModelRuntimeService.ResolvedModel.class));
        when(agentMapper.insert((Agent) any(Agent.class))).thenReturn(1);
        when(agentMapper.updateById((Agent) any(Agent.class))).thenReturn(1);
        when(agentMapper.selectById(any())).thenReturn(new Agent());
        service = new AgentService(agentMapper, mock(AgentVersionMapper.class), knowledge,
                mcp, skill, modelMapper, mock(KnowledgeBaseMapper.class),
                mock(McpServerMapper.class), mock(SkillMapper.class), mock(SpaceMapper.class),
                modelRuntimeService, new ObjectMapper(), new KingAiProperties(),
                licenseService);
    }

    @Test
    void createPersistsHistoryWindowFromRequest() {
        AgentView view = service.create(2L, request(30));

        assertThat(view.historyWindow()).isEqualTo(30);
        verify(agentMapper).insert((Agent) argThat((Agent a) -> Integer.valueOf(30).equals(a.getHistoryWindow())));
    }

    @Test
    void createDefaultsNullHistoryWindowWhenOmitted() {
        AgentView view = service.create(2L, request(null));

        assertThat(view.historyWindow()).isNull();
        verify(agentMapper).insert((Agent) argThat((Agent a) -> a.getHistoryWindow() == null));
    }

    @Test
    void updatePersistsHistoryWindow() {
        Agent existing = new Agent();
        existing.setId(9L);
        existing.setSpaceId(2L);
        when(agentMapper.selectById(9L)).thenReturn(existing);

        service.update(2L, 9L, request(10));

        verify(agentMapper).updateById((Agent) argThat((Agent a) -> Integer.valueOf(10).equals(a.getHistoryWindow())));
    }

    @Test
    void snapshotOfCapturesHistoryWindowAndRoundTripsThroughJson() {
        Agent agent = new Agent();
        agent.setName("a");
        agent.setIntro("");
        agent.setModelId(5L);
        agent.setSystemPrompt("p");
        agent.setTemperature(new BigDecimal("0.7"));
        agent.setMaxTokens(2048);
        agent.setHistoryWindow(15);
        when(modelMapper.selectById(5L)).thenReturn(null);

        AgentSnapshot snapshot = service.snapshotOf(agent);
        assertThat(snapshot.historyWindow()).isEqualTo(15);

        String json;
        AgentSnapshot restored;
        try {
            json = new ObjectMapper().writeValueAsString(snapshot);
            restored = new ObjectMapper().readValue(json, AgentSnapshot.class);
        } catch (Exception ex) {
            throw new AssertionError("snapshot json round-trip failed", ex);
        }
        assertThat(restored.historyWindow()).isEqualTo(15);
    }

    private AgentSaveRequest request(Integer historyWindow) {
        return new AgentSaveRequest("agent", "", 5L, "prompt",
                new BigDecimal("0.7"), 2048, 0,
                List.of(), List.of(), List.of(), List.of(), "WRITE", null, historyWindow);
    }
}
