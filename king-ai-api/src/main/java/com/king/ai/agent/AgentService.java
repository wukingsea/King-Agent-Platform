package com.king.ai.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.agent.dto.AgentOption;
import com.king.ai.agent.dto.AgentSaveRequest;
import com.king.ai.agent.dto.AgentVersionView;
import com.king.ai.agent.dto.AgentView;
import com.king.ai.agent.entity.Agent;
import com.king.ai.agent.entity.AgentKnowledge;
import com.king.ai.agent.entity.AgentMcp;
import com.king.ai.agent.entity.AgentSkill;
import com.king.ai.agent.entity.AgentVersion;
import com.king.ai.agent.mapper.AgentKnowledgeMapper;
import com.king.ai.agent.mapper.AgentMapper;
import com.king.ai.agent.mapper.AgentMcpMapper;
import com.king.ai.agent.mapper.AgentSkillMapper;
import com.king.ai.agent.mapper.AgentVersionMapper;
import com.king.ai.agent.release.CanaryRouting;
import com.king.ai.agent.release.ReleaseState;
import com.king.ai.common.BizException;
import com.king.ai.common.Ids;
import com.king.ai.common.OptimisticLocks;
import com.king.ai.common.PageQuery;
import com.king.ai.common.PageResult;
import com.king.ai.common.Texts;
import com.king.ai.config.KingAiProperties;
import com.king.ai.knowledge.entity.KnowledgeBase;
import com.king.ai.knowledge.mapper.KnowledgeBaseMapper;
import com.king.ai.license.LicenseService;
import com.king.ai.mcp.entity.McpServer;
import com.king.ai.mcp.mapper.McpServerMapper;
import com.king.ai.model.ModelRuntimeService;
import com.king.ai.model.entity.SupplierModel;
import com.king.ai.model.mapper.SupplierModelMapper;
import com.king.ai.skill.entity.Skill;
import com.king.ai.skill.mapper.SkillMapper;
import com.king.ai.system.mapper.SpaceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private static final BigDecimal DEFAULT_TEMPERATURE = new BigDecimal("0.70");
    private static final String DEFAULT_APPROVAL_MODE = "WRITE";

    private final AgentMapper agentMapper;
    private final AgentVersionMapper agentVersionMapper;
    private final AgentKnowledgeMapper agentKnowledgeMapper;
    private final AgentMcpMapper agentMcpMapper;
    private final AgentSkillMapper agentSkillMapper;
    private final SupplierModelMapper modelMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final McpServerMapper mcpServerMapper;
    private final SkillMapper skillMapper;
    private final SpaceMapper spaceMapper;
    private final ModelRuntimeService modelRuntimeService;
    private final ObjectMapper objectMapper;
    private final KingAiProperties properties;
    private final LicenseService licenseService;

    public PageResult<AgentView> list(Long spaceId, String keyword, PageQuery query,
                                      boolean includeSystemPrompt) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<Agent>()
                .eq(Agent::getSpaceId, spaceId);
        if (!Texts.isBlank(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Agent::getName, kw).or().like(Agent::getIntro, kw));
        }
        wrapper.orderByDesc(Agent::getId);
        Page<Agent> result = agentMapper.selectPage(new Page<>(query.page(), query.size()), wrapper);
        List<Agent> agents = result.getRecords();
        if (agents.isEmpty()) {
            return PageResult.of(List.of(), result.getTotal(), query.page(), query.size());
        }
        Map<Long, SupplierModel> models = modelMap(agents.stream().map(Agent::getModelId).toList());
        Map<Long, AgentBindings> bindings = bindingsFor(agents.stream().map(Agent::getId).toList());
        List<AgentView> views = agents.stream()
                .map(agent -> view(agent, models.get(agent.getModelId()),
                        bindings.getOrDefault(agent.getId(), emptyBindings()), includeSystemPrompt))
                .toList();
        return PageResult.of(views, result.getTotal(), query.page(), query.size());
    }

    public List<AgentOption> options(Long spaceId, String keyword, int limit) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<Agent>()
                .select(Agent::getId, Agent::getName)
                .eq(Agent::getSpaceId, spaceId);
        if (!Texts.isBlank(keyword)) {
            wrapper.like(Agent::getName, keyword.trim());
        }
        wrapper.orderByDesc(Agent::getId).last("LIMIT " + Math.min(PageQuery.MAX_SIZE, Math.max(1, limit)));
        return agentMapper.selectList(wrapper).stream()
                .map(agent -> new AgentOption(agent.getId(), agent.getName()))
                .toList();
    }

    public Agent require(Long spaceId, Long id) {
        if (id == null) {
            throw BizException.badRequest("缺少智能体 ID");
        }
        Agent agent = agentMapper.selectById(id);
        if (agent == null || !Objects.equals(agent.getSpaceId(), spaceId)) {
            throw BizException.notFound("智能体不存在");
        }
        return agent;
    }

    public AgentView detail(Long spaceId, Long id, boolean includeSystemPrompt) {
        return view(require(spaceId, id), includeSystemPrompt);
    }

    @Transactional
    public AgentView create(Long spaceId, AgentSaveRequest request) {
        licenseService.ensureAgentCapacity();
        validateModels(spaceId, request.modelId(), request.fallbackModelId());
        Agent agent = new Agent();
        agent.setSpaceId(spaceId);
        agent.setPublishStatus(0);
        apply(agent, request);
        agentMapper.insert(agent);
        saveBindings(spaceId, agent.getId(), request.knowledgeBaseIds(), request.mcpIds(), request.skillIds());
        return view(agent);
    }

    @Transactional
    public AgentView update(Long spaceId, Long id, AgentSaveRequest request) {
        Agent agent = require(spaceId, id);
        validateModels(spaceId, request.modelId(), request.fallbackModelId());
        apply(agent, request);
        OptimisticLocks.requireUpdated(agentMapper.updateById(agent), "智能体");
        saveBindings(spaceId, agent.getId(), request.knowledgeBaseIds(), request.mcpIds(), request.skillIds());
        return view(agent);
    }

    /**
     * The agent row is archived rather than removed, so existing sessions keep their
     * history and can still resolve the agent name after deletion.
     */
    @Transactional
    public void delete(Long spaceId, Long id) {
        Agent agent = require(spaceId, id);
        if (isPublished(agent)) {
            throw BizException.conflict("请先下架智能体后再删除");
        }
        agentKnowledgeMapper.delete(new LambdaQueryWrapper<AgentKnowledge>().eq(AgentKnowledge::getAgentId, id));
        agentMcpMapper.delete(new LambdaQueryWrapper<AgentMcp>().eq(AgentMcp::getAgentId, id));
        agentSkillMapper.delete(new LambdaQueryWrapper<AgentSkill>().eq(AgentSkill::getAgentId, id));
        agentMapper.softDeleteById(id);
    }

    /**
     * Legacy direct publish. When approvals are enforced this endpoint is closed and callers
     * must go through {@code AgentReleaseService}.
     */
    @Transactional
    public AgentView publish(Long spaceId, Long id) {
        if (properties.getRelease().isRequireApproval()) {
            throw BizException.conflict("发布需要审批，请通过发布流程提交版本");
        }
        Agent agent = require(spaceId, id);
        validateForRelease(agent);
        if (Texts.isBlank(agent.getPublicId())) {
            agent.setPublicId(Ids.publicId());
        }
        AgentSnapshot snapshot = snapshotOf(agent);
        int versionNo = nextVersionNo(agent.getId());
        createVersion(agent, snapshot, ReleaseState.PUBLISHED, null);
        agent.setPublishStatus(1);
        agent.setPublishedVersion(versionNo);
        OptimisticLocks.requireUpdated(agentMapper.updateById(agent), "智能体");
        archiveOtherPublished(agent.getId(), versionNo);
        return view(agent);
    }

    @Transactional
    public AgentView unpublish(Long spaceId, Long id) {
        Agent agent = require(spaceId, id);
        agent.setPublishStatus(0);
        OptimisticLocks.requireUpdated(agentMapper.updateById(agent), "智能体");
        return view(agent);
    }

    public List<AgentVersionView> versions(Long spaceId, Long id) {
        Agent agent = require(spaceId, id);
        Integer current = agent.getPublishedVersion();
        return agentVersionMapper.selectList(new LambdaQueryWrapper<AgentVersion>()
                        .eq(AgentVersion::getAgentId, id)
                        .orderByDesc(AgentVersion::getVersionNo))
                .stream()
                .map(version -> AgentVersionView.of(version,
                        current != null && current.equals(version.getVersionNo())))
                .toList();
    }

    /**
     * Restores a snapshot as a new draft configuration. If the agent is currently published,
     * the restored configuration is immediately published as a new immutable version.
     */
    @Transactional
    public AgentView rollback(Long spaceId, Long id, Integer versionNo) {
        Agent agent = require(spaceId, id);
        if (versionNo == null) {
            throw BizException.badRequest("缺少版本号");
        }
        AgentVersion version = agentVersionMapper.selectOne(new LambdaQueryWrapper<AgentVersion>()
                .eq(AgentVersion::getAgentId, id)
                .eq(AgentVersion::getVersionNo, versionNo));
        if (version == null) {
            throw BizException.notFound("指定版本不存在");
        }
        AgentSnapshot snapshot = readSnapshot(version);
        validateModels(spaceId, snapshot.modelId(), snapshot.fallbackModelId());
        applySnapshot(agent, snapshot);
        if (isPublished(agent)) {
            int next = createVersion(agent, snapshot, ReleaseState.PUBLISHED, null).getVersionNo();
            agent.setPublishedVersion(next);
            archiveOtherPublished(id, next);
        }
        OptimisticLocks.requireUpdated(agentMapper.updateById(agent), "智能体");
        saveBindings(spaceId, id, snapshot.knowledgeBaseIds(), snapshot.mcpIds(), snapshot.skillIds());
        return view(agent);
    }

    public Agent requirePublished(String publicId) {
        if (Texts.isBlank(publicId)) {
            throw BizException.notFound("智能体不存在或已下架");
        }
        Agent agent = agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getPublicId, publicId.trim()));
        if (agent == null || !isPublished(agent) || agent.getStatus() == null || agent.getStatus() != 0) {
            throw BizException.notFound("智能体不存在或已下架");
        }
        // An archived space must stop serving public traffic even if its agents stay published.
        if (spaceMapper.selectById(agent.getSpaceId()) == null) {
            throw BizException.notFound("智能体不存在或已下架");
        }
        return agent;
    }

    /**
     * Published agents created before immutable snapshots existed have no version row yet.
     * They are captured once at startup so public traffic never runs on editable configuration.
     */
    public List<Long> legacyPublicationAgentIds() {
        return agentMapper.selectList(new LambdaQueryWrapper<Agent>()
                        .eq(Agent::getPublishStatus, 1)
                        .and(wrapper -> wrapper.isNull(Agent::getPublishedVersion)
                                .or().le(Agent::getPublishedVersion, 0)))
                .stream()
                .map(Agent::getId)
                .toList();
    }

    @Transactional
    public boolean backfillLegacyPublication(Long agentId) {
        Agent agent = agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getId, agentId)
                .last("FOR UPDATE"));
        if (agent == null || !isPublished(agent)
                || (agent.getPublishedVersion() != null && agent.getPublishedVersion() > 0)) {
            return false;
        }
        AgentSnapshot snapshot = snapshotOf(agent);
        int versionNo = createVersion(agent, snapshot, ReleaseState.PUBLISHED, null).getVersionNo();
        agent.setPublishedVersion(versionNo);
        OptimisticLocks.requireUpdated(agentMapper.updateById(agent), "智能体");
        archiveOtherPublished(agent.getId(), versionNo);
        log.info("Backfilled immutable publication v{} for legacy published agent {}", versionNo, agentId);
        return true;
    }

    /**
     * Public chats must run on the exact snapshot that was published, never on the editable row.
     */
    public AgentRuntime publishedRuntime(String publicId) {
        return publishedRuntime(publicId, null);
    }

    /**
     * Resolves the runtime for a public visitor. Visitors in the canary bucket see the canary
     * snapshot; everyone else stays on the stable published snapshot.
     */
    public AgentRuntime publishedRuntime(String publicId, String visitorId) {
        Agent agent = requirePublished(publicId);
        AgentVersion canary = canaryFor(agent, visitorId);
        if (canary != null) {
            try {
                return AgentRuntime.published(agent, readSnapshot(canary));
            } catch (RuntimeException ex) {
                log.warn("Canary snapshot v{} for agent {} is unusable, falling back: {}",
                        canary.getVersionNo(), agent.getId(), ex.getMessage());
            }
        }
        Integer publishedVersion = agent.getPublishedVersion();
        if (publishedVersion == null || publishedVersion <= 0) {
            throw BizException.conflict("智能体发布快照缺失，请重新发布后再访问");
        }
        AgentVersion version = agentVersionMapper.selectOne(new LambdaQueryWrapper<AgentVersion>()
                .eq(AgentVersion::getAgentId, agent.getId())
                .eq(AgentVersion::getVersionNo, publishedVersion));
        if (version == null) {
            log.error("Published snapshot v{} for agent {} is missing", publishedVersion, agent.getId());
            throw BizException.conflict("智能体发布快照缺失，请重新发布后再访问");
        }
        return AgentRuntime.published(agent, readSnapshot(version));
    }

    private AgentVersion canaryFor(Agent agent, String visitorId) {
        AgentVersion canary = agentVersionMapper.selectOne(new LambdaQueryWrapper<AgentVersion>()
                .eq(AgentVersion::getAgentId, agent.getId())
                .eq(AgentVersion::getStatus, ReleaseState.CANARY.name())
                .orderByDesc(AgentVersion::getVersionNo)
                .last("LIMIT 1"));
        if (canary == null || !CanaryRouting.selected(visitorId, agent.getId(), canary.getCanaryPercent())) {
            return null;
        }
        return canary;
    }

    public AgentRuntime liveRuntime(Agent agent) {
        return AgentRuntime.live(agent, bindings(agent.getId()));
    }

    public AgentBindings bindings(Long agentId) {
        List<Long> knowledgeIds = agentKnowledgeMapper.selectList(new LambdaQueryWrapper<AgentKnowledge>()
                        .eq(AgentKnowledge::getAgentId, agentId))
                .stream().map(AgentKnowledge::getKnowledgeBaseId).toList();
        List<Long> mcpIds = agentMcpMapper.selectList(new LambdaQueryWrapper<AgentMcp>()
                        .eq(AgentMcp::getAgentId, agentId))
                .stream().map(AgentMcp::getMcpId).toList();
        List<Long> skillIds = agentSkillMapper.selectList(new LambdaQueryWrapper<AgentSkill>()
                        .eq(AgentSkill::getAgentId, agentId))
                .stream().map(AgentSkill::getSkillId).toList();
        return new AgentBindings(knowledgeIds, mcpIds, skillIds);
    }

    public AgentView view(Agent agent) {
        return view(agent, true);
    }

    private AgentView view(Agent agent, boolean includeSystemPrompt) {
        SupplierModel model = modelMapper.selectById(agent.getModelId());
        return view(agent, model, bindings(agent.getId()), includeSystemPrompt);
    }

    private AgentView view(Agent agent, SupplierModel model, AgentBindings bindings,
                           boolean includeSystemPrompt) {
        return new AgentView(agent.getId(), agent.getSpaceId(), agent.getName(), agent.getIntro(),
                agent.getModelId(), model == null ? "-" : model.getName(),
                model == null ? "" : model.getModel(),
                includeSystemPrompt ? agent.getSystemPrompt() : "", agent.getTemperature(),
                agent.getMaxTokens(), agent.getPublishStatus(), agent.getPublicId(), agent.getStatus(),
                agent.getPublishedVersion(), parseTools(agent.getAllowedTools()),
                normalizeApprovalMode(agent.getToolApprovalMode()), agent.getFallbackModelId(),
                bindings.knowledgeBaseIds(), bindings.mcpIds(), bindings.skillIds(),
                agent.getHistoryWindow(),
                agent.getCreatedAt(), agent.getUpdatedAt());
    }

    private void apply(Agent agent, AgentSaveRequest request) {
        agent.setName(request.name().trim());
        agent.setIntro(Texts.trimTo(request.intro(), 500));
        agent.setModelId(request.modelId());
        agent.setSystemPrompt(Texts.trimTo(request.systemPrompt(), 50000));
        agent.setTemperature(request.temperature() == null ? DEFAULT_TEMPERATURE : request.temperature());
        agent.setMaxTokens(request.maxTokens() == null ? 2048 : request.maxTokens());
        agent.setStatus(request.status() == null || request.status() == 0 ? 0 : 1);
        agent.setAllowedTools(writeTools(request.allowedTools()));
        agent.setToolApprovalMode(normalizeApprovalMode(request.toolApprovalMode()));
        agent.setFallbackModelId(request.fallbackModelId());
        agent.setHistoryWindow(request.historyWindow());
    }

    private void applySnapshot(Agent agent, AgentSnapshot snapshot) {
        agent.setName(snapshot.name());
        agent.setIntro(snapshot.intro());
        agent.setModelId(snapshot.modelId());
        agent.setSystemPrompt(snapshot.systemPrompt());
        agent.setTemperature(snapshot.temperature());
        agent.setMaxTokens(snapshot.maxTokens());
        agent.setAllowedTools(snapshot.allowedTools());
        agent.setToolApprovalMode(normalizeApprovalMode(snapshot.toolApprovalMode()));
        agent.setFallbackModelId(snapshot.fallbackModelId());
        agent.setHistoryWindow(snapshot.historyWindow());
    }

    private void validateModels(Long spaceId, Long modelId, Long fallbackModelId) {
        modelRuntimeService.resolveChat(spaceId, modelId);
        if (fallbackModelId != null) {
            if (Objects.equals(fallbackModelId, modelId)) {
                throw BizException.badRequest("降级模型不能与主模型相同");
            }
            modelRuntimeService.resolveChat(spaceId, fallbackModelId);
        }
    }

    public AgentSnapshot snapshotOf(Agent agent) {
        AgentBindings bindings = bindings(agent.getId());
        return new AgentSnapshot(agent.getName(), agent.getIntro(), agent.getModelId(),
                agent.getSystemPrompt(), agent.getTemperature(), agent.getMaxTokens(),
                agent.getAllowedTools(), normalizeApprovalMode(agent.getToolApprovalMode()),
                agent.getFallbackModelId(), bindings.knowledgeBaseIds(), bindings.mcpIds(),
                bindings.skillIds(), agent.getHistoryWindow());
    }

    public AgentVersion createVersion(Agent agent, AgentSnapshot snapshot, ReleaseState state,
                                      Long requestedBy) {
        String configJson = writeSnapshot(snapshot);
        AgentVersion version = new AgentVersion();
        version.setAgentId(agent.getId());
        version.setSpaceId(agent.getSpaceId());
        version.setVersionNo(nextVersionNo(agent.getId()));
        version.setConfigJson(configJson);
        version.setStatus(state.name());
        version.setCanaryPercent(0);
        version.setConfigHash(sha256(configJson));
        if (requestedBy != null) {
            version.setRequestedBy(requestedBy);
            version.setRequestedAt(LocalDateTime.now());
        }
        if (state == ReleaseState.PUBLISHED) {
            version.setPublishedAt(LocalDateTime.now());
        }
        agentVersionMapper.insert(version);
        return version;
    }

    public AgentVersion requireVersion(Long spaceId, Long agentId, Integer versionNo) {
        if (versionNo == null) {
            throw BizException.badRequest("缺少版本号");
        }
        Agent agent = require(spaceId, agentId);
        AgentVersion version = agentVersionMapper.selectOne(new LambdaQueryWrapper<AgentVersion>()
                .eq(AgentVersion::getAgentId, agent.getId())
                .eq(AgentVersion::getVersionNo, versionNo));
        if (version == null) {
            throw BizException.notFound("指定版本不存在");
        }
        return version;
    }

    public void validateForRelease(Agent agent) {
        if (agent.getStatus() != null && agent.getStatus() != 0) {
            throw BizException.badRequest("智能体已停用，无法发布");
        }
        if (Texts.isBlank(agent.getSystemPrompt())) {
            throw BizException.badRequest("发布前请先配置系统提示词");
        }
        validateModels(agent.getSpaceId(), agent.getModelId(), agent.getFallbackModelId());
    }

    /**
     * Marks the version as the active publication and archives the previous one.
     */
    public void publishVersion(Agent agent, AgentVersion version) {
        if (Texts.isBlank(agent.getPublicId())) {
            agent.setPublicId(Ids.publicId());
        }
        agent.setPublishStatus(1);
        agent.setPublishedVersion(version.getVersionNo());
        OptimisticLocks.requireUpdated(agentMapper.updateById(agent), "智能体");
        archiveOtherPublished(agent.getId(), version.getVersionNo());
    }

    private void archiveOtherPublished(Long agentId, int keepVersionNo) {
        agentVersionMapper.update(null, new LambdaUpdateWrapper<AgentVersion>()
                .eq(AgentVersion::getAgentId, agentId)
                .eq(AgentVersion::getStatus, ReleaseState.PUBLISHED.name())
                .ne(AgentVersion::getVersionNo, keepVersionNo)
                .set(AgentVersion::getStatus, ReleaseState.ARCHIVED.name())
                .set(AgentVersion::getUpdatedAt, LocalDateTime.now()));
    }

    private int nextVersionNo(Long agentId) {
        AgentVersion latest = agentVersionMapper.selectOne(new LambdaQueryWrapper<AgentVersion>()
                .eq(AgentVersion::getAgentId, agentId)
                .orderByDesc(AgentVersion::getVersionNo)
                .last("LIMIT 1"));
        return latest == null || latest.getVersionNo() == null ? 1 : latest.getVersionNo() + 1;
    }

    private String writeSnapshot(AgentSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception ex) {
            throw new BizException(500, "无法保存智能体快照");
        }
    }

    private AgentSnapshot readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, AgentSnapshot.class);
        } catch (Exception ex) {
            throw new BizException(500, "智能体快照已损坏，无法读取");
        }
    }

    public AgentSnapshot readSnapshot(AgentVersion version) {
        AgentSnapshot snapshot = readSnapshot(version.getConfigJson());
        if (version.getConfigHash() != null
                && !version.getConfigHash().equals(sha256(version.getConfigJson()))) {
            throw BizException.conflict("版本快照校验失败，请重新发布");
        }
        return snapshot;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String writeTools(List<String> tools) {
        List<String> normalized = normalizeTools(tools);
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception ex) {
            throw BizException.badRequest("工具白名单格式不正确");
        }
    }

    private List<String> parseTools(String json) {
        if (Texts.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static List<String> normalizeTools(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String tool : tools) {
            if (Texts.isBlank(tool)) {
                continue;
            }
            String value = tool.trim();
            if (value.length() > 120) {
                throw BizException.badRequest("工具名称不能超过 120 个字符");
            }
            values.add(value);
        }
        return new ArrayList<>(values);
    }

    private static String normalizeApprovalMode(String mode) {
        if (Texts.isBlank(mode)) {
            return DEFAULT_APPROVAL_MODE;
        }
        String value = mode.trim().toUpperCase(Locale.ROOT);
        if (!"NONE".equals(value) && !"WRITE".equals(value) && !"ALL".equals(value)) {
            throw BizException.badRequest("工具审批模式必须为 NONE、WRITE 或 ALL");
        }
        return value;
    }

    private static boolean isPublished(Agent agent) {
        return agent.getPublishStatus() != null && agent.getPublishStatus() == 1;
    }

    private void saveBindings(Long spaceId, Long agentId, List<Long> knowledgeIds,
                              List<Long> mcpIds, List<Long> skillIds) {
        if (knowledgeIds != null) {
            agentKnowledgeMapper.delete(new LambdaQueryWrapper<AgentKnowledge>()
                    .eq(AgentKnowledge::getAgentId, agentId));
            for (Long knowledgeBaseId : validateIds(knowledgeIds, "知识库")) {
                long exists = knowledgeBaseMapper.selectCount(new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, knowledgeBaseId)
                        .eq(KnowledgeBase::getSpaceId, spaceId));
                if (exists == 0) {
                    throw BizException.badRequest("包含无效的知识库");
                }
                AgentKnowledge link = new AgentKnowledge();
                link.setAgentId(agentId);
                link.setKnowledgeBaseId(knowledgeBaseId);
                agentKnowledgeMapper.insert(link);
            }
        }
        if (mcpIds != null) {
            agentMcpMapper.delete(new LambdaQueryWrapper<AgentMcp>().eq(AgentMcp::getAgentId, agentId));
            for (Long mcpId : validateIds(mcpIds, "MCP 服务")) {
                long exists = mcpServerMapper.selectCount(new LambdaQueryWrapper<McpServer>()
                        .eq(McpServer::getId, mcpId)
                        .eq(McpServer::getSpaceId, spaceId));
                if (exists == 0) {
                    throw BizException.badRequest("包含无效的 MCP 服务");
                }
                AgentMcp link = new AgentMcp();
                link.setAgentId(agentId);
                link.setMcpId(mcpId);
                agentMcpMapper.insert(link);
            }
        }
        if (skillIds != null) {
            agentSkillMapper.delete(new LambdaQueryWrapper<AgentSkill>().eq(AgentSkill::getAgentId, agentId));
            for (Long skillId : validateIds(skillIds, "技能")) {
                long exists = skillMapper.selectCount(new LambdaQueryWrapper<Skill>()
                        .eq(Skill::getId, skillId)
                        .eq(Skill::getSpaceId, spaceId));
                if (exists == 0) {
                    throw BizException.badRequest("包含无效的技能");
                }
                AgentSkill link = new AgentSkill();
                link.setAgentId(agentId);
                link.setSkillId(skillId);
                agentSkillMapper.insert(link);
            }
        }
    }

    private static List<Long> validateIds(List<Long> ids, String label) {
        Set<Long> values = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null) {
                throw BizException.badRequest(label + " ID 不能为空");
            }
            values.add(id);
        }
        return new ArrayList<>(values);
    }

    private Map<Long, SupplierModel> modelMap(List<Long> modelIds) {
        if (modelIds.isEmpty()) {
            return Map.of();
        }
        return modelMapper.selectBatchIds(modelIds.stream().filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(SupplierModel::getId, model -> model));
    }

    private Map<Long, AgentBindings> bindingsFor(List<Long> agentIds) {
        Map<Long, List<Long>> knowledge = new HashMap<>();
        Map<Long, List<Long>> mcps = new HashMap<>();
        Map<Long, List<Long>> skills = new HashMap<>();
        agentKnowledgeMapper.selectList(new LambdaQueryWrapper<AgentKnowledge>()
                        .in(AgentKnowledge::getAgentId, agentIds))
                .forEach(link -> knowledge.computeIfAbsent(link.getAgentId(), key -> new ArrayList<>())
                        .add(link.getKnowledgeBaseId()));
        agentMcpMapper.selectList(new LambdaQueryWrapper<AgentMcp>()
                        .in(AgentMcp::getAgentId, agentIds))
                .forEach(link -> mcps.computeIfAbsent(link.getAgentId(), key -> new ArrayList<>())
                        .add(link.getMcpId()));
        agentSkillMapper.selectList(new LambdaQueryWrapper<AgentSkill>()
                        .in(AgentSkill::getAgentId, agentIds))
                .forEach(link -> skills.computeIfAbsent(link.getAgentId(), key -> new ArrayList<>())
                        .add(link.getSkillId()));
        Map<Long, AgentBindings> result = new HashMap<>();
        for (Long agentId : agentIds) {
            result.put(agentId, new AgentBindings(knowledge.getOrDefault(agentId, List.of()),
                    mcps.getOrDefault(agentId, List.of()), skills.getOrDefault(agentId, List.of())));
        }
        return result;
    }

    private static AgentBindings emptyBindings() {
        return new AgentBindings(List.of(), List.of(), List.of());
    }
}
