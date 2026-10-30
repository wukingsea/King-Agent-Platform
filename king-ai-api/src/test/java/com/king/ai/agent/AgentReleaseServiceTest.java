package com.king.ai.agent;

import com.king.ai.agent.entity.Agent;
import com.king.ai.agent.entity.AgentEvalCase;
import com.king.ai.agent.entity.AgentVersion;
import com.king.ai.agent.mapper.AgentEvalCaseMapper;
import com.king.ai.agent.mapper.AgentVersionMapper;
import com.king.ai.agent.release.ReleaseState;
import com.king.ai.audit.AuditLogService;
import com.king.ai.common.BizException;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.UserPrincipal;
import com.king.ai.config.KingAiProperties;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentReleaseServiceTest {

    @Mock
    private AgentService agentService;
    @Mock
    private AgentVersionMapper versionMapper;
    @Mock
    private AgentEvalCaseMapper evalCaseMapper;
    @Mock
    private EvaluationService evaluationService;
    @Mock
    private AuditLogService auditLogService;

    private AgentReleaseService service;
    private Agent agent;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentVersion.class);
        TableInfoHelper.initTableInfo(assistant, AgentEvalCase.class);
        service = new AgentReleaseService(agentService, versionMapper, evalCaseMapper,
                evaluationService, auditLogService, new KingAiProperties());
        agent = new Agent();
        agent.setId(11L);
        agent.setSpaceId(2L);
        agent.setPublishedVersion(3);
        when(agentService.require(2L, 11L)).thenReturn(agent);
        RequestContext.set(new UserPrincipal(7L, "admin", "Admin", "USER", 2L,
                "OWNER", false));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void approverCannotApproveTheirOwnSubmission() {
        AgentVersion version = version(4, ReleaseState.PENDING_REVIEW);
        version.setRequestedBy(7L);
        when(agentService.requireVersion(2L, 11L, 4)).thenReturn(version);

        assertThatThrownBy(() -> service.approve(2L, 11L, 4, "ok"))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.getCode()).isEqualTo(403));
    }

    @Test
    void approvalRequiresAnEvaluationWhenCasesAreEnabled() {
        RequestContext.set(new UserPrincipal(8L, "reviewer", "Reviewer", "USER", 2L,
                "ADMIN", false));
        AgentVersion version = version(4, ReleaseState.PENDING_REVIEW);
        version.setRequestedBy(7L);
        when(agentService.requireVersion(2L, 11L, 4)).thenReturn(version);
        when(evalCaseMapper.selectList(any())).thenReturn(List.of(new AgentEvalCase()));

        assertThatThrownBy(() -> service.approve(2L, 11L, 4, "ok"))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                                .contains("评测"));
    }

    @Test
    void promoteOnlyAcceptsApprovedOrCanaryVersions() {
        when(agentService.requireVersion(2L, 11L, 4))
                .thenReturn(version(4, ReleaseState.PENDING_REVIEW));

        assertThatThrownBy(() -> service.promote(2L, 11L, 4))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.getCode()).isEqualTo(409));
    }

    @Test
    void onlyOneCanaryVersionIsAllowedPerAgent() {
        AgentVersion version = version(4, ReleaseState.APPROVED);
        when(agentService.requireVersion(2L, 11L, 4)).thenReturn(version);
        when(versionMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.canary(2L, 11L, 4, 10))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                                .contains("灰度"));
    }

    @Test
    void rejectionNeedsAReason() {
        when(agentService.requireVersion(2L, 11L, 4))
                .thenReturn(version(4, ReleaseState.PENDING_REVIEW));

        assertThatThrownBy(() -> service.reject(2L, 11L, 4, "  "))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                                .contains("驳回原因"));
    }

    private static AgentVersion version(int versionNo, ReleaseState state) {
        AgentVersion version = new AgentVersion();
        version.setId((long) versionNo);
        version.setAgentId(11L);
        version.setSpaceId(2L);
        version.setVersionNo(versionNo);
        version.setStatus(state.name());
        return version;
    }
}
