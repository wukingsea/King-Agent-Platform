package com.king.ai.mcp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.agent.entity.AgentMcp;
import com.king.ai.agent.mapper.AgentMcpMapper;
import com.king.ai.common.BizException;
import com.king.ai.common.CryptoService;
import com.king.ai.common.OptimisticLocks;
import com.king.ai.common.Texts;
import com.king.ai.common.UpstreamErrors;
import com.king.ai.common.web.OutboundUrlGuard;
import com.king.ai.mcp.dto.McpSaveRequest;
import com.king.ai.mcp.dto.McpTestResult;
import com.king.ai.mcp.dto.McpToolView;
import com.king.ai.mcp.dto.McpView;
import com.king.ai.mcp.entity.McpServer;
import com.king.ai.mcp.mapper.McpServerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class McpService {

    private final McpServerMapper mcpServerMapper;
    private final AgentMcpMapper agentMcpMapper;
    private final McpClientFactory clientFactory;
    private final CryptoService cryptoService;
    private final OutboundUrlGuard outboundUrlGuard;
    private final ObjectMapper objectMapper;

    public List<McpView> list(Long spaceId, String keyword) {
        LambdaQueryWrapper<McpServer> wrapper = new LambdaQueryWrapper<McpServer>()
                .eq(McpServer::getSpaceId, spaceId);
        if (!Texts.isBlank(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(McpServer::getName, kw).or().like(McpServer::getRemark, kw));
        }
        wrapper.orderByDesc(McpServer::getId);
        List<McpServer> servers = mcpServerMapper.selectList(wrapper);
        if (servers.isEmpty()) {
            return List.of();
        }
        List<Long> ids = servers.stream().map(McpServer::getId).toList();
        Map<Long, Long> counts = new HashMap<>();
        agentMcpMapper.selectList(new LambdaQueryWrapper<AgentMcp>()
                        .select(AgentMcp::getMcpId)
                        .in(AgentMcp::getMcpId, ids))
                .forEach(link -> counts.merge(link.getMcpId(), 1L, Long::sum));
        return servers.stream().map(server -> view(server, counts.getOrDefault(server.getId(), 0L))).toList();
    }

    public McpServer require(Long spaceId, Long id) {
        if (id == null) {
            throw BizException.badRequest("缺少 MCP 服务 ID");
        }
        McpServer server = mcpServerMapper.selectById(id);
        if (server == null || !Objects.equals(server.getSpaceId(), spaceId)) {
            throw BizException.notFound("MCP 服务不存在");
        }
        return server;
    }

    public McpView create(Long spaceId, McpSaveRequest request) {
        requireUniqueName(spaceId, request.name().trim(), null);
        McpServer server = new McpServer();
        server.setSpaceId(spaceId);
        apply(server, request);
        mcpServerMapper.insert(server);
        return view(server, 0);
    }

    public McpView update(Long spaceId, Long id, McpSaveRequest request) {
        McpServer server = require(spaceId, id);
        requireUniqueName(spaceId, request.name().trim(), id);
        apply(server, request);
        OptimisticLocks.requireUpdated(mcpServerMapper.updateById(server), "MCP 服务");
        clientFactory.invalidate(id);
        long agents = agentMcpMapper.selectCount(new LambdaQueryWrapper<AgentMcp>().eq(AgentMcp::getMcpId, id));
        return view(server, agents);
    }

    public void delete(Long spaceId, Long id) {
        require(spaceId, id);
        long agents = agentMcpMapper.selectCount(new LambdaQueryWrapper<AgentMcp>().eq(AgentMcp::getMcpId, id));
        if (agents > 0) {
            throw BizException.conflict("MCP 服务已绑定 " + agents + " 个智能体，请先解除绑定");
        }
        mcpServerMapper.deleteById(id);
        clientFactory.invalidate(id);
    }

    public McpTestResult test(Long spaceId, Long id) {
        McpServer server = require(spaceId, id);
        try {
            List<McpClient.RemoteTool> tools = clientFactory.createFresh(server).listTools();
            List<McpToolView> views = tools.stream()
                    .map(tool -> new McpToolView(tool.name(), tool.description()))
                    .toList();
            return new McpTestResult(true, "连接成功，发现 " + views.size() + " 个工具", views);
        } catch (RuntimeException ex) {
            // Upstream text may contain headers or credentials; only the server log sees it.
            log.warn("MCP connection test failed: id={}, reason={}", id, ex.getMessage());
            return new McpTestResult(false, UpstreamErrors.CONNECTION_TEST_FAILED, List.of());
        }
    }

    public McpClient client(McpServer server) {
        return clientFactory.create(server);
    }

    /**
     * Drops the cached transport session after a failure so the next attempt
     * re-initializes instead of retrying against an expired MCP session.
     */
    public void evict(McpServer server) {
        if (server != null) {
            clientFactory.invalidate(server.getId());
        }
    }

    public McpView view(McpServer server) {
        long agents = agentMcpMapper.selectCount(new LambdaQueryWrapper<AgentMcp>()
                .eq(AgentMcp::getMcpId, server.getId()));
        return view(server, agents);
    }

    private McpView view(McpServer server, long agentCount) {
        return new McpView(server.getId(), server.getSpaceId(), server.getName(), server.getTransport(),
                server.getUrl(), !Texts.isBlank(server.getHeaders()),
                Texts.blankToDefault(server.getAuthType(), McpOAuthService.AUTH_NONE),
                server.getOauthTokenUrl(), server.getOauthClientId(), server.getOauthScopes(),
                !Texts.isBlank(server.getOauthClientSecret()), !Texts.isBlank(server.getOauthAccessToken()),
                server.getOauthExpiresAt(), server.getStatus(), server.getRemark(),
                agentCount, server.getCreatedAt());
    }

    private void apply(McpServer server, McpSaveRequest request) {
        String transport = Texts.blankToDefault(request.transport(), "HTTP").trim().toUpperCase(Locale.ROOT);
        if ("STREAMABLE_HTTP".equals(transport)) {
            transport = "HTTP";
        }
        if ("SSE".equals(transport)) {
            throw BizException.badRequest("当前版本仅支持 Streamable HTTP 传输方式（SSE 已停止支持）");
        }
        if (!"HTTP".equals(transport)) {
            throw BizException.badRequest("传输方式不合法");
        }
        String url = request.url().trim();
        String violation = outboundUrlGuard.violation(url);
        if (violation != null) {
            throw BizException.badRequest(violation);
        }
        server.setName(request.name().trim());
        server.setTransport(transport);
        server.setUrl(Texts.trimTo(url, 1000));
        if (request.headers() != null) {
            server.setHeaders(encryptHeaders(request.headers()));
        }
        applyAuth(server, request);
        server.setStatus(request.status() == null || request.status() == 0 ? 0 : 1);
        server.setRemark(Texts.trimTo(request.remark(), 500));
    }

    private void applyAuth(McpServer server, McpSaveRequest request) {
        String authType;
        try {
            authType = McpOAuthService.normalizeAuthType(request.authType());
        } catch (McpException ex) {
            throw BizException.badRequest(ex.getMessage());
        }
        if (McpOAuthService.AUTH_NONE.equals(authType)) {
            server.setAuthType(authType);
            server.setOauthAccessToken(null);
            server.setOauthRefreshToken(null);
            server.setOauthExpiresAt(null);
            return;
        }
        String tokenUrl = Texts.blankToDefault(request.oauthTokenUrl(), "").trim();
        if (tokenUrl.isEmpty()) {
            throw BizException.badRequest("启用 OAuth 2.0 时必须填写 Token 地址");
        }
        String violation = outboundUrlGuard.violation(tokenUrl);
        if (violation != null) {
            throw BizException.badRequest(violation);
        }
        if (Texts.isBlank(request.oauthClientId())) {
            throw BizException.badRequest("启用 OAuth 2.0 时必须填写 Client ID");
        }
        String scopes = Texts.trimTo(request.oauthScopes(), 500);
        boolean configChanged = !tokenUrl.equals(server.getOauthTokenUrl())
                || !request.oauthClientId().trim().equals(server.getOauthClientId())
                || !java.util.Objects.equals(scopes, server.getOauthScopes());
        server.setAuthType(authType);
        server.setOauthTokenUrl(Texts.trimTo(tokenUrl, 1000));
        server.setOauthClientId(request.oauthClientId().trim());
        server.setOauthScopes(scopes);
        if (!Texts.isBlank(request.oauthClientSecret())) {
            server.setOauthClientSecret(cryptoService.encrypt(request.oauthClientSecret().trim()));
            configChanged = true;
        } else if (server.getId() == null) {
            throw BizException.badRequest("启用 OAuth 2.0 时必须填写 Client Secret");
        }
        if (configChanged) {
            server.setOauthAccessToken(null);
            server.setOauthRefreshToken(null);
            server.setOauthExpiresAt(null);
        }
    }

    private String encryptHeaders(String headers) {
        String normalized = normalizeHeaders(headers);
        return Texts.isBlank(normalized) ? "" : cryptoService.encrypt(normalized);
    }

    private String normalizeHeaders(String headers) {
        try {
            Map<String, String> parsed = clientFactory.parseHeaders(headers);
            if (parsed.isEmpty()) {
                return null;
            }
            return objectMapper.writeValueAsString(parsed);
        } catch (McpException ex) {
            throw BizException.badRequest(ex.getMessage());
        } catch (Exception ex) {
            throw BizException.badRequest("自定义请求头不是合法 JSON");
        }
    }

    private void requireUniqueName(Long spaceId, String name, Long excludeId) {
        LambdaQueryWrapper<McpServer> wrapper = new LambdaQueryWrapper<McpServer>()
                .eq(McpServer::getSpaceId, spaceId)
                .eq(McpServer::getName, name);
        if (excludeId != null) {
            wrapper.ne(McpServer::getId, excludeId);
        }
        if (mcpServerMapper.selectCount(wrapper) > 0) {
            throw BizException.conflict("当前空间已存在同名 MCP 服务");
        }
    }
}
