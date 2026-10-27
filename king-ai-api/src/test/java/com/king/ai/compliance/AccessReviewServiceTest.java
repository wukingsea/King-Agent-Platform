package com.king.ai.compliance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.king.ai.audit.AuditLogService;
import com.king.ai.common.BizException;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.common.security.UserPrincipal;
import com.king.ai.compliance.access.AccessReview;
import com.king.ai.compliance.access.AccessReviewItem;
import com.king.ai.compliance.access.AccessReviewItemMapper;
import com.king.ai.compliance.access.AccessReviewMapper;
import com.king.ai.compliance.access.AccessReviewReportBuilder;
import com.king.ai.compliance.access.AccessReviewService;
import com.king.ai.compliance.dto.AccessReviewDecisionRequest;
import com.king.ai.compliance.dto.AccessReviewItemView;
import com.king.ai.compliance.dto.AccessReviewView;
import com.king.ai.config.KingAiProperties;
import com.king.ai.system.SpaceService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessReviewServiceTest {

    static {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AccessReview.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AccessReviewItem.class);
    }

    private static final long SPACE = 1L;
    private static final long REVIEW = 99L;
    private static final long ITEM = 11L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 26, 12, 0);

    private AccessReviewMapper reviewMapper;
    private AccessReviewItemMapper itemMapper;
    private JdbcTemplate jdbc;
    private SpaceService spaceService;
    private AuditLogService audit;
    private AccessReviewService service;

    @BeforeEach
    void setUp() {
        reviewMapper = mock(AccessReviewMapper.class);
        itemMapper = mock(AccessReviewItemMapper.class);
        jdbc = mock(JdbcTemplate.class);
        spaceService = mock(SpaceService.class);
        audit = mock(AuditLogService.class);
        service = new AccessReviewService(reviewMapper, itemMapper, jdbc, spaceService, audit,
                new KingAiProperties());
        // decide 会先对复核行加锁；默认放行，个别测试可覆盖为空结果验证 404。
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains("FOR UPDATE") ? List.of(REVIEW) : List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        // 成员变更会先锁定空间行，默认返回该空间。
        doAnswer(invocation -> List.of(SPACE))
                .when(jdbc).queryForList(anyString(), eq(Long.class), any(Object[].class));
    }

    @Test
    void constructsWithExpectedCollaborators() {
        AccessReviewService constructed = new AccessReviewService(
                mock(AccessReviewMapper.class), mock(AccessReviewItemMapper.class),
                mock(JdbcTemplate.class), mock(SpaceService.class),
                mock(AuditLogService.class), new KingAiProperties());

        assertThat(constructed).isNotNull();
    }

    @Test
    void generateSupersedesOpenReviewsStoresFindingsAndReturnsPersistedReview() {
        AccessReview previous = review(50L, SPACE, "OPEN");
        when(reviewMapper.selectList(any())).thenReturn(List.of(previous));
        AtomicReference<AccessReview> saved = new AtomicReference<>();
        when(reviewMapper.insert(any(AccessReview.class))).thenAnswer(invocation -> {
            AccessReview inserted = invocation.getArgument(0);
            inserted.setId(REVIEW);
            saved.set(inserted);
            return 1;
        });
        when(reviewMapper.selectById(REVIEW)).thenAnswer(invocation -> saved.get());
        List<AccessReviewItem> insertedItems = new ArrayList<>();
        when(itemMapper.insert(any(AccessReviewItem.class))).thenAnswer(invocation -> {
            AccessReviewItem inserted = invocation.getArgument(0);
            inserted.setId(100L + insertedItems.size());
            insertedItems.add(inserted);
            return 1;
        });
        when(itemMapper.selectList(any())).thenAnswer(invocation -> insertedItems);

        AccessReviewReportBuilder.MemberSnapshot owner = new AccessReviewReportBuilder.MemberSnapshot(
                5L, "alice", "Alice", "OWNER", NOW.minusDays(200));
        AccessReviewReportBuilder.MemberSnapshot viewer = new AccessReviewReportBuilder.MemberSnapshot(
                6L, "bob", "Bob", "VIEWER", null);
        stubFindingQueries(List.of(owner, viewer), List.of(), List.of(), List.of());

        AccessReviewView view = service.generate(SPACE, 7L);

        assertThat(previous.getStatus()).isEqualTo("SUPERSEDED");
        verify(reviewMapper).updateById(previous);
        assertThat(saved.get().getStatus()).isEqualTo("OPEN");
        assertThat(saved.get().getSpaceId()).isEqualTo(SPACE);
        assertThat(saved.get().getGeneratedBy()).isEqualTo(7L);
        assertThat(saved.get().getSummary()).isEqualTo("findings=2");
        assertThat(insertedItems).hasSize(2);
        assertThat(insertedItems).allSatisfy(item -> {
            assertThat(item.getReviewId()).isEqualTo(REVIEW);
            assertThat(item.getSpaceId()).isEqualTo(SPACE);
            assertThat(item.getDecision()).isEqualTo("PENDING");
        });
        assertThat(insertedItems.get(0).getFlags()).isEqualTo("UNUSED_90D,OWNER");
        assertThat(insertedItems.get(0).getSubjectType()).isEqualTo("MEMBER");
        assertThat(insertedItems.get(0).getSubjectName()).isEqualTo("Alice (alice)");
        assertThat(insertedItems.get(1).getFlags()).isEqualTo("NEVER_LOGGED_IN");

        assertThat(view.id()).isEqualTo(REVIEW);
        assertThat(view.summary()).isEqualTo("findings=2");
        assertThat(view.summary()).doesNotContain("alice", "bob", "Alice", "Bob");
        assertThat(view.items()).hasSize(2);
        assertThat(view.items()).extracting(AccessReviewItemView::subjectName)
                .containsExactly("Alice (alice)", "Bob (bob)");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<AccessReview>> supersedeQuery =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reviewMapper).selectList(supersedeQuery.capture());
        LambdaQueryWrapper<AccessReview> wrapper = supersedeQuery.getValue();
        assertThat(wrapper.getSqlSegment()).contains("space_id", "status");
        assertThat(wrapper.getParamNameValuePairs().values())
                .containsExactlyInAnyOrder(SPACE, "OPEN");
        verify(reviewMapper, times(1)).updateById(any(AccessReview.class));

        verify(audit).recordEvent(eq(SPACE), eq(7L), anyString(),
                eq("compliance.access_review_generate"), eq("access_review"),
                eq(String.valueOf(REVIEW)), eq(AuditLogService.STATUS_SUCCESS),
                eq("findings=2"), isNull(), isNull(), isNull());
    }

    @Test
    void generateWithNoFindingsCreatesEmptyOpenReview() {
        when(reviewMapper.selectList(any())).thenReturn(List.of());
        AtomicReference<AccessReview> saved = new AtomicReference<>();
        when(reviewMapper.insert(any(AccessReview.class))).thenAnswer(invocation -> {
            AccessReview inserted = invocation.getArgument(0);
            inserted.setId(REVIEW);
            saved.set(inserted);
            return 1;
        });
        when(reviewMapper.selectById(REVIEW)).thenAnswer(invocation -> saved.get());
        when(itemMapper.selectList(any())).thenReturn(List.of());
        stubFindingQueries(List.of(), List.of(), List.of(), List.of());

        AccessReviewView view = service.generate(SPACE, 7L);

        verify(itemMapper, never()).insert(any(AccessReviewItem.class));
        verify(reviewMapper, never()).updateById(any(AccessReview.class));
        assertThat(saved.get().getStatus()).isEqualTo("OPEN");
        assertThat(view.summary()).isEqualTo("findings=0");
        assertThat(view.items()).isEmpty();
        verify(audit).recordEvent(eq(SPACE), eq(7L), anyString(),
                eq("compliance.access_review_generate"), eq("access_review"),
                eq(String.valueOf(REVIEW)), eq(AuditLogService.STATUS_SUCCESS),
                eq("findings=0"), isNull(), isNull(), isNull());
    }

    @Test
    void generatePersistsNonNullSubjectNameWhenNicknameMissing() {
        when(reviewMapper.selectList(any())).thenReturn(List.of());
        List<AccessReviewItem> insertedItems = stubGeneratePersistence();
        stubFindingQueries(List.of(new AccessReviewReportBuilder.MemberSnapshot(
                5L, "alice", null, "VIEWER", NOW)), List.of(), List.of(), List.of());

        service.generate(SPACE, 7L);

        assertThat(insertedItems).singleElement().satisfies(item -> {
            assertThat(item.getSubjectName()).isNotNull().isEqualTo("alice (alice)");
            assertThat(item.getRole()).isEqualTo("VIEWER");
        });
    }

    @Test
    void generateFallsBackToDashWhenCredentialNameMissing() {
        when(reviewMapper.selectList(any())).thenReturn(List.of());
        List<AccessReviewItem> insertedItems = stubGeneratePersistence();
        stubFindingQueries(List.of(),
                List.of(new AccessReviewReportBuilder.CredentialSnapshot(5L, null, NOW)),
                List.of(), List.of());

        service.generate(SPACE, 7L);

        assertThat(insertedItems).singleElement().satisfies(item -> {
            assertThat(item.getSubjectName()).isEqualTo("-");
            assertThat(item.getRole()).isEqualTo("供应商密钥");
        });
    }

    @Test
    void generateKeepsFullPublicIdAndLabelsPublicAgentsAsPublic() {
        when(reviewMapper.selectList(any())).thenReturn(List.of());
        List<AccessReviewItem> insertedItems = stubGeneratePersistence();
        String publicId = "f47ac10b58cc4372a5670e02b2c3d479";
        stubFindingQueries(List.of(),
                List.of(new AccessReviewReportBuilder.CredentialSnapshot(5L, "openai", NOW)),
                List.of(new AccessReviewReportBuilder.CredentialSnapshot(6L, "mcp-a", NOW)),
                List.of(new AccessReviewReportBuilder.PublicAgentSnapshot(7L, "客服", publicId,
                        NOW)));

        service.generate(SPACE, 7L);

        AccessReviewItem agent = insertedItems.stream()
                .filter(item -> "PUBLIC_AGENT".equals(item.getSubjectType()))
                .findFirst().orElseThrow();
        assertThat(agent.getSubjectName()).isEqualTo("客服 (" + publicId + ")");
        assertThat(agent.getSubjectName()).contains(publicId);
        assertThat(agent.getRole()).isEqualTo("PUBLIC");
        assertThat(insertedItems).filteredOn(item -> "SUPPLIER".equals(item.getSubjectType()))
                .singleElement().satisfies(item -> assertThat(item.getRole())
                        .isEqualTo("供应商密钥"));
        assertThat(insertedItems).filteredOn(item -> "MCP".equals(item.getSubjectType()))
                .singleElement().satisfies(item -> assertThat(item.getRole())
                        .isEqualTo("MCP 配置"));
    }

    @Test
    void listReturnsViewsInMapperOrderAndScopesEachReviewToSpace() {
        AccessReview newer = review(2L, SPACE, "OPEN");
        AccessReview older = review(1L, SPACE, "COMPLETED");
        when(reviewMapper.selectList(any())).thenReturn(List.of(newer, older));
        when(reviewMapper.selectById(2L)).thenReturn(newer);
        when(reviewMapper.selectById(1L)).thenReturn(older);
        when(itemMapper.selectList(any())).thenReturn(List.of());

        List<AccessReviewView> views = service.list(SPACE);

        assertThat(views).extracting(AccessReviewView::id).containsExactly(2L, 1L);
        assertThat(views).allSatisfy(view -> assertThat(view.spaceId()).isEqualTo(SPACE));
        verify(reviewMapper).selectById(2L);
        verify(reviewMapper).selectById(1L);
    }

    @Test
    void listFailsWhenMapperReturnsReviewFromAnotherSpace() {
        AccessReview foreign = review(3L, 2L, "OPEN");
        when(reviewMapper.selectList(any())).thenReturn(List.of(foreign));
        when(reviewMapper.selectById(3L)).thenReturn(foreign);

        assertThatThrownBy(() -> service.list(SPACE))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(404));
    }

    @Test
    void getReturns404ForMissingReview() {
        when(reviewMapper.selectById(REVIEW)).thenReturn(null);

        assertThatThrownBy(() -> service.get(SPACE, REVIEW))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(404);
                    assertThat(ex.getMessage()).contains("访问复核不存在");
                });
    }

    @Test
    void getReturns404ForReviewFromAnotherSpace() {
        when(reviewMapper.selectById(REVIEW)).thenReturn(review(REVIEW, 2L, "OPEN"));

        assertThatThrownBy(() -> service.get(SPACE, REVIEW))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(404));
    }

    @Test
    void decideRejectsInvalidDecisionText() {
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), memberItem(9L, "ADMIN", "PENDING"));

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("MAYBE", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(400);
                    assertThat(ex.getMessage()).isEqualTo("复核决策不合法");
                });
        verify(itemMapper, never()).updateById(any(AccessReviewItem.class));
    }

    @Test
    void decideRejectsMissingOrCompletedReview() {
        when(reviewMapper.selectById(REVIEW)).thenReturn(null);

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("CONFIRM", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(404));

        when(reviewMapper.selectById(REVIEW)).thenReturn(review(REVIEW, SPACE, "COMPLETED"));

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("CONFIRM", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(404);
                    assertThat(ex.getMessage()).contains("已完成");
                });
    }

    @Test
    void decideLocksReviewRowBeforeReadingReviewState() {
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), memberItem(9L, "VIEWER", "PENDING"));

        service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("CONFIRM", null), operator(2L));

        InOrder order = inOrder(jdbc, reviewMapper);
        order.verify(jdbc).query(contains("FROM king_ai_access_review"), any(RowMapper.class),
                eq(REVIEW), eq(SPACE));
        order.verify(reviewMapper).selectById(REVIEW);
    }

    @Test
    void decideReturns404WhenReviewRowCannotBeLocked() {
        doAnswer(invocation -> List.of()).when(jdbc).query(anyString(),
                any(RowMapper.class), any(Object[].class));

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("CONFIRM", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(404);
                    assertThat(ex.getMessage()).contains("访问复核不存在或已完成");
                });
        verify(reviewMapper, never()).selectById(any());
        verify(itemMapper, never()).selectById(any());
    }

    @Test
    void decideRejectsReviewFromAnotherSpaceBeforeLoadingItem() {
        when(reviewMapper.selectById(REVIEW)).thenReturn(review(REVIEW, 2L, "OPEN"));

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("CONFIRM", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(404));
        verify(itemMapper, never()).selectById(any());
    }

    @Test
    void decideRejectsItemBelongingToAnotherReview() {
        when(reviewMapper.selectById(REVIEW)).thenReturn(review(REVIEW, SPACE, "OPEN"));
        AccessReviewItem foreign = memberItem(9L, "ADMIN", "PENDING");
        foreign.setReviewId(55L);
        when(itemMapper.selectById(ITEM)).thenReturn(foreign);

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("CONFIRM", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(404);
                    assertThat(ex.getMessage()).contains("复核项不存在");
                });
    }

    @Test
    void decideRejectsAlreadyDecidedItem() {
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), memberItem(9L, "ADMIN", "REMOVE"));
        when(spaceService.roleInSpace(any(), eq(SPACE))).thenReturn(SpaceRole.ADMIN);

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("REMOVE", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(409);
                    assertThat(ex.getMessage()).isEqualTo("该复核项已决策");
                });
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(itemMapper, never()).updateById(any(AccessReviewItem.class));
    }

    @Test
    void decideRejectsRemoveForSupplier() {
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"),
                item(ITEM, REVIEW, "SUPPLIER", 5L, null, "PENDING"));

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("REMOVE", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("供应商");
                });
    }

    @Test
    void downgradeUsesLiveRoleFromDatabaseNotSnapshot() {
        AccessReviewItem item = memberItem(9L, "ADMIN", "PENDING");
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), item);
        when(spaceService.roleInSpace(any(), eq(SPACE))).thenReturn(SpaceRole.ADMIN);
        stubLiveRole("EDITOR");

        AccessReviewItemView view = service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("DOWNGRADE", "  reviewed  "), operator(2L));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("UPDATE king_ai_user_space SET role = ?"), args.capture());
        Object[] values = args.getValue();
        assertThat(values[0]).isEqualTo("VIEWER");
        assertThat(values[1]).isInstanceOf(LocalDateTime.class);
        assertThat(values[2]).isEqualTo(SPACE);
        assertThat(values[3]).isEqualTo(9L);
        assertThat(item.getRole()).isEqualTo("ADMIN");
        assertThat(view.role()).isEqualTo("ADMIN");
        assertThat(view.decision()).isEqualTo("DOWNGRADE");
        assertThat(view.note()).isEqualTo("reviewed");
        assertThat(view.decidedAt()).isNotNull();
    }

    @Test
    void downgradeRejectsLiveViewerAsLowestRole() {
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), memberItem(9L, "ADMIN", "PENDING"));
        when(spaceService.roleInSpace(any(), eq(SPACE))).thenReturn(SpaceRole.ADMIN);
        stubLiveRole("VIEWER");

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("DOWNGRADE", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("最低角色");
                });
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void removeIssuesParameterizedDeleteOfMembership() {
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), memberItem(9L, "VIEWER", "PENDING"));
        when(spaceService.roleInSpace(any(), eq(SPACE))).thenReturn(SpaceRole.ADMIN);
        stubLiveRole("VIEWER");

        service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("REMOVE", null), operator(2L));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("DELETE FROM king_ai_user_space WHERE space_id = ? AND user_id = ?"),
                args.capture());
        assertThat(args.getValue()).containsExactly(SPACE, 9L);
    }

    @Test
    void removeRejectsSelfRemoval() {
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), memberItem(2L, "ADMIN", "PENDING"));

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("REMOVE", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("不能移除当前登录账号");
                });
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void actorBelowAdminCannotRemoveMember() {
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), memberItem(9L, "VIEWER", "PENDING"));
        when(spaceService.roleInSpace(any(), eq(SPACE))).thenReturn(SpaceRole.VIEWER);

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("REMOVE", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(403));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void actorBelowAdminCannotDowngradeMember() {
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), memberItem(9L, "ADMIN", "PENDING"));
        when(spaceService.roleInSpace(any(), eq(SPACE))).thenReturn(SpaceRole.EDITOR);

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("DOWNGRADE", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(403));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void actorBelowAdminWithNoRoleCannotRemoveMember() {
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), memberItem(9L, "VIEWER", "PENDING"));
        when(spaceService.roleInSpace(any(), eq(SPACE))).thenReturn(null);

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("REMOVE", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(403));
    }

    @Test
    void nonOwnerCannotAdjustAnotherOwner() {
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), memberItem(9L, "OWNER", "PENDING"));
        when(spaceService.roleInSpace(any(), eq(SPACE))).thenReturn(SpaceRole.ADMIN);
        stubLiveRole("OWNER");

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("DOWNGRADE", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(403);
                    assertThat(ex.getMessage()).contains("只有空间所有者");
                });
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void snapshotAdminLiveOwnerCannotBeRemovedByNonOwner() {
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), memberItem(9L, "ADMIN", "PENDING"));
        when(spaceService.roleInSpace(any(), eq(SPACE))).thenReturn(SpaceRole.ADMIN);
        stubLiveRole("OWNER");

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("REMOVE", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(403);
                    assertThat(ex.getMessage()).contains("只有空间所有者");
                });
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(jdbc, never()).queryForObject(contains("COUNT(*)"), eq(Long.class), eq(SPACE));
    }

    @Test
    void snapshotAdminLiveOwnerCannotBeDowngradedByNonOwner() {
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), memberItem(9L, "ADMIN", "PENDING"));
        when(spaceService.roleInSpace(any(), eq(SPACE))).thenReturn(SpaceRole.ADMIN);
        stubLiveRole("OWNER");

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("DOWNGRADE", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(403);
                    assertThat(ex.getMessage()).contains("只有空间所有者");
                });
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(jdbc, never()).queryForObject(contains("COUNT(*)"), eq(Long.class), eq(SPACE));
    }

    @Test
    void singleOwnerCannotBeRemoved() {
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), memberItem(9L, "OWNER", "PENDING"));
        when(spaceService.roleInSpace(any(), eq(SPACE))).thenReturn(SpaceRole.OWNER);
        stubLiveRole("OWNER");
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(SPACE))).thenReturn(1L);

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("REMOVE", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("唯一所有者");
                });
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void singleOwnerCannotBeDowngraded() {
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), memberItem(9L, "OWNER", "PENDING"));
        when(spaceService.roleInSpace(any(), eq(SPACE))).thenReturn(SpaceRole.OWNER);
        stubLiveRole("OWNER");
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(SPACE))).thenReturn(1L);

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("DOWNGRADE", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("唯一所有者");
                });
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void platformAdminCanAdjustOwnerWhenAnotherOwnerExists() {
        AccessReviewItem item = memberItem(9L, "OWNER", "PENDING");
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), item);
        UserPrincipal platformAdmin = new UserPrincipal(2L, "root", "Root", "ADMIN", SPACE,
                "OWNER", false);
        when(spaceService.roleInSpace(any(), eq(SPACE))).thenReturn(SpaceRole.ADMIN);
        stubLiveRole("OWNER");
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(SPACE))).thenReturn(2L);

        service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("DOWNGRADE", null), platformAdmin);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("UPDATE king_ai_user_space SET role = ?"), args.capture());
        assertThat(args.getValue()[0]).isEqualTo("ADMIN");
    }

    @Test
    void ownerRemovalLocksSpaceBeforeCountingOwners() {
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), memberItem(9L, "OWNER", "PENDING"));
        when(spaceService.roleInSpace(any(), eq(SPACE))).thenReturn(SpaceRole.OWNER);
        stubLiveRole("OWNER");
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(SPACE))).thenReturn(2L);

        service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("REMOVE", null), operator(2L));

        InOrder order = inOrder(jdbc);
        order.verify(jdbc).queryForList(contains("king_ai_space"), eq(Long.class), eq(SPACE));
        order.verify(jdbc).query(contains("SELECT role FROM king_ai_user_space"),
                any(RowMapper.class), eq(SPACE), eq(9L));
        order.verify(jdbc).queryForObject(contains("COUNT(*)"), eq(Long.class), eq(SPACE));
        order.verify(jdbc).update(contains("DELETE FROM king_ai_user_space"), any(Object[].class));
    }

    @Test
    void ownerDowngradeLocksSpaceBeforeCountingOwners() {
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), memberItem(9L, "OWNER", "PENDING"));
        when(spaceService.roleInSpace(any(), eq(SPACE))).thenReturn(SpaceRole.OWNER);
        stubLiveRole("OWNER");
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(SPACE))).thenReturn(2L);

        service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("DOWNGRADE", null), operator(2L));

        InOrder order = inOrder(jdbc);
        order.verify(jdbc).queryForList(contains("king_ai_space"), eq(Long.class), eq(SPACE));
        order.verify(jdbc).query(contains("SELECT role FROM king_ai_user_space"),
                any(RowMapper.class), eq(SPACE), eq(9L));
        order.verify(jdbc).queryForObject(contains("COUNT(*)"), eq(Long.class), eq(SPACE));
        order.verify(jdbc).update(contains("UPDATE king_ai_user_space SET role = ?"),
                any(Object[].class));
    }

    @Test
    void nonOwnerRemovalLocksSpaceBeforeDeletingMembership() {
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), memberItem(9L, "VIEWER", "PENDING"));
        when(spaceService.roleInSpace(any(), eq(SPACE))).thenReturn(SpaceRole.ADMIN);
        stubLiveRole("VIEWER");

        service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("REMOVE", null), operator(2L));

        InOrder order = inOrder(jdbc);
        order.verify(jdbc).queryForList(contains("king_ai_space"), eq(Long.class), eq(SPACE));
        order.verify(jdbc).query(contains("SELECT role FROM king_ai_user_space"),
                any(RowMapper.class), eq(SPACE), eq(9L));
        order.verify(jdbc).update(contains("DELETE FROM king_ai_user_space"), any(Object[].class));
        verify(jdbc, never()).queryForObject(contains("COUNT(*)"), eq(Long.class), eq(SPACE));
    }

    @Test
    void acknowledgeSupplierDoesNotTouchMembership() {
        AccessReviewItem item = item(ITEM, REVIEW, "SUPPLIER", 5L, null, "PENDING");
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), item);
        when(itemMapper.selectCount(any())).thenReturn(1L);

        AccessReviewItemView view = service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("ACKNOWLEDGE", "noted"), operator(2L));

        assertThat(view.decision()).isEqualTo("ACKNOWLEDGE");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(jdbc, never()).query(contains("king_ai_user_space"), any(RowMapper.class),
                any(Object[].class));
        verify(jdbc, never()).queryForList(anyString(), eq(Long.class), any(Object[].class));
        verify(spaceService, never()).roleInSpace(any(), any());
    }

    @Test
    void decidingLastPendingItemCompletesReviewWithOptimisticLock() {
        AccessReview review = review(REVIEW, SPACE, "OPEN");
        stubReviewAndItem(review, memberItem(9L, "VIEWER", "PENDING"));
        when(itemMapper.selectCount(any())).thenReturn(0L);

        service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("CONFIRM", null), operator(2L));

        assertThat(review.getStatus()).isEqualTo("COMPLETED");
        assertThat(review.getCompletedAt()).isNotNull();
        verify(reviewMapper).updateById(review);
    }

    @Test
    void decidingNonLastPendingItemLeavesReviewOpen() {
        AccessReview review = review(REVIEW, SPACE, "OPEN");
        stubReviewAndItem(review, memberItem(9L, "VIEWER", "PENDING"));
        when(itemMapper.selectCount(any())).thenReturn(1L);

        service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("CONFIRM", null), operator(2L));

        assertThat(review.getStatus()).isEqualTo("OPEN");
        assertThat(review.getCompletedAt()).isNull();
        verify(reviewMapper, never()).updateById(any(AccessReview.class));
    }

    @Test
    void completingReviewFailsWhenOptimisticLockConflicts() {
        AccessReview review = review(REVIEW, SPACE, "OPEN");
        stubReviewAndItem(review, memberItem(9L, "VIEWER", "PENDING"));
        when(itemMapper.selectCount(any())).thenReturn(0L);
        when(reviewMapper.updateById(any(AccessReview.class))).thenReturn(0);

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("CONFIRM", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(409);
                    assertThat(ex.getMessage()).contains("访问复核");
                });
    }

    @Test
    void itemOptimisticLockConflictStopsBeforeCompletionAndAudit() {
        AccessReview review = review(REVIEW, SPACE, "OPEN");
        stubReviewAndItem(review, memberItem(9L, "VIEWER", "PENDING"));
        when(itemMapper.updateById(any(AccessReviewItem.class))).thenReturn(0);
        when(itemMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("CONFIRM", null), operator(2L)))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(409);
                    assertThat(ex.getMessage()).contains("复核项");
                });
        verify(reviewMapper, never()).updateById(any(AccessReview.class));
        verify(audit, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void decideAuditContainsOnlyItemTypeAndDecision() {
        AccessReviewItem item = memberItem(9L, "VIEWER", "PENDING");
        item.setSubjectName("Subject Nine");
        stubReviewAndItem(review(REVIEW, SPACE, "OPEN"), item);
        UserPrincipal operator = new UserPrincipal(2L, "secret-user", "Secret Nick", "USER",
                SPACE, "ADMIN", false);

        service.decide(SPACE, REVIEW, ITEM,
                new AccessReviewDecisionRequest("CONFIRM", "sensitive note"), operator);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).recordEvent(eq(SPACE), eq(2L), eq("secret-user"),
                eq("compliance.access_review_decide"), eq("access_review"),
                eq(String.valueOf(REVIEW)), eq(AuditLogService.STATUS_SUCCESS),
                detail.capture(), any(), any(), any());
        assertThat(detail.getValue()).isEqualTo("item=11, subject=MEMBER, decision=CONFIRM");
        assertThat(detail.getValue()).doesNotContain("secret-user", "Secret Nick", "Subject Nine",
                "sensitive note");
    }

    @Test
    void csvEmitsExactHeaderJoinedFlagsQuotedAndNeutralizedValues() {
        AccessReview review = review(REVIEW, SPACE, "OPEN");
        when(reviewMapper.selectById(REVIEW)).thenReturn(review);
        LocalDateTime activity = LocalDateTime.of(2026, 10, 1, 9, 30);
        AccessReviewItem risky = item(ITEM, REVIEW, "MEMBER", 9L, "ADMIN", "PENDING");
        risky.setSubjectName("Alice, \"Al\"");
        risky.setLastActivityAt(activity);
        risky.setFlags("NEVER_LOGGED_IN,OWNER");
        risky.setNote("=SUM(A1)");
        AccessReviewItem noteOnly = item(ITEM + 1, REVIEW, "MCP", 5L, "MCP 配置", "ACKNOWLEDGE");
        noteOnly.setSubjectName("mcp-a");
        noteOnly.setLastActivityAt(activity.minusDays(100));
        noteOnly.setFlags("STALE_CREDENTIAL");
        noteOnly.setDecidedAt(activity.plusDays(1));
        noteOnly.setNote("plain");
        when(itemMapper.selectList(any())).thenReturn(List.of(risky, noteOnly));

        UserPrincipal operator = operator(2L);
        String csv = service.csv(SPACE, REVIEW, operator);

        String header = csv.substring(0, csv.indexOf('\n'));
        assertThat(header).isEqualTo(
                "subject_type,subject_name,role,last_activity_at,flags,decision,decided_at,note");
        assertThat(header.split(",", -1)).hasSize(8);
        assertThat(csv).contains("\"Alice, \"\"Al\"\"\"");
        assertThat(csv).contains("NEVER_LOGGED_IN;OWNER");
        assertThat(csv).contains("'=SUM(A1)");
        assertThat(csv).contains("STALE_CREDENTIAL,ACKNOWLEDGE");
        assertThat(csv).doesNotContain("NEVER_LOGGED_IN,OWNER");

        verify(audit, times(1)).recordEvent(eq(SPACE), eq(2L), eq("op"),
                eq("compliance.access_review_export"), eq("access_review"),
                eq(String.valueOf(REVIEW)), eq(AuditLogService.STATUS_SUCCESS),
                eq("rows=2"), isNull(), isNull(), isNull());
    }

    @Test
    void csvReturns404ForReviewFromAnotherSpaceAndSkipsAudit() {
        when(reviewMapper.selectById(REVIEW)).thenReturn(review(REVIEW, 2L, "OPEN"));

        assertThatThrownBy(() -> service.csv(SPACE, REVIEW, operator(2L)))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(404));
        verify(audit, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    private List<AccessReviewItem> stubGeneratePersistence() {
        AtomicReference<AccessReview> saved = new AtomicReference<>();
        when(reviewMapper.insert(any(AccessReview.class))).thenAnswer(invocation -> {
            AccessReview inserted = invocation.getArgument(0);
            inserted.setId(REVIEW);
            saved.set(inserted);
            return 1;
        });
        when(reviewMapper.selectById(REVIEW)).thenAnswer(invocation -> saved.get());
        List<AccessReviewItem> insertedItems = new ArrayList<>();
        when(itemMapper.insert(any(AccessReviewItem.class))).thenAnswer(invocation -> {
            AccessReviewItem inserted = invocation.getArgument(0);
            inserted.setId(100L + insertedItems.size());
            insertedItems.add(inserted);
            return 1;
        });
        when(itemMapper.selectList(any())).thenAnswer(invocation -> insertedItems);
        return insertedItems;
    }

    private void stubFindingQueries(List<AccessReviewReportBuilder.MemberSnapshot> members,
                                    List<AccessReviewReportBuilder.CredentialSnapshot> suppliers,
                                    List<AccessReviewReportBuilder.CredentialSnapshot> mcps,
                                    List<AccessReviewReportBuilder.PublicAgentSnapshot> agents) {
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("FROM king_ai_user_space us")) {
                return members;
            }
            if (sql.contains("FROM king_ai_supplier")) {
                return suppliers;
            }
            if (sql.contains("FROM king_ai_mcp")) {
                return mcps;
            }
            if (sql.contains("FROM king_ai_agent")) {
                return agents;
            }
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    private void stubLiveRole(String role) {
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("FOR UPDATE")) {
                return List.of(REVIEW);
            }
            if (sql.contains("SELECT role FROM king_ai_user_space")) {
                return List.of(role);
            }
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    private void stubReviewAndItem(AccessReview review, AccessReviewItem item) {
        when(reviewMapper.selectById(REVIEW)).thenReturn(review);
        when(itemMapper.selectById(ITEM)).thenReturn(item);
        when(itemMapper.updateById(any(AccessReviewItem.class))).thenReturn(1);
        when(reviewMapper.updateById(any(AccessReview.class))).thenReturn(1);
        when(itemMapper.selectCount(any())).thenReturn(1L);
    }

    private AccessReview review(long id, long spaceId, String status) {
        AccessReview review = new AccessReview();
        review.setId(id);
        review.setSpaceId(spaceId);
        review.setStatus(status);
        review.setSummary("findings=1");
        review.setGeneratedBy(7L);
        review.setGeneratedAt(NOW);
        return review;
    }

    private AccessReviewItem memberItem(long subjectId, String role, String decision) {
        return item(ITEM, REVIEW, "MEMBER", subjectId, role, decision);
    }

    private AccessReviewItem item(long id, long reviewId, String subjectType, long subjectId,
                                  String role, String decision) {
        AccessReviewItem item = new AccessReviewItem();
        item.setId(id);
        item.setReviewId(reviewId);
        item.setSpaceId(SPACE);
        item.setSubjectType(subjectType);
        item.setSubjectId(subjectId);
        item.setSubjectName("Subject " + subjectId);
        item.setRole(role);
        item.setFlags("NEVER_LOGGED_IN");
        item.setDecision(decision);
        return item;
    }

    private UserPrincipal operator(long id) {
        return new UserPrincipal(id, "op", "Operator", "USER", SPACE, "ADMIN", false);
    }
}
