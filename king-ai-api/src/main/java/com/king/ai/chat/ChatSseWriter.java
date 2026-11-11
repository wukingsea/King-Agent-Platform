package com.king.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.agent.AgentRuntime;
import com.king.ai.chat.entity.ChatSession;
import com.king.ai.common.Texts;
import com.king.ai.knowledge.dto.RetrievedChunk;
import com.king.ai.model.ModelRuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSE 事件的序列化与发送，以及各类事件 payload 的组装。
 * 发送失败抛出 {@link ChatStreamClosedException}；{@link #sendQuietly}
 * 用于收尾阶段，绝不允许因客户端断开而中断落库与审计。
 */
@Component
@RequiredArgsConstructor
public class ChatSseWriter {

    private final ObjectMapper objectMapper;

    public void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(payload)));
        } catch (Exception ex) {
            throw new ChatStreamClosedException(ex);
        }
    }

    public void sendQuietly(SseEmitter emitter, String event, Object payload) {
        try {
            send(emitter, event, payload);
        } catch (RuntimeException ignored) {
        }
    }

    public Map<String, Object> metaPayload(String requestId, ChatSession session, AgentRuntime agent,
                                           ModelRuntimeService.ResolvedModel resolved, String sourceType) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("requestId", requestId);
        meta.put("sessionId", session.getId());
        meta.put("agentId", agent.agentId());
        meta.put("agentName", agent.name());
        meta.put("model", resolved.model().getModel());
        meta.put("modelName", resolved.model().getName());
        meta.put("sourceType", sourceType);
        return meta;
    }

    public Map<String, Object> citationPayload(int index, RetrievedChunk chunk, String snippet) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("index", index);
        payload.put("chunkId", chunk.chunkId());
        payload.put("docId", chunk.docId());
        payload.put("docName", chunk.docName());
        payload.put("knowledgeBaseId", chunk.knowledgeBaseId());
        payload.put("knowledgeBaseName", chunk.knowledgeBaseName());
        payload.put("chunkIndex", chunk.chunkIndex());
        payload.put("score", Math.round(chunk.score() * 1000) / 1000.0);
        payload.put("snippet", Texts.cap(snippet, 600));
        return payload;
    }

    public Map<String, Object> usagePayload(int promptTokens, int completionTokens) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("promptTokens", promptTokens);
        usage.put("completionTokens", completionTokens);
        usage.put("totalTokens", promptTokens + completionTokens);
        return usage;
    }

    public String toolTraceJson(List<Map<String, Object>> trace) {
        try {
            return objectMapper.writeValueAsString(trace);
        } catch (Exception ex) {
            return "[]";
        }
    }

    public String citationsJson(List<Map<String, Object>> citations) {
        try {
            return objectMapper.writeValueAsString(citations);
        } catch (Exception ex) {
            return "[]";
        }
    }
}
