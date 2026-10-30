package com.king.ai.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.king.ai.agent.entity.Agent;
import com.king.ai.agent.entity.AgentEvalCase;
import com.king.ai.agent.entity.AgentVersion;
import com.king.ai.agent.dto.AgentVersionView;
import com.king.ai.agent.mapper.AgentEvalCaseMapper;
import com.king.ai.agent.mapper.AgentVersionMapper;
import com.king.ai.agent.release.ReleaseState;
import com.king.ai.agent.release.dto.AgentEvalCaseSaveRequest;
import com.king.ai.agent.release.dto.AgentEvalCaseView;
import com.king.ai.audit.AuditLogService;
import com.king.ai.common.BizException;
import com.king.ai.common.Texts;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.UserPrincipal;
import com.king.ai.config.KingAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentReleaseService {

    private final AgentService agentService;
    private final AgentVersionMapper versionMapper;
    private final AgentEvalCaseMapper evalCaseMapper;
    private final EvaluationService evaluationService;
    private final AuditLogService auditLogService;
    private final KingAiProperties properties;

    @Transactional
    public AgentVersionView submit(Long spaceId, Long agentId) {
        Agent agent = agentService.require(spaceId, agentId);
        agentService.validateForRelease(agent);
        long pending = versionMapper.selectCount(new LambdaQueryWrapper<AgentVersion>()
                .eq(AgentVersion::getAgentId, agentId)
                .eq(AgentVersion::getStatus, ReleaseState.PENDING_REVIEW.name()));
        if (pending > 0) {
            throw BizException.conflict("该智能体已有待审批的发布版本");
        }
        UserPrincipal actor = RequestContext.require();
        AgentVersion version = agentService.createVersion(agent, agentService.snapshotOf(agent),
                ReleaseState.PENDING_REVIEW, actor.id());
        audit(spaceId, agentId, "agent.release_submit", version,
                "提交发布 v" + version.getVersionNo());
        if (properties.getRelease().isRequireApproval()) {
            return view(agent, version);
        }
        version.setStatus(ReleaseState.APPROVED.name());
        version.setApprovedBy(actor.id());
        version.setApprovedAt(LocalDateTime.now());
        version.setReviewComment("自动审批（发布审批未启用）");
        versionMapper.updateById(version);
        return publish(agent, version);
    }

    @Transactional
    public AgentVersionView approve(Long spaceId, Long agentId, Integer versionNo, String comment) {
        Agent agent = agentService.require(spaceId, agentId);
        AgentVersion version = agentService.requireVersion(spaceId, agentId, versionNo);
        requireState(version, ReleaseState.PENDING_REVIEW);
        UserPrincipal actor = RequestContext.require();
        if (Objects.equals(actor.id(), version.getRequestedBy())) {
            throw BizException.forbidden("不能审批自己提交的发布版本");
        }
        requireEvaluationPassed(version);
        versionMapper.update(null, new LambdaUpdateWrapper<AgentVersion>()
                .eq(AgentVersion::getId, version.getId())
                .eq(AgentVersion::getStatus, ReleaseState.PENDING_REVIEW.name())
                .set(AgentVersion::getStatus, ReleaseState.APPROVED.name())
                .set(AgentVersion::getApprovedBy, actor.id())
                .set(AgentVersion::getApprovedAt, LocalDateTime.now())
                .set(AgentVersion::getReviewComment, Texts.trimTo(comment, 1000))
                .set(AgentVersion::getUpdatedAt, LocalDateTime.now()));
        audit(spaceId, agentId, "agent.release_approve", version, "审批通过 v" + versionNo);
        return view(agent, reload(version));
    }

    @Transactional
    public AgentVersionView reject(Long spaceId, Long agentId, Integer versionNo, String comment) {
        Agent agent = agentService.require(spaceId, agentId);
        AgentVersion version = agentService.requireVersion(spaceId, agentId, versionNo);
        requireState(version, ReleaseState.PENDING_REVIEW);
        if (Texts.isBlank(comment)) {
            throw BizException.badRequest("请填写驳回原因");
        }
        UserPrincipal actor = RequestContext.require();
        versionMapper.update(null, new LambdaUpdateWrapper<AgentVersion>()
                .eq(AgentVersion::getId, version.getId())
                .eq(AgentVersion::getStatus, ReleaseState.PENDING_REVIEW.name())
                .set(AgentVersion::getStatus, ReleaseState.REJECTED.name())
                .set(AgentVersion::getApprovedBy, actor.id())
                .set(AgentVersion::getApprovedAt, LocalDateTime.now())
                .set(AgentVersion::getReviewComment, Texts.trimTo(comment, 1000))
                .set(AgentVersion::getUpdatedAt, LocalDateTime.now()));
        audit(spaceId, agentId, "agent.release_reject", version, "驳回 v" + versionNo);
        return view(agent, reload(version));
    }

    @Transactional
    public AgentVersionView canary(Long spaceId, Long agentId, Integer versionNo, Integer percent) {
        Agent agent = agentService.require(spaceId, agentId);
        AgentVersion version = agentService.requireVersion(spaceId, agentId, versionNo);
        if (version.getStatus() == null
                || !(ReleaseState.APPROVED.name().equals(version.getStatus())
                || ReleaseState.CANARY.name().equals(version.getStatus()))) {
            throw BizException.conflict("仅已审批的版本可以进入灰度");
        }
        if (percent == null || percent < 1 || percent > 99) {
            throw BizException.badRequest("灰度比例必须在 1-99 之间");
        }
        long others = versionMapper.selectCount(new LambdaQueryWrapper<AgentVersion>()
                .eq(AgentVersion::getAgentId, agentId)
                .eq(AgentVersion::getStatus, ReleaseState.CANARY.name())
                .ne(AgentVersion::getId, version.getId()));
        if (others > 0) {
            throw BizException.conflict("该智能体已有灰度中的版本，请先发布或取消");
        }
        versionMapper.update(null, new LambdaUpdateWrapper<AgentVersion>()
                .eq(AgentVersion::getId, version.getId())
                .in(AgentVersion::getStatus, ReleaseState.APPROVED.name(), ReleaseState.CANARY.name())
                .set(AgentVersion::getStatus, ReleaseState.CANARY.name())
                .set(AgentVersion::getCanaryPercent, percent)
                .set(AgentVersion::getUpdatedAt, LocalDateTime.now()));
        audit(spaceId, agentId, "agent.release_canary", version,
                "灰度 " + percent + "% 于 v" + versionNo);
        return view(agent, reload(version));
    }

    @Transactional
    public AgentVersionView promote(Long spaceId, Long agentId, Integer versionNo) {
        Agent agent = agentService.require(spaceId, agentId);
        AgentVersion version = agentService.requireVersion(spaceId, agentId, versionNo);
        if (version.getStatus() == null
                || !(ReleaseState.APPROVED.name().equals(version.getStatus())
                || ReleaseState.CANARY.name().equals(version.getStatus()))) {
            throw BizException.conflict("仅已审批或灰度中的版本可以正式发布");
        }
        requireEvaluationPassed(version);
        int updated = versionMapper.update(null, new LambdaUpdateWrapper<AgentVersion>()
                .eq(AgentVersion::getId, version.getId())
                .in(AgentVersion::getStatus, ReleaseState.APPROVED.name(), ReleaseState.CANARY.name())
                .set(AgentVersion::getStatus, ReleaseState.PUBLISHED.name())
                .set(AgentVersion::getCanaryPercent, 0)
                .set(AgentVersion::getPublishedAt, LocalDateTime.now())
                .set(AgentVersion::getUpdatedAt, LocalDateTime.now()));
        if (updated == 0) {
            throw BizException.conflict("版本状态已变化，请刷新后重试");
        }
        agentService.publishVersion(agent, version);
        audit(spaceId, agentId, "agent.release_promote", version, "发布 v" + versionNo);
        return view(agent, reload(version));
    }

    @Transactional
    public AgentVersionView evaluate(Long spaceId, Long agentId, Integer versionNo) {
        Agent agent = agentService.require(spaceId, agentId);
        AgentVersion version = agentService.requireVersion(spaceId, agentId, versionNo);
        if (ReleaseState.PUBLISHED.name().equals(version.getStatus())
                || ReleaseState.ARCHIVED.name().equals(version.getStatus())
                || ReleaseState.REJECTED.name().equals(version.getStatus())) {
            throw BizException.badRequest("该版本不支持重新评测");
        }
        List<AgentEvalCase> cases = enabledCases(agentId);
        EvaluationService.Outcome outcome = evaluationService.evaluate(agent,
                agentService.readSnapshot(version), cases);
        version.setEvalTotal(outcome.total());
        version.setEvalPassed(outcome.passed());
        version.setEvalScore(outcome.score());
        version.setEvalSummary(outcome.summary());
        version.setEvalRunAt(LocalDateTime.now());
        versionMapper.updateById(version);
        audit(spaceId, agentId, "agent.release_evaluate", version,
                "评测 v" + versionNo + "：" + outcome.summary());
        return view(agent, reload(version));
    }

    public List<AgentEvalCaseView> evalCases(Long spaceId, Long agentId) {
        agentService.require(spaceId, agentId);
        return evalCaseMapper.selectList(new LambdaQueryWrapper<AgentEvalCase>()
                        .eq(AgentEvalCase::getAgentId, agentId)
                        .orderByAsc(AgentEvalCase::getId))
                .stream().map(AgentEvalCaseView::of).toList();
    }

    @Transactional
    public AgentEvalCaseView createEvalCase(Long spaceId, Long agentId, AgentEvalCaseSaveRequest request) {
        agentService.require(spaceId, agentId);
        long count = evalCaseMapper.selectCount(new LambdaQueryWrapper<AgentEvalCase>()
                .eq(AgentEvalCase::getAgentId, agentId));
        if (count >= properties.getRelease().getMaxEvalCases()) {
            throw BizException.badRequest("评测用例数量已达上限 "
                    + properties.getRelease().getMaxEvalCases());
        }
        AgentEvalCase evalCase = new AgentEvalCase();
        evalCase.setSpaceId(spaceId);
        evalCase.setAgentId(agentId);
        apply(evalCase, request);
        evalCaseMapper.insert(evalCase);
        audit(spaceId, agentId, "agent.eval_case_create", null, "新增评测用例：" + evalCase.getName());
        return AgentEvalCaseView.of(evalCase);
    }

    @Transactional
    public AgentEvalCaseView updateEvalCase(Long spaceId, Long agentId, Long caseId,
                                            AgentEvalCaseSaveRequest request) {
        AgentEvalCase evalCase = requireCase(spaceId, agentId, caseId);
        apply(evalCase, request);
        evalCaseMapper.updateById(evalCase);
        audit(spaceId, agentId, "agent.eval_case_update", null, "更新评测用例：" + evalCase.getName());
        return AgentEvalCaseView.of(evalCase);
    }

    @Transactional
    public void deleteEvalCase(Long spaceId, Long agentId, Long caseId) {
        AgentEvalCase evalCase = requireCase(spaceId, agentId, caseId);
        evalCaseMapper.deleteById(caseId);
        audit(spaceId, agentId, "agent.eval_case_delete", null, "删除评测用例：" + evalCase.getName());
    }

    private AgentEvalCase requireCase(Long spaceId, Long agentId, Long caseId) {
        agentService.require(spaceId, agentId);
        AgentEvalCase evalCase = evalCaseMapper.selectById(caseId);
        if (evalCase == null || !Objects.equals(evalCase.getAgentId(), agentId)
                || !Objects.equals(evalCase.getSpaceId(), spaceId)) {
            throw BizException.notFound("评测用例不存在");
        }
        return evalCase;
    }

    private static void apply(AgentEvalCase evalCase, AgentEvalCaseSaveRequest request) {
        evalCase.setName(request.name().trim());
        evalCase.setPrompt(Texts.trimTo(request.prompt(), 10000));
        evalCase.setExpectedKeywords(Texts.trimTo(request.expectedKeywords(), 2000));
        evalCase.setWeight(request.weight() == null ? 1 : request.weight());
        evalCase.setStatus(request.status() == null || request.status() == 0 ? 0 : 1);
    }

    private List<AgentEvalCase> enabledCases(Long agentId) {
        return evalCaseMapper.selectList(new LambdaQueryWrapper<AgentEvalCase>()
                .eq(AgentEvalCase::getAgentId, agentId)
                .eq(AgentEvalCase::getStatus, 0)
                .orderByAsc(AgentEvalCase::getId));
    }

    private void requireEvaluationPassed(AgentVersion version) {
        if (enabledCases(version.getAgentId()).isEmpty()) {
            return;
        }
        if (version.getEvalRunAt() == null) {
            throw BizException.badRequest("存在启用的评测用例，请先运行评测再审批");
        }
        BigDecimal score = version.getEvalScore();
        double required = properties.getRelease().getEvalPassScore();
        if (score == null || score.doubleValue() + 1e-9 < required) {
            throw BizException.badRequest("评测得分未达到发布门槛 " + required);
        }
    }

    private static void requireState(AgentVersion version, ReleaseState expected) {
        if (!expected.name().equals(version.getStatus())) {
            throw BizException.conflict("当前版本状态为 " + version.getStatus() + "，无法执行该操作");
        }
    }

    private AgentVersion reload(AgentVersion version) {
        AgentVersion latest = versionMapper.selectById(version.getId());
        return latest == null ? version : latest;
    }

    private AgentVersionView view(Agent agent, AgentVersion version) {
        return AgentVersionView.of(version,
                Objects.equals(agent.getPublishedVersion(), version.getVersionNo()));
    }

    private AgentVersionView publish(Agent agent, AgentVersion version) {
        int updated = versionMapper.update(null, new LambdaUpdateWrapper<AgentVersion>()
                .eq(AgentVersion::getId, version.getId())
                .eq(AgentVersion::getStatus, ReleaseState.APPROVED.name())
                .set(AgentVersion::getStatus, ReleaseState.PUBLISHED.name())
                .set(AgentVersion::getPublishedAt, LocalDateTime.now())
                .set(AgentVersion::getUpdatedAt, LocalDateTime.now()));
        if (updated == 0) {
            throw BizException.conflict("版本状态已变化，请刷新后重试");
        }
        agentService.publishVersion(agent, version);
        audit(agent.getSpaceId(), agent.getId(), "agent.release_promote", version,
                "发布 v" + version.getVersionNo());
        return view(agent, reload(version));
    }

    private void audit(Long spaceId, Long agentId, String action, AgentVersion version, String detail) {
        UserPrincipal actor = RequestContext.get();
        String resourceId = version == null ? String.valueOf(agentId)
                : agentId + "#v" + version.getVersionNo();
        auditLogService.recordEvent(spaceId, actor == null ? null : actor.id(),
                actor == null ? "system" : actor.username(), action, "agent", resourceId,
                AuditLogService.STATUS_SUCCESS, detail, null, null, null);
    }
}
