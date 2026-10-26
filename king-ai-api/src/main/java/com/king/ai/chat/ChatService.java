package com.king.ai.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.agent.entity.Agent;
import com.king.ai.agent.mapper.AgentMapper;
import com.king.ai.chat.dto.ChatMessageView;
import com.king.ai.chat.dto.ChatSessionView;
import com.king.ai.chat.entity.ChatMessage;
import com.king.ai.chat.entity.ChatSession;
import com.king.ai.chat.mapper.ChatMessageMapper;
import com.king.ai.chat.mapper.ChatSessionMapper;
import com.king.ai.common.BizException;
import com.king.ai.common.PageResult;
import com.king.ai.common.Texts;
import com.king.ai.common.security.UserPrincipal;
import com.king.ai.config.KingAiProperties;
import com.king.ai.llm.LlmMessage;
import com.king.ai.system.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {

    public static final String SOURCE_ADMIN = "ADMIN";
    public static final String SOURCE_PUBLIC = "PUBLIC";

    public static final String STATUS_STREAMING = "STREAMING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_STOPPED = "STOPPED";
    public static final String STATUS_FAILED = "FAILED";

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final AgentMapper agentMapper;
    private final ObjectMapper objectMapper;
    private final KingAiProperties properties;
    private final SpaceService spaceService;

    public PageResult<ChatSessionView> listSessions(UserPrincipal principal, Long agentId, String sourceType,
                                                    String keyword, Integer pageNo, Integer size) {
        Long spaceId = requireSpace(principal);
        int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int pageSize = size == null ? 20 : Math.max(1, Math.min(size, 100));
        LambdaQueryWrapper<ChatSession> wrapper = new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getSpaceId, spaceId);
        if (!spaceService.canViewAllSessions(principal, spaceId)) {
            wrapper.eq(ChatSession::getOwnerUserId, principal.id());
        }
        if (agentId != null) {
            wrapper.eq(ChatSession::getAgentId, agentId);
        }
        if (!Texts.isBlank(sourceType)) {
            wrapper.eq(ChatSession::getSourceType, sourceType.trim().toUpperCase());
        }
        if (!Texts.isBlank(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(ChatSession::getTitle, kw).or().like(ChatSession::getVisitorId, kw));
        }
        wrapper.orderByDesc(ChatSession::getLastMessageAt).orderByDesc(ChatSession::getId);
        Page<ChatSession> result = sessionMapper.selectPage(new Page<>(page, pageSize), wrapper);
        Map<Long, Agent> agents = agentMap(result.getRecords().stream().map(ChatSession::getAgentId).toList());
        List<ChatSessionView> views = result.getRecords().stream()
                .map(session -> view(session, agents.get(session.getAgentId())))
                .toList();
        return PageResult.of(views, result.getTotal(), page, pageSize);
    }

    public List<ChatSessionView> visitorSessions(Long agentId, String visitorId) {
        Page<ChatSession> result = sessionMapper.selectPage(new Page<>(1, 50),
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getAgentId, agentId)
                        .eq(ChatSession::getSourceType, SOURCE_PUBLIC)
                        .eq(ChatSession::getVisitorId, visitorId)
                        .orderByDesc(ChatSession::getLastMessageAt)
                        .orderByDesc(ChatSession::getId));
        Agent agent = agentMapper.selectById(agentId);
        return result.getRecords().stream().map(session -> view(session, agent)).toList();
    }

    public ChatSession requireSession(Long spaceId, Long sessionId) {
        if (sessionId == null) {
            throw BizException.badRequest("缺少会话 ID");
        }
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !Objects.equals(session.getSpaceId(), spaceId)) {
            throw BizException.notFound("会话不存在");
        }
        return session;
    }

    public List<ChatMessageView> messages(UserPrincipal principal, Long sessionId) {
        ChatSession session = requireSessionAccess(principal, sessionId, false);
        return messagesOf(session.getId());
    }

    public List<ChatMessageView> visitorMessages(Long agentId, String visitorId, Long sessionId) {
        if (sessionId == null) {
            throw BizException.badRequest("缺少会话 ID");
        }
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !Objects.equals(session.getAgentId(), agentId)
                || !SOURCE_PUBLIC.equals(session.getSourceType())
                || !Objects.equals(session.getVisitorId(), visitorId)) {
            throw BizException.notFound("会话不存在");
        }
        return messagesOf(sessionId);
    }

    public ChatSessionView rename(UserPrincipal principal, Long sessionId, String title) {
        ChatSession session = requireSessionAccess(principal, sessionId, true);
        String value = Texts.trimTo(title, 100);
        if (Texts.isBlank(value)) {
            throw BizException.badRequest("会话标题不能为空");
        }
        session.setTitle(value);
        sessionMapper.updateById(session);
        return view(session, agentMapper.selectById(session.getAgentId()));
    }

    @Transactional
    public void deleteSession(UserPrincipal principal, Long sessionId) {
        ChatSession session = requireSessionAccess(principal, sessionId, true);
        messageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId));
        sessionMapper.deleteById(session.getId());
    }

    @Transactional
    public ChatSession ensureSession(ChatActor actor, Long agentId, String sourceType,
                                     Long sessionId, String firstMessage) {
        if (sessionId != null) {
            ChatSession session = sessionMapper.selectById(sessionId);
            if (session == null || !Objects.equals(session.getSpaceId(), actor.spaceId())) {
                throw BizException.notFound("会话不存在");
            }
            if (!Objects.equals(session.getAgentId(), agentId)) {
                throw BizException.badRequest("会话与当前智能体不匹配");
            }
            if (!Objects.equals(session.getSourceType(), sourceType)) {
                throw BizException.badRequest("会话来源不匹配");
            }
            if (SOURCE_PUBLIC.equals(sourceType)) {
                if (!Objects.equals(session.getVisitorId(), actor.visitorId())) {
                    throw BizException.notFound("会话不存在");
                }
            } else if (!actor.canManage() && !Objects.equals(session.getOwnerUserId(), actor.userId())) {
                throw BizException.notFound("会话不存在");
            }
            return session;
        }
        ChatSession session = new ChatSession();
        session.setSpaceId(actor.spaceId());
        session.setAgentId(agentId);
        session.setSourceType(sourceType);
        session.setVisitorId(actor.visitorId());
        session.setOwnerUserId(actor.userId());
        session.setTitle(titleOf(firstMessage));
        session.setMessageCount(0);
        sessionMapper.insert(session);
        return session;
    }

    public ChatMessage saveUserMessage(ChatSession session, String content) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(session.getId());
        message.setRole("user");
        message.setContent(content);
        message.setStatus(STATUS_COMPLETED);
        message.setPromptTokens(0);
        message.setCompletionTokens(0);
        message.setDurationMs(0L);
        messageMapper.insert(message);
        return message;
    }

    public ChatMessage saveAssistantPlaceholder(Long sessionId, String model) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole("assistant");
        message.setContent("");
        message.setReasoning("");
        message.setToolTrace("[]");
        message.setCitations("[]");
        message.setStatus(STATUS_STREAMING);
        message.setModel(model);
        message.setPromptTokens(0);
        message.setCompletionTokens(0);
        message.setDurationMs(0L);
        messageMapper.insert(message);
        return message;
    }

    public void updateProgress(Long messageId, String content, String reasoning,
                               String toolTrace, String citations) {
        ChatMessage patch = new ChatMessage();
        patch.setId(messageId);
        patch.setContent(content == null ? "" : content);
        patch.setReasoning(reasoning == null ? "" : reasoning);
        patch.setToolTrace(toolTrace == null ? "[]" : toolTrace);
        patch.setCitations(citations == null ? "[]" : citations);
        messageMapper.updateById(patch);
    }

    public void finishAssistant(Long messageId, String content, String reasoning, String toolTrace,
                                String citations, String status, String model,
                                int promptTokens, int completionTokens, long durationMs) {
        ChatMessage patch = new ChatMessage();
        patch.setId(messageId);
        patch.setContent(content == null ? "" : content);
        patch.setReasoning(reasoning == null ? "" : reasoning);
        patch.setToolTrace(toolTrace == null ? "[]" : toolTrace);
        patch.setCitations(citations == null ? "[]" : citations);
        patch.setStatus(status);
        patch.setModel(model);
        patch.setPromptTokens(promptTokens);
        patch.setCompletionTokens(completionTokens);
        patch.setDurationMs(durationMs);
        messageMapper.updateById(patch);
    }

    public void touchSession(Long sessionId, int addedMessages) {
        if (sessionId == null || addedMessages <= 0) {
            return;
        }
        sessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId)
                .setSql("message_count = message_count + " + addedMessages)
                .setSql("last_message_at = CURRENT_TIMESTAMP")
                .setSql("updated_at = CURRENT_TIMESTAMP"));
    }

    public List<LlmMessage> history(Long sessionId) {
        int limit = Math.max(1, properties.getChat().getMaxHistoryMessages());
        // Bounded query: long sessions must not load the whole transcript into memory on every turn.
        List<ChatMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .in(ChatMessage::getRole, "user", "assistant")
                .in(ChatMessage::getStatus, STATUS_COMPLETED, STATUS_STOPPED)
                .orderByDesc(ChatMessage::getId)
                .last("LIMIT " + limit));
        if (messages.isEmpty()) {
            return List.of();
        }
        int maxChars = Math.max(2000, properties.getChat().getMaxHistoryChars());
        List<LlmMessage> result = new ArrayList<>();
        int chars = 0;
        for (int i = messages.size() - 1; i >= 0 && result.size() < limit; i--) {
            ChatMessage message = messages.get(i);
            String content = message.getContent();
            if (Texts.isBlank(content)) {
                continue;
            }
            if (!result.isEmpty() && chars + content.length() > maxChars) {
                break;
            }
            result.add(0, new LlmMessage(message.getRole(), content, null, null));
            chars += content.length();
        }
        return result;
    }

    private List<ChatMessageView> messagesOf(Long sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getId))
                .stream()
                .map(this::messageView)
                .toList();
    }

    private ChatMessageView messageView(ChatMessage message) {
        return new ChatMessageView(message.getId(), message.getSessionId(), message.getRole(),
                Texts.blankToDefault(message.getContent(), ""),
                Texts.blankToDefault(message.getReasoning(), ""),
                json(message.getToolTrace(), true),
                json(message.getCitations(), true),
                Texts.blankToDefault(message.getStatus(), STATUS_COMPLETED),
                message.getModel(),
                message.getPromptTokens() == null ? 0 : message.getPromptTokens(),
                message.getCompletionTokens() == null ? 0 : message.getCompletionTokens(),
                message.getDurationMs() == null ? 0L : message.getDurationMs(),
                message.getCreatedAt());
    }

    private JsonNode json(String value, boolean arrayFallback) {
        if (!Texts.isBlank(value)) {
            try {
                return objectMapper.readTree(value);
            } catch (Exception ignored) {
            }
        }
        return arrayFallback ? objectMapper.createArrayNode() : objectMapper.createObjectNode();
    }

    private ChatSessionView view(ChatSession session, Agent agent) {
        return new ChatSessionView(session.getId(), session.getAgentId(), session.getOwnerUserId(),
                agent == null ? "-" : agent.getName(),
                agent == null ? "" : Texts.blankToDefault(agent.getPublicId(), ""),
                session.getSourceType(), session.getVisitorId(), session.getTitle(),
                session.getMessageCount() == null ? 0 : session.getMessageCount(),
                session.getLastMessageAt(), session.getCreatedAt());
    }

    private ChatSession requireSessionAccess(UserPrincipal principal, Long sessionId, boolean modify) {
        Long spaceId = requireSpace(principal);
        ChatSession session = requireSession(spaceId, sessionId);
        boolean manage = spaceService.canManageSessions(principal, spaceId);
        boolean viewAll = spaceService.canViewAllSessions(principal, spaceId);
        if (SOURCE_PUBLIC.equals(session.getSourceType())) {
            if (modify ? !manage : !viewAll) {
                throw BizException.notFound("会话不存在");
            }
            return session;
        }
        boolean owner = Objects.equals(session.getOwnerUserId(), principal.id());
        if (modify && !owner && !manage) {
            throw BizException.forbidden("无权操作该会话");
        }
        if (!modify && !owner && !viewAll) {
            throw BizException.notFound("会话不存在");
        }
        return session;
    }

    private Long requireSpace(UserPrincipal principal) {
        if (principal.spaceId() == null) {
            throw BizException.badRequest("请先选择业务空间");
        }
        return principal.spaceId();
    }

    private Map<Long, Agent> agentMap(List<Long> agentIds) {
        List<Long> ids = agentIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Agent> map = new HashMap<>();
        agentMapper.selectBatchIds(ids).forEach(agent -> map.put(agent.getId(), agent));
        return map;
    }

    private static String titleOf(String message) {
        String title = Texts.oneLine(message, 40);
        return title.isEmpty() ? "新对话" : title;
    }
}
