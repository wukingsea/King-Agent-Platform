package com.king.ai.compliance.access;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.king.ai.audit.AuditLogService;
import com.king.ai.common.BizException;
import com.king.ai.common.OptimisticLocks;
import com.king.ai.common.Texts;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.common.security.UserPrincipal;
import com.king.ai.compliance.access.AccessReviewReportBuilder.AccessFinding;
import com.king.ai.compliance.access.AccessReviewReportBuilder.CredentialSnapshot;
import com.king.ai.compliance.access.AccessReviewReportBuilder.MemberSnapshot;
import com.king.ai.compliance.access.AccessReviewReportBuilder.PublicAgentSnapshot;
import com.king.ai.compliance.dto.AccessReviewDecisionRequest;
import com.king.ai.compliance.dto.AccessReviewItemView;
import com.king.ai.compliance.dto.AccessReviewView;
import com.king.ai.config.KingAiProperties;
import com.king.ai.system.SpaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AccessReviewService {

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_SUPERSEDED = "SUPERSEDED";
    private static final String DECISION_PENDING = "PENDING";

    private final AccessReviewMapper reviewMapper;
    private final AccessReviewItemMapper itemMapper;
    private final JdbcTemplate jdbcTemplate;
    private final SpaceService spaceService;
    private final AuditLogService auditLogService;
    private final KingAiProperties properties;

    @Transactional
    public AccessReviewView generate(Long spaceId, Long operatorId) {
        List<AccessReview> open = reviewMapper.selectList(new LambdaQueryWrapper<AccessReview>()
                .eq(AccessReview::getSpaceId, spaceId)
                .eq(AccessReview::getStatus, STATUS_OPEN));
        for (AccessReview previous : open) {
            previous.setStatus(STATUS_SUPERSEDED);
            reviewMapper.updateById(previous);
        }
        List<MemberSnapshot> members = jdbcTemplate.query(
                "SELECT us.user_id, u.username, u.nickname, us.role, u.last_login_at "
                        + "FROM king_ai_user_space us JOIN king_ai_user u ON u.id = us.user_id "
                        + "WHERE us.space_id = ? ORDER BY us.user_id",
                (rs, rowNum) -> new MemberSnapshot(rs.getLong("user_id"), rs.getString("username"),
                        rs.getString("nickname"), rs.getString("role"),
                        rs.getTimestamp("last_login_at") == null ? null
                                : rs.getTimestamp("last_login_at").toLocalDateTime()), spaceId);
        List<CredentialSnapshot> suppliers = jdbcTemplate.query(
                "SELECT id, name, COALESCE(api_key_rotated_at, updated_at) AS activity "
                        + "FROM king_ai_supplier WHERE space_id = ? ORDER BY id",
                (rs, rowNum) -> new CredentialSnapshot(rs.getLong("id"), rs.getString("name"),
                        rs.getTimestamp("activity") == null ? null
                                : rs.getTimestamp("activity").toLocalDateTime()), spaceId);
        List<CredentialSnapshot> mcps = jdbcTemplate.query(
                "SELECT id, name, updated_at AS activity FROM king_ai_mcp "
                        + "WHERE space_id = ? ORDER BY id",
                (rs, rowNum) -> new CredentialSnapshot(rs.getLong("id"), rs.getString("name"),
                        rs.getTimestamp("activity") == null ? null
                                : rs.getTimestamp("activity").toLocalDateTime()), spaceId);
        List<PublicAgentSnapshot> agents = jdbcTemplate.query(
                "SELECT id, name, public_id, updated_at FROM king_ai_agent "
                        + "WHERE space_id = ? AND deleted_at IS NULL "
                        + "AND publish_status = 1 AND public_id IS NOT NULL ORDER BY id",
                (rs, rowNum) -> new PublicAgentSnapshot(rs.getLong("id"), rs.getString("name"),
                        rs.getString("public_id"),
                        rs.getTimestamp("updated_at") == null ? null
                                : rs.getTimestamp("updated_at").toLocalDateTime()), spaceId);
        List<AccessFinding> findings = AccessReviewReportBuilder.build(members, suppliers, mcps,
                agents, LocalDateTime.now(),
                properties.getCompliance().getAccessReview().getStaleDays());

        AccessReview review = new AccessReview();
        review.setSpaceId(spaceId);
        review.setStatus(STATUS_OPEN);
        review.setGeneratedBy(operatorId);
        review.setGeneratedAt(LocalDateTime.now());
        review.setSummary("findings=" + findings.size());
        reviewMapper.insert(review);
        for (AccessFinding finding : findings) {
            AccessReviewItem item = new AccessReviewItem();
            item.setReviewId(review.getId());
            item.setSpaceId(spaceId);
            item.setSubjectType(finding.subjectType());
            item.setSubjectId(finding.subjectId());
            if ("PUBLIC_AGENT".equals(finding.subjectType())) {
                // public_id 是 32 位 UUID，而 role 列仅为 VARCHAR(20)，不能把公开标识塞进 role。
                // 改为在名称中保留完整 public_id，并将角色标记为 PUBLIC。
                String publicId = Texts.blankToDefault(finding.role(), "-");
                String name = Texts.blankToDefault(finding.name(), "-");
                item.setSubjectName(Texts.blankToDefault(
                        Texts.trimTo(name + " (" + publicId + ")", 200), "-"));
                item.setRole("PUBLIC");
            } else {
                item.setSubjectName(Texts.blankToDefault(Texts.trimTo(finding.name(), 200), "-"));
                item.setRole(Texts.trimTo(finding.role(), 20));
            }
            item.setLastActivityAt(finding.lastActivityAt());
            item.setFlags(String.join(",", finding.flags()));
            item.setDecision(DECISION_PENDING);
            itemMapper.insert(item);
        }
        auditLogService.recordEvent(spaceId, operatorId == null ? 0L : operatorId, "SYSTEM",
                "compliance.access_review_generate", "access_review",
                String.valueOf(review.getId()), AuditLogService.STATUS_SUCCESS,
                "findings=" + findings.size(), null, null, null);
        return get(spaceId, review.getId());
    }

    public List<AccessReviewView> list(Long spaceId) {
        return reviewMapper.selectList(new LambdaQueryWrapper<AccessReview>()
                        .eq(AccessReview::getSpaceId, spaceId)
                        .orderByDesc(AccessReview::getId))
                .stream().map(review -> get(spaceId, review.getId())).toList();
    }

    public AccessReviewView get(Long spaceId, Long reviewId) {
        AccessReview review = reviewMapper.selectById(reviewId);
        if (review == null || !Objects.equals(review.getSpaceId(), spaceId)) {
            throw BizException.notFound("访问复核不存在");
        }
        List<AccessReviewItemView> items = itemMapper.selectList(
                        new LambdaQueryWrapper<AccessReviewItem>()
                                .eq(AccessReviewItem::getReviewId, reviewId)
                                .orderByAsc(AccessReviewItem::getId))
                .stream().map(this::view).toList();
        return new AccessReviewView(review.getId(), review.getSpaceId(), review.getStatus(),
                review.getSummary(), review.getGeneratedBy(), review.getGeneratedAt(),
                review.getCompletedAt(), items);
    }

    @Transactional
    public AccessReviewItemView decide(Long spaceId, Long reviewId, Long itemId,
                                       AccessReviewDecisionRequest request, UserPrincipal operator) {
        // MySQL REPEATABLE READ 下事务的首次普通读取即固定快照；若先查复核再查复核项，
        // 两个并发的“最后一项”决策会各自看到对方仍为 PENDING，导致复核永远不完成。
        // 因此先用行锁串行化同一复核的决策，再读取事务内状态。
        List<Long> locked = jdbcTemplate.query(
                "SELECT id FROM king_ai_access_review WHERE id = ? AND space_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong(1), reviewId, spaceId);
        if (locked.isEmpty()) {
            throw BizException.notFound("访问复核不存在或已完成");
        }
        AccessReview review = reviewMapper.selectById(reviewId);
        if (review == null || !Objects.equals(review.getSpaceId(), spaceId)
                || !STATUS_OPEN.equals(review.getStatus())) {
            throw BizException.notFound("访问复核不存在或已完成");
        }
        AccessReviewItem item = itemMapper.selectById(itemId);
        if (item == null || !Objects.equals(item.getReviewId(), reviewId)) {
            throw BizException.notFound("复核项不存在");
        }
        if (!DECISION_PENDING.equals(item.getDecision())) {
            throw BizException.conflict("该复核项已决策");
        }
        String decision = AccessReviewDecisions.parse(request.decision());
        AccessReviewDecisions.validate(item.getSubjectType(), decision);
        if ("MEMBER".equals(item.getSubjectType())) {
            applyMemberDecision(spaceId, item, decision, operator);
        }
        item.setDecision(decision);
        item.setDecidedBy(operator.id());
        item.setDecidedAt(LocalDateTime.now());
        item.setNote(Texts.trimTo(request.note(), 500));
        OptimisticLocks.requireUpdated(itemMapper.updateById(item), "复核项");
        long pending = itemMapper.selectCount(new LambdaQueryWrapper<AccessReviewItem>()
                .eq(AccessReviewItem::getReviewId, reviewId)
                .eq(AccessReviewItem::getDecision, DECISION_PENDING));
        if (pending == 0) {
            review.setStatus(STATUS_COMPLETED);
            review.setCompletedAt(LocalDateTime.now());
            OptimisticLocks.requireUpdated(reviewMapper.updateById(review), "访问复核");
        }
        auditLogService.recordEvent(spaceId, operator.id(), operator.username(),
                "compliance.access_review_decide", "access_review",
                String.valueOf(reviewId),
                AuditLogService.STATUS_SUCCESS,
                "item=" + itemId + ", subject=" + item.getSubjectType() + ", decision=" + decision,
                null, null, null);
        return view(item);
    }

    public String csv(Long spaceId, Long reviewId, UserPrincipal operator) {
        AccessReviewView review = get(spaceId, reviewId);
        List<List<String>> rows = review.items().stream()
                .map(item -> List.of(
                        nullToEmpty(item.subjectType()),
                        nullToEmpty(item.subjectName()),
                        nullToEmpty(item.role()),
                        item.lastActivityAt() == null ? "" : item.lastActivityAt().toString(),
                        String.join(";", item.flags()),
                        nullToEmpty(item.decision()),
                        item.decidedAt() == null ? "" : item.decidedAt().toString(),
                        nullToEmpty(item.note())))
                .toList();
        auditLogService.recordEvent(spaceId, operator.id(), operator.username(),
                "compliance.access_review_export", "access_review", String.valueOf(reviewId),
                AuditLogService.STATUS_SUCCESS, "rows=" + rows.size(), null, null, null);
        return CsvWriter.write(List.of("subject_type", "subject_name", "role",
                "last_activity_at", "flags", "decision", "decided_at", "note"), rows);
    }

    private void applyMemberDecision(Long spaceId, AccessReviewItem item, String decision,
                                     UserPrincipal operator) {
        if (AccessReviewDecisions.CONFIRM.equals(decision)
                || AccessReviewDecisions.ACKNOWLEDGE.equals(decision)) {
            return;
        }
        // 以空间行锁串行化同一空间的成员变更，保证“所有者数量”校验与后续删除/降级之间
        // 不会因并发而同时通过（TOCTOU），避免两个所有者被同时移除。
        // 注意：decide 在加锁前已做过普通读取并固定了 REPEATABLE READ 快照，因此加锁之后
        // 的成员状态必须用锁定读（FOR UPDATE）重新读取，否则仍会看到加锁前的过期快照。
        jdbcTemplate.queryForList(
                "SELECT id FROM king_ai_space WHERE id = ? FOR UPDATE", Long.class, spaceId);
        if (AccessReviewDecisions.REMOVE.equals(decision)
                && Objects.equals(item.getSubjectId(), operator.id())) {
            throw BizException.badRequest("不能移除当前登录账号");
        }
        SpaceRole actorRole = spaceService.roleInSpace(operator, spaceId);
        if (actorRole == null || !actorRole.atLeast(SpaceRole.ADMIN)) {
            throw BizException.forbidden("当前空间角色无权调整成员");
        }
        // 以决策时的实时角色为准，避免复核快照过期导致“降级”变成升级。
        SpaceRole current = currentRole(spaceId, item.getSubjectId());
        if (current == SpaceRole.OWNER && actorRole != SpaceRole.OWNER && !operator.isAdmin()) {
            throw BizException.forbidden("只有空间所有者可以调整其他所有者");
        }
        if (current == SpaceRole.OWNER) {
            // 所有者数量同样必须锁定读：普通 COUNT 会复用加锁前的快照，导致并发移除双双通过。
            List<String> roles = jdbcTemplate.query(
                    "SELECT role FROM king_ai_user_space WHERE space_id = ? FOR UPDATE",
                    (rs, rowNum) -> rs.getString(1), spaceId);
            long owners = roles.stream().filter("OWNER"::equals).count();
            if (owners <= 1) {
                throw BizException.badRequest(AccessReviewDecisions.REMOVE.equals(decision)
                        ? "该成员是空间唯一所有者，请先转移所有权"
                        : "该成员是空间唯一所有者，不能降级，请先转移所有权");
            }
        }
        if (AccessReviewDecisions.REMOVE.equals(decision)) {
            jdbcTemplate.update("DELETE FROM king_ai_user_space WHERE space_id = ? AND user_id = ?",
                    spaceId, item.getSubjectId());
            return;
        }
        SpaceRole target = AccessReviewDecisions.downgrade(current);
        jdbcTemplate.update("UPDATE king_ai_user_space SET role = ?, updated_at = ? "
                        + "WHERE space_id = ? AND user_id = ?",
                target.name(), LocalDateTime.now(), spaceId, item.getSubjectId());
    }

    private SpaceRole currentRole(Long spaceId, Long userId) {
        List<String> roles = jdbcTemplate.query(
                "SELECT role FROM king_ai_user_space WHERE space_id = ? AND user_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString(1), spaceId, userId);
        if (roles.isEmpty()) {
            throw BizException.notFound("成员已不在该空间");
        }
        return SpaceRole.parse(roles.get(0));
    }

    private AccessReviewItemView view(AccessReviewItem item) {
        List<String> flags = Texts.isBlank(item.getFlags()) ? List.of()
                : Arrays.stream(item.getFlags().split(","))
                        .map(String::trim).filter(value -> !value.isEmpty()).toList();
        return new AccessReviewItemView(item.getId(), item.getSubjectType(), item.getSubjectId(),
                item.getSubjectName(), item.getRole(), item.getLastActivityAt(), flags,
                item.getDecision(), item.getDecidedBy(), item.getDecidedAt(), item.getNote());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
