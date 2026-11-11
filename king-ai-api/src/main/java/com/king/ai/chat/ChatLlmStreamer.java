package com.king.ai.chat;

import com.king.ai.agent.AgentRuntime;
import com.king.ai.common.Texts;
import com.king.ai.compliance.ComplianceStreamGuard;
import com.king.ai.compliance.ContentModerationException;
import com.king.ai.config.KingAiProperties;
import com.king.ai.llm.LlmCancelledException;
import com.king.ai.llm.LlmChatRequest;
import com.king.ai.llm.LlmCircuitBreaker;
import com.king.ai.llm.LlmException;
import com.king.ai.llm.LlmMessage;
import com.king.ai.llm.LlmResult;
import com.king.ai.llm.LlmStreamListener;
import com.king.ai.llm.ToolDefinition;
import com.king.ai.model.ModelRuntimeService;
import com.king.ai.observability.ChatMetrics;
import com.king.ai.observability.ChatTracing;
import io.micrometer.tracing.Span;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单轮模型流式调用：重试、熔断、降级模型回退与安全带（脱敏/审核）增量处理。
 * 所有输出先经过 {@link ComplianceStreamGuard} 再进入 SSE 与落库缓冲，
 * 失败重试会把内容与安全带状态回滚到本次尝试之前，避免半截输出重复。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatLlmStreamer {

    private final ModelRuntimeService modelRuntimeService;
    private final LlmCircuitBreaker circuitBreaker;
    private final KingAiProperties properties;
    private final ChatTracing tracing;
    private final ChatMetrics chatMetrics;
    private final ChatSseWriter chatSseWriter;
    private final ChatProgressFlusher progressFlusher;

    record StreamOutcome(LlmResult result, String model) {
    }

    public StreamOutcome streamChatWithRetry(ModelRuntimeService.ResolvedModel resolved,
                                             List<LlmMessage> messages, boolean allowTools,
                                             List<ToolDefinition> toolDefinitions,
                                             AgentRuntime agent, ChatStreamState state) {
        ChatStreamCheckpoint checkpoint = ChatStreamCheckpoint.capture(
                state.content, state.reasoning, state.contentGuard, state.reasoningGuard);
        try {
            LlmResult result = streamWithRetry(resolved, messages, allowTools, toolDefinitions, agent, state);
            return new StreamOutcome(result, resolved.model().getModel());
        } catch (LlmException ex) {
            if (agent.fallbackModelId() != null && !Objects.equals(agent.fallbackModelId(), agent.modelId())) {
                try {
                    ModelRuntimeService.ResolvedModel fallback =
                            modelRuntimeService.resolveChat(agent.spaceId(), agent.fallbackModelId());
                    log.warn("Primary model {} failed, switching to fallback {}: {}",
                            resolved.model().getModel(), fallback.model().getModel(), ex.getMessage());
                    // 降级模型会从头重放同一轮，必须先丢弃主模型的半截输出，否则前端与落库内容重复。
                    checkpoint.restore(state.content, state.reasoning,
                            state.contentGuard, state.reasoningGuard);
                    progressFlusher.rollbackProgress(state, state.content.length(), state.reasoning.length());
                    chatSseWriter.sendQuietly(state.emitter, "degraded", Map.of(
                            "reason", "model_fallback",
                            "message", "主模型暂时不可用，已切换至降级模型",
                            "model", fallback.model().getModel(),
                            "reset", true,
                            "contentLength", state.content.length(),
                            "reasoningLength", state.reasoning.length()));
                    LlmResult result = streamWithRetry(fallback, messages, allowTools, toolDefinitions,
                            agent, state);
                    return new StreamOutcome(result, fallback.model().getModel());
                } catch (ContentModerationException moderationEx) {
                    throw moderationEx;
                } catch (RuntimeException fallbackError) {
                    log.warn("Fallback model failed: {}", fallbackError.getMessage());
                }
            }
            throw ex;
        }
    }

    private LlmResult streamWithRetry(ModelRuntimeService.ResolvedModel resolved,
                                      List<LlmMessage> messages, boolean allowTools,
                                      List<ToolDefinition> toolDefinitions,
                                      AgentRuntime agent, ChatStreamState state) {
        String breakerKey = resolved.supplier().getId() + ":" + resolved.model().getModel();
        if (!circuitBreaker.allow(breakerKey)) {
            throw new LlmException("模型服务连续失败，已触发熔断，请稍后重试");
        }
        int maxRetries = Math.max(0, properties.getChat().getLlmMaxRetries());
        LlmException lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            state.token.throwIfCancelled();
            Span llmSpan = tracing.start("llm.chat");
            tracing.tag(llmSpan, "gen_ai.request.model", resolved.model().getModel());
            tracing.tag(llmSpan, "llm.attempt", String.valueOf(attempt + 1));
            ChatMetrics.TokenTimer ttft = chatMetrics.startTokenTimer(
                    resolved.model().getModel(), state.source);
            int contentMark = state.content.length();
            int reasoningMark = state.reasoning.length();
            // 失败重试必须恢复到本次尝试前的安全带状态：直接 reset 会丢掉上一轮工具调用
            // 遗留的 holdback 尾巴和已计入的脱敏次数。
            ComplianceStreamGuard.Checkpoint contentGuardMark = state.contentGuard.checkpoint();
            ComplianceStreamGuard.Checkpoint reasoningGuardMark = state.reasoningGuard.checkpoint();
            try {
                LlmResult result = resolved.client().streamChat(new LlmChatRequest(
                                resolved.model().getModel(), messages,
                                allowTools ? toolDefinitions : List.of(),
                                agent.temperature(), agent.maxTokens()),
                        new LlmStreamListener() {
                            @Override
                            public void onReasoning(String delta) {
                                if (Texts.isBlank(delta)) {
                                    return;
                                }
                                ttft.firstToken();
                                String safe = state.reasoningGuard.accept(delta);
                                if (!safe.isEmpty()) {
                                    state.reasoning.append(safe);
                                    chatSseWriter.send(state.emitter, "reasoning", Map.of("delta", safe));
                                    progressFlusher.maybeFlush(state, false);
                                }
                            }

                            @Override
                            public void onContent(String delta) {
                                if (Texts.isBlank(delta)) {
                                    return;
                                }
                                ttft.firstToken();
                                String safe = state.contentGuard.accept(delta);
                                if (!safe.isEmpty()) {
                                    state.content.append(safe);
                                    chatSseWriter.send(state.emitter, "content", Map.of("delta", safe));
                                    progressFlusher.maybeFlush(state, false);
                                }
                            }
                        }, state.token);
                circuitBreaker.success(breakerKey);
                tracing.tag(llmSpan, "gen_ai.response.finish_reasons", result.finishReason());
                tracing.tag(llmSpan, "gen_ai.usage.prompt_tokens", String.valueOf(result.promptTokens()));
                tracing.tag(llmSpan, "gen_ai.usage.completion_tokens", String.valueOf(result.completionTokens()));
                return result;
            } catch (LlmException ex) {
                tracing.error(llmSpan, ex);
                lastException = ex;
                circuitBreaker.failure(breakerKey);
                if (attempt < maxRetries && isTransientError(ex)) {
                    log.warn("LLM request failed (attempt {}/{}), retrying: {}", attempt + 1, maxRetries + 1,
                            ex.getMessage());
                    // Discard partial output from the failed attempt so retries do not duplicate text.
                    state.content.setLength(contentMark);
                    state.reasoning.setLength(reasoningMark);
                    state.contentGuard.restore(contentGuardMark);
                    state.reasoningGuard.restore(reasoningGuardMark);
                    progressFlusher.rollbackProgress(state, contentMark, reasoningMark);
                    chatSseWriter.sendQuietly(state.emitter, "retry", Map.of("attempt", attempt + 1,
                            "reset", true, "message", "模型请求失败，正在重试..."));
                    try {
                        Thread.sleep(1000L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new LlmCancelledException();
                    }
                    continue;
                }
                throw ex;
            } finally {
                tracing.end(llmSpan);
            }
        }
        throw lastException != null ? lastException : new LlmException("LLM 请求失败");
    }

    private boolean isTransientError(LlmException ex) {
        String message = ex.getMessage();
        if (Texts.isBlank(message)) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("timeout") || lower.contains("connection") || lower.contains("502")
                || lower.contains("503") || lower.contains("504") || lower.contains("rate limit");
    }
}
