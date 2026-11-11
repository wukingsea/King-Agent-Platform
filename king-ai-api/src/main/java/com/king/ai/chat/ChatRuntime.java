package com.king.ai.chat;

import com.king.ai.agent.AgentBindings;
import com.king.ai.agent.AgentRuntime;
import com.king.ai.audit.AuditLogService;
import com.king.ai.chat.entity.ChatMessage;
import com.king.ai.chat.entity.ChatSession;
import com.king.ai.common.BizException;
import com.king.ai.common.Texts;
import com.king.ai.common.web.ClientIpResolver;
import com.king.ai.common.web.RequestIdFilter;
import com.king.ai.compliance.ChatInputCompliance;
import com.king.ai.compliance.ComplianceSettings;
import com.king.ai.compliance.ComplianceSettingsService;
import com.king.ai.compliance.ComplianceStreamGuard;
import com.king.ai.compliance.ContentModerationException;
import com.king.ai.compliance.ModerationContext;
import com.king.ai.compliance.ModerationScan;
import com.king.ai.compliance.ModerationService;
import com.king.ai.compliance.RedactionResult;
import com.king.ai.compliance.RedactionService;
import com.king.ai.config.KingAiProperties;
import com.king.ai.finops.UsageLedger;
import com.king.ai.knowledge.dto.RetrievedChunk;
import com.king.ai.llm.CancellationToken;
import com.king.ai.llm.LlmCancelledException;
import com.king.ai.llm.LlmException;
import com.king.ai.llm.LlmMessage;
import com.king.ai.llm.LlmResult;
import com.king.ai.llm.ToolCall;
import com.king.ai.llm.ToolDefinition;
import com.king.ai.model.ModelRuntimeService;
import com.king.ai.observability.ChatMetrics;
import com.king.ai.observability.ChatTracing;
import com.king.ai.observability.McpMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话主链路编排：鉴权后准入（审核/配额/并发/会话锁）→ SSE 会话 →
 * 上下文组装 → 多轮工具调用 → 落库与审计收尾。
 * 具体职责下沉到 {@link ChatContextAssembler}（上下文）、{@link ChatToolRuntime}
 * （工具）、{@link ChatLlmStreamer}（模型流式与重试）、{@link ChatProgressFlusher}
 * （增量落库）与 {@link ChatSseWriter}（事件输出）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRuntime {

    private static final long SSE_TIMEOUT_MS = 600_000L;
    private static final Set<String> MODERATION_CATEGORIES = Set.of(
            "BLOCKLIST", "SPAM", "FLOOD", "MODERATION_UNAVAILABLE", "EXTERNAL");

    private final ChatService chatService;
    private final ModelRuntimeService modelRuntimeService;
    private final ChatCancellationRegistry cancellationRegistry;
    private final ChatSessionLock sessionLock;
    private final ChatConcurrencyGuard chatConcurrencyGuard;
    private final ToolApprovalRegistry toolApprovalRegistry;
    private final AuditLogService auditLogService;
    private final ComplianceSettingsService complianceSettingsService;
    private final ChatInputCompliance chatInputCompliance;
    private final RedactionService redactionService;
    private final ModerationService moderationService;
    private final UsageLedger usageLedger;
    private final ClientIpResolver clientIpResolver;
    private final KingAiProperties properties;
    private final MeterRegistry meterRegistry;
    private final ChatTracing tracing;
    private final ChatMetrics chatMetrics;
    private final McpMetrics mcpMetrics;
    private final ChatSseWriter chatSseWriter;
    private final ChatContextAssembler contextAssembler;
    private final ChatToolRuntime toolRuntime;
    private final ChatLlmStreamer llmStreamer;
    private final ChatProgressFlusher progressFlusher;

    private final Map<String, SseEmitter> activeEmitters = new ConcurrentHashMap<>();

    @Qualifier("chatExecutor")
    private final ThreadPoolTaskExecutor chatExecutor;

    public SseEmitter start(ChatActor actor, AgentRuntime agent, Long sessionId, String rawMessage,
                            String sourceType) {
        Long spaceId = actor.spaceId();
        if (!Objects.equals(agent.spaceId(), spaceId)) {
            throw BizException.notFound("智能体不存在");
        }
        if (agent.status() != null && agent.status() != 0) {
            throw BizException.badRequest("智能体已停用");
        }
        String message = rawMessage == null ? "" : rawMessage.trim();
        if (message.isEmpty()) {
            throw BizException.badRequest("请输入消息内容");
        }
        int maxInput = Math.max(1000, properties.getChat().getMaxInputChars());
        if (message.length() > maxInput) {
            throw BizException.badRequest("消息长度不能超过 " + maxInput + " 个字符");
        }
        ComplianceSettings compliance = complianceSettingsService.effective(spaceId);
        boolean publicChat = "PUBLIC".equalsIgnoreCase(Texts.blankToDefault(sourceType, ""));
        ModerationContext inputContext = new ModerationContext(spaceId, publicChat, "input");
        ChatInputCompliance.PreparedInput prepared;
        try {
            prepared = chatInputCompliance.prepare(message, compliance, inputContext);
        } catch (ContentModerationException ex) {
            ChatAuditContext audit = captureAuditContext();
            auditLogService.recordEvent(spaceId, actor.userId(), actor.displayName(),
                    "chat.moderation_blocked", "chat", null, AuditLogService.STATUS_DENIED,
                    "surface=input, category=" + boundedCategory(ex.category()),
                    audit.ip(), audit.userAgent(), audit.requestId());
            meterRegistry.counter("king.ai.compliance.moderation", "surface", "input",
                    "action", "block", "category", boundedCategory(ex.category())).increment();
            throw BizException.badRequest("输入包含不合规内容，已被拦截");
        }
        String safeMessage = prepared.text();
        if (prepared.redactedCount() > 0) {
            meterRegistry.counter("king.ai.compliance.redactions", "surface", "input",
                    "category", "any").increment(prepared.redactedCount());
        }
        if (prepared.masked()) {
            meterRegistry.counter("king.ai.compliance.moderation", "surface", "input",
                    "action", "warn", "category", "BLOCKLIST").increment();
        }
        ModelRuntimeService.ResolvedModel resolved = modelRuntimeService.resolveChat(spaceId, agent.modelId());
        try {
            usageLedger.requireQuota(spaceId);
        } catch (BizException ex) {
            ChatAuditContext audit = captureAuditContext();
            auditLogService.recordEvent(spaceId, actor.userId(), actor.displayName(),
                    "chat.quota_rejected", "chat", null, AuditLogService.STATUS_DENIED,
                    "reason=monthly_token_quota", audit.ip(), audit.userAgent(), audit.requestId());
            throw ex;
        }
        chatMetrics.request(sourceTag(sourceType));

        CancellationToken token = new CancellationToken();
        String requestId = cancellationRegistry.register(token, spaceId, actor.key());
        boolean locked = sessionId != null && sessionLock.tryAcquire(sessionId, requestId);
        if (sessionId != null && !locked) {
            cancellationRegistry.remove(requestId);
            throw BizException.conflict("该会话正在生成回复，请等待完成后再发送");
        }
        int quotaLimit = properties.getChat().getMaxConcurrentPerSpace();
        if (!chatConcurrencyGuard.tryAcquire(spaceId, requestId, quotaLimit)) {
            cancellationRegistry.remove(requestId);
            releaseLock(sessionId, requestId, locked);
            chatMetrics.rejected("space_quota");
            throw BizException.tooManyRequests("当前空间并发对话已达上限，请稍后重试");
        }
        if (!chatMetrics.tryAcquireSse()) {
            cancellationRegistry.remove(requestId);
            releaseQuota(spaceId, requestId);
            releaseLock(sessionId, requestId, locked);
            chatMetrics.rejected("sse_capacity");
            throw new BizException(503, "当前流式连接已达上限，请稍后重试");
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        activeEmitters.put(requestId, emitter);
        AtomicBoolean closed = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            activeEmitters.remove(requestId);
            token.cancel();
            cancellationRegistry.remove(requestId);
            toolApprovalRegistry.removeRequest(requestId);
            releaseQuota(spaceId, requestId);
            releaseLock(sessionId, requestId, locked);
            chatMetrics.releaseSse();
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(error -> cleanup.run());
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        ChatAuditContext auditContext = captureAuditContext();
        Span chatSpan = tracing.start("chat.request");
        tracing.tag(chatSpan, "chat.source", sourceTag(sourceType));
        tracing.tag(chatSpan, "chat.space_id", String.valueOf(spaceId));
        tracing.tag(chatSpan, "gen_ai.agent.id", String.valueOf(agent.agentId()));
        tracing.tag(chatSpan, "gen_ai.request.model", resolved.model().getModel());
        try {
            chatExecutor.execute(() -> run(emitter, requestId, token, actor, agent, resolved,
                    sessionId, safeMessage, sourceType, locked, mdcContext, auditContext,
                    compliance, publicChat, prepared.redactedCount(), prepared.masked(),
                    chatSpan, cleanup));
        } catch (RuntimeException ex) {
            cleanup.run();
            tracing.error(chatSpan, ex);
            tracing.end(chatSpan);
            log.warn("Chat task rejected: {}", ex.getMessage());
            throw new BizException(503, "对话请求繁忙，请稍后重试");
        }
        return emitter;
    }

    /**
     * Keeps long-running streams alive through load balancers and proxies that close idle
     * connections. Clients ignore the heartbeat event.
     */
    @Scheduled(fixedDelayString = "${king-ai.chat.heartbeat-interval-ms:15000}")
    public void heartbeat() {
        activeEmitters.forEach((requestId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data("{}"));
            } catch (Exception ex) {
                activeEmitters.remove(requestId);
            }
        });
    }

    private void run(SseEmitter emitter, String requestId, CancellationToken token,
                     ChatActor actor, AgentRuntime agent, ModelRuntimeService.ResolvedModel resolved,
                     Long sessionId, String message, String sourceType, boolean locked,
                     Map<String, String> mdcContext, ChatAuditContext auditContext,
                     ComplianceSettings compliance, boolean publicChat,
                     int inputRedactions, boolean inputMasked, Span chatSpan, Runnable cleanup) {
        if (mdcContext != null && !mdcContext.isEmpty()) {
            MDC.setContextMap(mdcContext);
        }
        MDC.put(RequestIdFilter.MDC_KEY, requestId);
        Long spaceId = actor.spaceId();
        MDC.put("spaceId", spaceId == null ? "-" : String.valueOf(spaceId));
        long startedAt = System.currentTimeMillis();
        chatMetrics.activeStarted(spaceId);
        ChatSession session = null;
        ChatMessage assistant = null;
        boolean userSaved = false;
        String status = ChatService.STATUS_COMPLETED;
        String errorMessage = null;
        String usedModel = resolved.model().getModel();
        int promptTokens = 0;
        int completionTokens = 0;
        boolean truncated = false;
        String source = sourceTag(sourceType);

        // 安全带必须在 try 外创建：回调 lambda 只能捕获 effectively-final 变量，finally 也要访问。
        ComplianceStreamGuard contentGuard = new ComplianceStreamGuard(redactionService,
                moderationService, compliance,
                new ModerationContext(spaceId, publicChat, "output"), publicChat);
        ComplianceStreamGuard reasoningGuard = new ComplianceStreamGuard(redactionService,
                moderationService, compliance,
                new ModerationContext(spaceId, publicChat, "reasoning"), publicChat);
        ChatStreamState state = new ChatStreamState(emitter, token, contentGuard, reasoningGuard,
                source, startedAt);

        Tracer.SpanInScope spanScope = tracing.openScope(chatSpan);
        try {
            session = chatService.ensureSession(actor, agent.agentId(), sourceType, sessionId, message);
            List<LlmMessage> history = chatService.history(session.getId(), agent.historyWindow());
            chatService.saveUserMessage(session, message);
            userSaved = true;
            assistant = chatService.saveAssistantPlaceholder(session.getId(), resolved.model().getModel());
            state.progressMessage = assistant;

            chatSseWriter.send(emitter, "meta",
                    chatSseWriter.metaPayload(requestId, session, agent, resolved, sourceType));

            if (inputRedactions > 0) {
                chatSseWriter.send(emitter, "degraded", Map.of("reason", "pii_redacted",
                        "surface", "input", "count", inputRedactions));
            }
            if (inputMasked) {
                chatSseWriter.send(emitter, "degraded", Map.of("reason", "moderation_warn",
                        "surface", "input", "category", "BLOCKLIST"));
            }

            AgentBindings bindings = agent.bindings() == null ? AgentBindings.empty() : agent.bindings();
            String skillPrompt = contextAssembler.skillPrompt(spaceId, bindings.skillIds());
            List<RetrievedChunk> retrieved = contextAssembler.retrieve(spaceId, bindings.knowledgeBaseIds(),
                    message, actor.spaceRole());
            for (int i = 0; i < retrieved.size(); i++) {
                RetrievedChunk chunk = retrieved.get(i);
                RedactionResult safeSnippet = redactionService.redact(chunk.content(), compliance);
                Map<String, Object> citation = chatSseWriter.citationPayload(i + 1, chunk, safeSnippet.text());
                state.citations.add(citation);
                chatSseWriter.send(emitter, "citation", citation);
            }
            List<BoundTool> boundTools = toolRuntime.discoverTools(spaceId, bindings, skillPrompt,
                    agent.allowedTools());
            List<ToolDefinition> toolDefinitions = boundTools.stream().map(BoundTool::definition).toList();
            Map<String, BoundTool> toolIndex = new HashMap<>();
            for (BoundTool tool : boundTools) {
                toolIndex.put(tool.definition().name(), tool);
            }

            String systemPrompt = contextAssembler.buildSystemPrompt(agent, skillPrompt, retrieved);
            List<LlmMessage> messages = new ArrayList<>();
            messages.add(LlmMessage.system(systemPrompt));
            messages.addAll(history);
            messages.add(LlmMessage.user(message));

            int maxRounds = Math.max(0, properties.getChat().getMaxToolRounds());
            for (int round = 0; round <= maxRounds; round++) {
                token.throwIfCancelled();
                boolean allowTools = round < maxRounds && !boundTools.isEmpty();
                ChatLlmStreamer.StreamOutcome streamOutcome = llmStreamer.streamChatWithRetry(resolved,
                        messages, allowTools, toolDefinitions, agent, state);
                LlmResult result = streamOutcome.result();
                usedModel = streamOutcome.model();
                promptTokens += result.promptTokens();
                completionTokens += result.completionTokens();
                if ("length".equalsIgnoreCase(result.finishReason())) {
                    truncated = true;
                }
                if (!allowTools || result.toolCalls() == null || result.toolCalls().isEmpty()) {
                    break;
                }
                messages.add(LlmMessage.assistantToolCalls(result.content(), result.toolCalls()));
                for (ToolCall call : result.toolCalls()) {
                    token.throwIfCancelled();
                    BoundTool tool = toolIndex.get(call.name());
                    RedactionResult argRedaction = redactionService.redact(call.arguments(), compliance);
                    String toolArguments = argRedaction.text();
                    Map<String, Object> callPayload = new LinkedHashMap<>();
                    callPayload.put("id", call.id());
                    callPayload.put("name", call.name());
                    callPayload.put("arguments", Texts.cap(toolArguments, 8000));
                    callPayload.put("source", tool == null ? "UNKNOWN" : tool.source());
                    chatSseWriter.send(emitter, "tool_call", callPayload);

                    long toolStart = System.currentTimeMillis();
                    Span toolSpan = tool != null && tool.kind() == ToolKind.MCP
                            ? tracing.start("mcp.tool.call") : null;
                    if (toolSpan != null) {
                        tracing.tag(toolSpan, "mcp.server", tool.server() == null ? null : tool.server().getName());
                        tracing.tag(toolSpan, "mcp.tool", tool.remoteName());
                    }
                    ToolOutcome outcome;
                    try {
                        outcome = toolRuntime.executeTool(tool, call, spaceId, actor,
                                agent.toolApprovalMode(), emitter, requestId, token, toolArguments);
                        if (toolSpan != null) {
                            tracing.tag(toolSpan, "mcp.result", outcome.error() ? "error" : "success");
                        }
                    } catch (RuntimeException ex) {
                        tracing.error(toolSpan, ex);
                        throw ex;
                    } finally {
                        tracing.end(toolSpan);
                    }
                    String toolText = outcome.text();
                    RedactionResult toolRedaction = redactionService.redact(toolText, compliance);
                    toolText = toolRedaction.text();
                    ModerationScan toolScan = moderationService.scan(toolText, compliance,
                            new ModerationContext(spaceId, publicChat, "tool"));
                    if (toolScan.decision().blocking(publicChat)) {
                        throw new ContentModerationException(toolScan.decision().category());
                    }
                    if (!argRedaction.hits().isEmpty() || !toolRedaction.hits().isEmpty()) {
                        meterRegistry.counter("king.ai.compliance.redactions", "surface", "tool",
                                "category", "any").increment(
                                argRedaction.redactedCount() + toolRedaction.redactedCount());
                    }
                    long toolDuration = System.currentTimeMillis() - toolStart;
                    if (tool != null && tool.kind() == ToolKind.MCP) {
                        mcpMetrics.call(tool.server() == null ? null : tool.server().getName(),
                                tool.remoteName(), outcome.error(), toolDuration);
                    }
                    String toolSource = tool == null ? "UNKNOWN" : tool.source();
                    chatMetrics.toolCall(toolSource, outcome.error());
                    auditLogService.recordChat(spaceId, actor.userId(), actor.displayName(),
                            "chat.tool_call", call.id(),
                            outcome.error() ? AuditLogService.STATUS_FAILURE : AuditLogService.STATUS_SUCCESS,
                            "tool=" + call.name() + ", source=" + toolSource + ", durationMs=" + toolDuration,
                            auditContext.ip(), auditContext.userAgent(), auditContext.requestId());

                    Map<String, Object> traceItem = new LinkedHashMap<>();
                    traceItem.put("id", call.id());
                    traceItem.put("name", call.name());
                    traceItem.put("source", toolSource);
                    traceItem.put("arguments", Texts.cap(toolArguments, 8000));
                    traceItem.put("result",
                            Texts.cap(toolText, properties.getChat().getMaxToolResultChars()));
                    traceItem.put("error", outcome.error());
                    traceItem.put("durationMs", toolDuration);
                    state.trace.add(traceItem);

                    Map<String, Object> resultPayload = new LinkedHashMap<>();
                    resultPayload.put("id", call.id());
                    resultPayload.put("name", call.name());
                    resultPayload.put("result", Texts.cap(toolText, 4000));
                    resultPayload.put("error", outcome.error());
                    resultPayload.put("durationMs", toolDuration);
                    chatSseWriter.send(emitter, "tool_result", resultPayload);

                    messages.add(LlmMessage.tool(call.id(),
                            ChatToolRuntime.untrustedToolResult(call.name(),
                                    Texts.cap(toolText, properties.getChat().getMaxToolResultChars()))));
                }
                progressFlusher.maybeFlush(state, true);
            }
            // 循环结束后再 flush：轮次之间不能冲刷，否则跨轮次的手机号/身份证号
            // 会被拆成两段分别输出，脱敏窗口失效。
            String contentTail = contentGuard.flush();
            if (!contentTail.isEmpty()) {
                state.content.append(contentTail);
                chatSseWriter.send(emitter, "content", Map.of("delta", contentTail));
            }
            String reasoningTail = reasoningGuard.flush();
            if (!reasoningTail.isEmpty()) {
                state.reasoning.append(reasoningTail);
                chatSseWriter.send(emitter, "reasoning", Map.of("delta", reasoningTail));
            }
            progressFlusher.maybeFlush(state, true);
        } catch (ChatStreamClosedException ex) {
            token.cancel();
            status = ChatService.STATUS_STOPPED;
        } catch (LlmCancelledException ex) {
            status = ChatService.STATUS_STOPPED;
        } catch (ContentModerationException ex) {
            status = ChatService.STATUS_FAILED;
            errorMessage = "回复包含不合规内容，已终止生成";
            auditLogService.recordEvent(spaceId, actor.userId(), actor.displayName(),
                    "chat.moderation_blocked", "chat",
                    session == null ? null : String.valueOf(session.getId()),
                    AuditLogService.STATUS_DENIED,
                    "surface=output, category=" + boundedCategory(ex.category()),
                    auditContext.ip(), auditContext.userAgent(), auditContext.requestId());
            meterRegistry.counter("king.ai.compliance.moderation", "surface", "output",
                    "action", "block", "category", boundedCategory(ex.category())).increment();
            chatSseWriter.sendQuietly(emitter, "error", Map.of("message", errorMessage));
        } catch (Throwable ex) {
            tracing.error(chatSpan, ex);
            if (token.isCancelled()) {
                status = ChatService.STATUS_STOPPED;
            } else {
                status = ChatService.STATUS_FAILED;
                errorMessage = friendlyError(ex);
                log.error("Chat generation failed: requestId={}, sessionId={}", requestId,
                        session == null ? null : session.getId(), ex);
                chatSseWriter.sendQuietly(emitter, "error", Map.of("message", errorMessage));
            }
        } finally {
            long duration = System.currentTimeMillis() - startedAt;
            tracing.tag(chatSpan, "chat.status", status);
            tracing.tag(chatSpan, "chat.duration_ms", String.valueOf(duration));
            tracing.tag(chatSpan, "gen_ai.usage.prompt_tokens", String.valueOf(promptTokens));
            tracing.tag(chatSpan, "gen_ai.usage.completion_tokens", String.valueOf(completionTokens));
            Map<String, Integer> redactions = new java.util.LinkedHashMap<>();
            redactions.putAll(contentGuard.countsByCategory());
            reasoningGuard.countsByCategory().forEach((key, value) ->
                    redactions.merge(key, value, Integer::sum));
            redactions.forEach((category, count) ->
                    meterRegistry.counter("king.ai.compliance.redactions",
                            "surface", "output", "category", category).increment(count));
            if (!redactions.isEmpty()) {
                chatSseWriter.sendQuietly(emitter, "degraded", Map.of("reason", "pii_redacted",
                        "surface", "output",
                        "count", redactions.values().stream().mapToInt(Integer::intValue).sum()));
            }
            Span persistSpan = tracing.start("chat.persist");
            tracing.tag(persistSpan, "chat.message_id",
                    assistant == null ? null : String.valueOf(assistant.getId()));
            tracing.tag(persistSpan, "chat.status", status);
            try {
                if (assistant != null) {
                    try {
                        chatService.finishAssistant(assistant.getId(), state.content.toString(),
                                state.reasoning.toString(),
                                chatSseWriter.toolTraceJson(state.trace),
                                chatSseWriter.citationsJson(state.citations), status,
                                usedModel, promptTokens, completionTokens, duration);
                    } catch (RuntimeException ex) {
                        tracing.error(persistSpan, ex);
                        log.error("Failed to persist assistant message {}", assistant.getId(), ex);
                    }
                }
                if (session != null) {
                    try {
                        chatService.touchSession(session.getId(),
                                (userSaved ? 1 : 0) + (assistant != null ? 1 : 0));
                    } catch (RuntimeException ex) {
                        tracing.error(persistSpan, ex);
                        log.warn("Failed to update chat session {}: {}", session.getId(), ex.getMessage());
                    }
                }
            } finally {
                tracing.end(persistSpan);
            }
            try {
                usageLedger.record(spaceId, agent.agentId(),
                        session == null ? null : session.getId(),
                        assistant == null ? null : assistant.getId(),
                        usedModel, sourceType, promptTokens, completionTokens, status);
            } catch (RuntimeException ex) {
                log.warn("Failed to record usage ledger for space {}: {}", spaceId, ex.getMessage());
            }
            if (assistant != null) {
                chatSseWriter.sendQuietly(emitter, "usage",
                        chatSseWriter.usagePayload(promptTokens, completionTokens));
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("messageId", assistant.getId());
                done.put("sessionId", session.getId());
                done.put("status", status);
                done.put("durationMs", duration);
                done.put("stopped", ChatService.STATUS_STOPPED.equals(status));
                done.put("truncated", truncated);
                if (errorMessage != null) {
                    done.put("error", errorMessage);
                }
                chatSseWriter.sendQuietly(emitter, "done", done);
            }
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
            }
            cleanup.run();
            chatMetrics.activeFinished(spaceId);
            String auditStatus = ChatService.STATUS_FAILED.equals(status)
                    ? AuditLogService.STATUS_FAILURE
                    : ChatService.STATUS_STOPPED.equals(status)
                    ? AuditLogService.STATUS_DENIED : AuditLogService.STATUS_SUCCESS;
            auditLogService.recordChat(spaceId, actor.userId(), actor.displayName(), "chat.send",
                    session == null ? null : String.valueOf(session.getId()), auditStatus,
                    "source=" + sourceType + ", model=" + resolved.model().getModel()
                            + ", status=" + status + ", durationMs=" + duration
                            + (errorMessage == null ? "" : ", error=" + errorMessage),
                    auditContext.ip(), auditContext.userAgent(), auditContext.requestId());
            chatMetrics.finished(source, status, duration, promptTokens, completionTokens,
                    resolved.model().getModel());
            MDC.clear();
            spanScope.close();
            tracing.end(chatSpan);
        }
    }

    private static String sourceTag(String sourceType) {
        return Texts.isBlank(sourceType) ? "unknown" : sourceType.trim().toLowerCase(Locale.ROOT);
    }

    private void releaseLock(Long sessionId, String requestId, boolean locked) {
        if (!locked) {
            return;
        }
        try {
            sessionLock.release(sessionId, requestId);
        } catch (RuntimeException ex) {
            log.warn("Failed to release chat session lock {}: {}", sessionId, ex.getMessage());
        }
    }

    private void releaseQuota(Long spaceId, String requestId) {
        try {
            chatConcurrencyGuard.release(spaceId, requestId);
        } catch (RuntimeException ex) {
            log.warn("Failed to release chat concurrency slot {}: {}", requestId, ex.getMessage());
        }
    }

    private ChatAuditContext captureAuditContext() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return ChatAuditContext.empty();
        }
        HttpServletRequest request = servletAttributes.getRequest();
        return new ChatAuditContext(clientIpResolver.resolve(request),
                request.getHeader("User-Agent"), MDC.get(RequestIdFilter.MDC_KEY));
    }

    /**
     * 外部审核服务返回的 category 属于不可信文本（EXTERNAL/HYBRID 直接透传 provider 响应），
     * 绝不能作为指标标签或审计详情输出。只放行固定类别，其余一律收敛为 EXTERNAL。
     */
    private static String boundedCategory(String category) {
        if (Texts.isBlank(category)) {
            return "UNKNOWN";
        }
        String normalized = category.trim().toUpperCase(Locale.ROOT);
        return MODERATION_CATEGORIES.contains(normalized) ? normalized : "EXTERNAL";
    }

    private static String friendlyError(Throwable ex) {
        if (ex instanceof BizException biz) {
            return biz.getMessage();
        }
        String message = ex.getMessage();
        return Texts.isBlank(message) ? "服务暂时不可用，请稍后重试" : Texts.oneLine(message, 300);
    }

    private record ChatAuditContext(String ip, String userAgent, String requestId) {
        private static ChatAuditContext empty() {
            return new ChatAuditContext(null, null, null);
        }
    }
}
