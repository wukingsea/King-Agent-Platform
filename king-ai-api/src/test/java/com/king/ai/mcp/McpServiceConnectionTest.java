package com.king.ai.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.agent.mapper.AgentMcpMapper;
import com.king.ai.common.CryptoService;
import com.king.ai.common.UpstreamErrors;
import com.king.ai.common.web.OutboundUrlGuard;
import com.king.ai.mcp.dto.McpTestResult;
import com.king.ai.mcp.entity.McpServer;
import com.king.ai.mcp.mapper.McpServerMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpServiceConnectionTest {

    @Test
    void upstreamFailureTextIsNotReturnedToTheClient() {
        McpServerMapper serverMapper = mock(McpServerMapper.class);
        McpClientFactory clientFactory = mock(McpClientFactory.class);
        McpServer server = new McpServer();
        server.setId(9L);
        server.setSpaceId(1L);
        when(serverMapper.selectById(9L)).thenReturn(server);
        when(clientFactory.createFresh(server))
                .thenThrow(new RuntimeException("401 for Bearer sk-live-secret"));
        McpService service = new McpService(serverMapper, mock(AgentMcpMapper.class), clientFactory,
                mock(CryptoService.class), mock(OutboundUrlGuard.class), new ObjectMapper());

        McpTestResult result = service.test(1L, 9L);

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).isEqualTo(UpstreamErrors.CONNECTION_TEST_FAILED);
        assertThat(result.message()).doesNotContain("sk-live-secret");
    }
}
