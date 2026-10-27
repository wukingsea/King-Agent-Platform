package com.king.ai.compliance.retention;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.king.ai.audit.AuditLogService;
import com.king.ai.common.BizException;
import com.king.ai.common.OptimisticLocks;
import com.king.ai.compliance.dto.RetentionPolicyUpdateRequest;
import com.king.ai.compliance.dto.RetentionPolicyView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RetentionPolicyService {

    private static final int MIN_RETENTION_DAYS = 1;
    private static final int MAX_RETENTION_DAYS = 3650;

    private final RetentionPolicyMapper mapper;
    private final AuditLogService auditLogService;

    public List<RetentionPolicyView> list(Long spaceId) {
        return mapper.selectList(new LambdaQueryWrapper<RetentionPolicy>()
                        .eq(RetentionPolicy::getSpaceId, spaceId)
                        .orderByAsc(RetentionPolicy::getResourceType))
                .stream().map(this::view).toList();
    }

    @Transactional
    public RetentionPolicyView upsert(Long spaceId, RetentionPolicyUpdateRequest request,
                                      Long operatorId) {
        RetentionType type = parse(request.resourceType());
        if (request.retentionDays() == null || request.retentionDays() < MIN_RETENTION_DAYS
                || request.retentionDays() > MAX_RETENTION_DAYS) {
            throw BizException.badRequest("保留天数必须在 1–3650 之间");
        }
        boolean archive = !Boolean.FALSE.equals(request.archiveBeforeDelete());
        if (archive && (type == RetentionType.MESSAGE || type == RetentionType.AUDIT)) {
            // 当前仅 SESSION 支持删除前归档，避免配置被静默忽略后直接删除数据。
            throw BizException.badRequest("MESSAGE/AUDIT 保留策略暂不支持删除前归档");
        }
        RetentionPolicy policy = mapper.selectOne(new LambdaQueryWrapper<RetentionPolicy>()
                .eq(RetentionPolicy::getSpaceId, spaceId)
                .eq(RetentionPolicy::getResourceType, type.name()).last("LIMIT 1"));
        boolean insert = policy == null;
        if (insert) {
            policy = new RetentionPolicy();
            policy.setSpaceId(spaceId);
            policy.setResourceType(type.name());
        }
        policy.setRetentionDays(request.retentionDays());
        policy.setEnabled(Boolean.FALSE.equals(request.enabled()) ? 0 : 1);
        policy.setArchiveBeforeDelete(Boolean.FALSE.equals(request.archiveBeforeDelete()) ? 0 : 1);
        if (insert) {
            mapper.insert(policy);
        } else {
            OptimisticLocks.requireUpdated(mapper.updateById(policy), "保留策略");
        }
        auditLogService.recordEvent(spaceId, operatorId, "USER", "compliance.retention_policy_update",
                "retention_policy", type.name(), AuditLogService.STATUS_SUCCESS,
                "days=" + policy.getRetentionDays() + ", enabled=" + policy.getEnabled()
                        + ", archive=" + policy.getArchiveBeforeDelete(), null, null, null);
        return view(policy);
    }

    private RetentionPolicyView view(RetentionPolicy policy) {
        return new RetentionPolicyView(policy.getId(), policy.getResourceType(),
                policy.getRetentionDays(),
                policy.getEnabled() != null && policy.getEnabled() == 1,
                policy.getArchiveBeforeDelete() != null && policy.getArchiveBeforeDelete() == 1);
    }

    public List<RetentionPolicy> enabledPolicies(Long spaceId) {
        return mapper.selectList(new LambdaQueryWrapper<RetentionPolicy>()
                .eq(RetentionPolicy::getSpaceId, spaceId)
                .eq(RetentionPolicy::getEnabled, 1));
    }

    public List<Long> spacesWithPolicies() {
        return mapper.selectList(new LambdaQueryWrapper<RetentionPolicy>()
                        .select(RetentionPolicy::getSpaceId)
                        .eq(RetentionPolicy::getEnabled, 1))
                .stream().map(RetentionPolicy::getSpaceId).distinct().toList();
    }

    private static RetentionType parse(String value) {
        try {
            return RetentionType.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw BizException.badRequest("保留类型仅支持 SESSION/MESSAGE/AUDIT/EXPORT");
        }
    }
}
