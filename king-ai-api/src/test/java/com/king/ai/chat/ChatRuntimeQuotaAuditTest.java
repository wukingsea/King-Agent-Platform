package com.king.ai.chat;

import com.king.ai.agent.AgentRuntime;
import com.king.ai.audit.AuditLogService;
import com.king.ai.common.BizException;
import com.king.ai.compliance.ChatInputCompliance;
import com.king.ai.compliance.ComplianceSettings;
import com.king.ai.compliance.ComplianceSettingsService;
import com.king.ai.config.KingAiProperties;
import com.king.ai.finops.UsageLedger;
import com.king.ai.model.ModelRuntimeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatRuntimeQuotaAuditTest {

    @Mock
    private ChatInputCompliance chatInputCompliance;
    @Mock
    private ComplianceSettingsService complianceSettingsService;
    @Mock
    private ModelRuntimeService modelRuntimeService;
    @Mock
    private UsageLedger usageLedger;
    @Mock
    private AuditLogService auditLogService;
    @Spy
    private KingAiProperties properties = new KingAiProperties();

    @InjectMocks
    private ChatRuntime chatRuntime;

    @Test
    void quotaRejectionIsAuditedBeforeTheRequestFails() {
        long spaceId = 1L;
        long userId = 7L;
        ChatActor actor = ChatActor.user(userId, null, "alice", spaceId, "OWNER", true);
        AgentRuntime agent = new AgentRuntime(11L, spaceId, null, "agent", null, 9L,
                null, null, null, 0, null, null, null, null, null);

        when(complianceSettingsService.effective(spaceId)).thenReturn(mock(ComplianceSettings.class));
        when(chatInputCompliance.prepare(eq("hi"), any(), any()))
                .thenReturn(new ChatInputCompliance.PreparedInput("hi", 0, false));
        when(modelRuntimeService.resolveChat(spaceId, 9L))
                .thenReturn(mock(ModelRuntimeService.ResolvedModel.class));
        doThrow(BizException.tooManyRequests("空间本月 Token 配额已用尽"))
                .when(usageLedger).requireQuota(spaceId);

        assertThatThrownBy(() -> chatRuntime.start(actor, agent, null, "hi", "ADMIN"))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.getCode()).isEqualTo(429));

        verify(auditLogService).recordEvent(eq(spaceId), eq(userId), eq("alice"),
                eq("chat.quota_rejected"), eq("chat"), isNull(), eq(AuditLogService.STATUS_DENIED),
                eq("reason=monthly_token_quota"), isNull(), isNull(), isNull());
    }
}
