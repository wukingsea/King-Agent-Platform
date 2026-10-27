package com.king.ai.compliance;

import com.king.ai.audit.AuditLogService;
import com.king.ai.common.BizException;
import com.king.ai.common.PageResult;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.RequireSpaceRole;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.common.security.UserPrincipal;
import com.king.ai.compliance.access.AccessReviewScheduler;
import com.king.ai.compliance.access.AccessReviewService;
import com.king.ai.compliance.dto.AccessReviewDecisionRequest;
import com.king.ai.compliance.dto.AccessReviewItemView;
import com.king.ai.compliance.dto.AccessReviewView;
import com.king.ai.compliance.dto.ExportCreateRequest;
import com.king.ai.compliance.dto.ExportJobView;
import com.king.ai.compliance.dto.LegalHoldCreateRequest;
import com.king.ai.compliance.dto.LegalHoldView;
import com.king.ai.compliance.dto.RetentionPolicyUpdateRequest;
import com.king.ai.compliance.dto.RetentionPolicyView;
import com.king.ai.compliance.export.ExportService;
import com.king.ai.compliance.retention.LegalHoldService;
import com.king.ai.compliance.retention.RetentionPolicyService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ComplianceControllerTest {

    private static final Long SPACE = 7L;

    @Test
    void constructsWithAllServices() {
        ComplianceController controller = new ComplianceController(
                mock(RetentionPolicyService.class), mock(LegalHoldService.class),
                mock(ExportService.class), mock(AccessReviewService.class),
                mock(AuditLogService.class));

        assertThat(controller).isNotNull();
    }

    @Test
    void listsRetentionPoliciesForCurrentSpace() {
        Fixture f = new Fixture();
        RetentionPolicyView view = new RetentionPolicyView(1L, "SESSION", 30, true, true);
        when(f.retentionService.list(SPACE)).thenReturn(List.of(view));
        RequestContext.set(member(42L, "VIEWER"));
        try {
            assertThat(f.controller.retentionPolicies().data()).containsExactly(view);
        } finally {
            RequestContext.clear();
        }
        verify(f.retentionService).list(SPACE);
    }

    @Test
    void upsertsRetentionPolicyWithSpaceAndOperator() {
        Fixture f = new Fixture();
        RetentionPolicyUpdateRequest request =
                new RetentionPolicyUpdateRequest("SESSION", 30, true, false);
        RetentionPolicyView view = new RetentionPolicyView(1L, "SESSION", 30, true, false);
        when(f.retentionService.upsert(SPACE, request, 42L)).thenReturn(view);
        RequestContext.set(member(42L, "ADMIN"));
        try {
            assertThat(f.controller.upsertRetentionPolicy(request).data()).isEqualTo(view);
        } finally {
            RequestContext.clear();
        }
        verify(f.retentionService).upsert(SPACE, request, 42L);
    }

    @Test
    void listsLegalHoldsForCurrentSpace() {
        Fixture f = new Fixture();
        LegalHoldView view = new LegalHoldView(3L, "AUDIT", 11L, "诉讼保全", null, null, null);
        when(f.legalHoldService.list(SPACE)).thenReturn(List.of(view));
        RequestContext.set(member(42L, "AUDITOR"));
        try {
            assertThat(f.controller.legalHolds().data()).containsExactly(view);
        } finally {
            RequestContext.clear();
        }
        verify(f.legalHoldService).list(SPACE);
    }

    @Test
    void createsLegalHoldWithSpaceAndOperator() {
        Fixture f = new Fixture();
        LegalHoldCreateRequest request =
                new LegalHoldCreateRequest("AUDIT", 11L, "诉讼保全", null);
        LegalHoldView view = new LegalHoldView(3L, "AUDIT", 11L, "诉讼保全", null, null, null);
        when(f.legalHoldService.create(SPACE, request, 42L)).thenReturn(view);
        RequestContext.set(member(42L, "ADMIN"));
        try {
            assertThat(f.controller.createLegalHold(request).data()).isEqualTo(view);
        } finally {
            RequestContext.clear();
        }
        verify(f.legalHoldService).create(SPACE, request, 42L);
    }

    @Test
    void releasesLegalHoldWithSpaceIdAndOperator() {
        Fixture f = new Fixture();
        RequestContext.set(member(42L, "ADMIN"));
        try {
            assertThat(f.controller.releaseLegalHold(3L).code()).isEqualTo(200);
        } finally {
            RequestContext.clear();
        }
        verify(f.legalHoldService).release(SPACE, 3L, 42L);
    }

    @Test
    void listsExportJobsWithPaging() {
        Fixture f = new Fixture();
        ExportJobView job = new ExportJobView(9L, "AUDIT", "CSV", "COMPLETED", "audit.csv", 3L,
                null, 42L, null, null, null, null, null);
        PageResult<ExportJobView> page = PageResult.of(List.of(job), 1, 2, 50);
        when(f.exportService.page(SPACE, 2, 50)).thenReturn(page);
        RequestContext.set(member(42L, "ADMIN"));
        try {
            assertThat(f.controller.exports(2, 50).data()).isEqualTo(page);
        } finally {
            RequestContext.clear();
        }
        verify(f.exportService).page(SPACE, 2, 50);
    }

    @Test
    void createsExportWithSpaceAndOperator() {
        Fixture f = new Fixture();
        ExportCreateRequest request = new ExportCreateRequest("AUDIT", "CSV", null, null);
        ExportJobView job = new ExportJobView(9L, "AUDIT", "CSV", "PENDING", null, 0L,
                null, 42L, null, null, null, null, null);
        when(f.exportService.create(SPACE, request, 42L)).thenReturn(job);
        RequestContext.set(member(42L, "ADMIN"));
        try {
            assertThat(f.controller.createExport(request).data()).isEqualTo(job);
        } finally {
            RequestContext.clear();
        }
        verify(f.exportService).create(SPACE, request, 42L);
    }

    @Test
    void downloadSanitizesFileNameSetsCsvCharsetAndAuditsBytesWritten() throws Exception {
        Fixture f = new Fixture();
        when(f.exportService.openDownload(SPACE, 9L)).thenReturn(new ExportService.Download(
                "../../evil\r\nX: y.csv", "CSV", 12L,
                new ByteArrayInputStream(new byte[]{1, 2, 3})));
        RequestContext.set(member(42L, "ADMIN"));
        ResponseEntity<StreamingResponseBody> response;
        byte[] body;
        try {
            response = f.controller.download(9L);
            body = write(response);
        } finally {
            RequestContext.clear();
        }

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .isEqualTo("attachment; filename=\".._.._evil__X__y.csv\"")
                .doesNotContain("\r")
                .doesNotContain("\n");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        assertThat(response.getHeaders().getContentLength()).isEqualTo(12L);
        assertThat(body).containsExactly(1, 2, 3);
        verify(f.auditLogService).recordEvent(SPACE, 42L, "user42",
                "compliance.export_download", "export", "9", AuditLogService.STATUS_SUCCESS,
                "bytes=3", null, null, null);
    }

    @Test
    void downloadFallsBackToExportBinAndOctetStreamWhenFormatMissing() throws Exception {
        Fixture f = new Fixture();
        when(f.exportService.openDownload(SPACE, 9L)).thenReturn(new ExportService.Download(
                null, null, 5L, new ByteArrayInputStream(new byte[]{1})));
        RequestContext.set(member(42L, "ADMIN"));
        ResponseEntity<StreamingResponseBody> response;
        try {
            response = f.controller.download(9L);
            assertThat(write(response)).containsExactly(1);
        } finally {
            RequestContext.clear();
        }
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .isEqualTo("attachment; filename=\"export.bin\"");
        verify(f.auditLogService).recordEvent(SPACE, 42L, "user42",
                "compliance.export_download", "export", "9", AuditLogService.STATUS_SUCCESS,
                "bytes=1", null, null, null);
    }

    @Test
    void downloadAuditsFailureOnlyAfterBodyStreamFails() throws Exception {
        Fixture f = new Fixture();
        when(f.exportService.openDownload(SPACE, 9L)).thenReturn(new ExportService.Download(
                "audit.csv", "CSV", 100L, new InputStream() {
                    private int remaining = 2;

                    @Override
                    public int read() throws IOException {
                        byte[] one = new byte[1];
                        int n = read(one, 0, 1);
                        return n < 0 ? -1 : one[0] & 0xff;
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        if (remaining > 0) {
                            remaining--;
                            buffer[offset] = 65;
                            return 1;
                        }
                        throw new IOException("client disconnected");
                    }
                }));
        RequestContext.set(member(42L, "ADMIN"));
        ResponseEntity<StreamingResponseBody> response;
        try {
            response = f.controller.download(9L);
            assertThatThrownBy(() -> write(response))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("client disconnected");
        } finally {
            RequestContext.clear();
        }
        verify(f.auditLogService).recordEvent(SPACE, 42L, "user42",
                "compliance.export_download", "export", "9", AuditLogService.STATUS_FAILURE,
                "bytes=2", null, null, null);
    }

    @Test
    void downloadWritesNoAuditWhenServiceThrows() {
        Fixture f = new Fixture();
        when(f.exportService.openDownload(SPACE, 9L))
                .thenThrow(BizException.notFound("导出任务不存在"));
        RequestContext.set(member(42L, "ADMIN"));
        try {
            assertThatThrownBy(() -> f.controller.download(9L))
                    .isInstanceOf(BizException.class);
        } finally {
            RequestContext.clear();
        }
        verifyNoInteractions(f.auditLogService);
    }

    @Test
    void listsAccessReviewsForCurrentSpace() {
        Fixture f = new Fixture();
        AccessReviewView review = review(5L);
        when(f.accessReviewService.list(SPACE)).thenReturn(List.of(review));
        RequestContext.set(member(42L, "AUDITOR"));
        try {
            assertThat(f.controller.accessReviews().data()).containsExactly(review);
        } finally {
            RequestContext.clear();
        }
        verify(f.accessReviewService).list(SPACE);
    }

    @Test
    void generatesAccessReviewWithSpaceAndOperator() {
        Fixture f = new Fixture();
        AccessReviewView review = review(5L);
        when(f.accessReviewService.generate(SPACE, 42L)).thenReturn(review);
        RequestContext.set(member(42L, "ADMIN"));
        try {
            assertThat(f.controller.generateAccessReview().data()).isEqualTo(review);
        } finally {
            RequestContext.clear();
        }
        verify(f.accessReviewService).generate(SPACE, 42L);
    }

    @Test
    void returnsSingleAccessReviewForCurrentSpace() {
        Fixture f = new Fixture();
        AccessReviewView review = review(5L);
        when(f.accessReviewService.get(SPACE, 5L)).thenReturn(review);
        RequestContext.set(member(42L, "AUDITOR"));
        try {
            assertThat(f.controller.accessReview(5L).data()).isEqualTo(review);
        } finally {
            RequestContext.clear();
        }
        verify(f.accessReviewService).get(SPACE, 5L);
    }

    @Test
    void decidePassesPathIdsRequestAndOperator() {
        Fixture f = new Fixture();
        AccessReviewDecisionRequest request = new AccessReviewDecisionRequest("CONFIRM", "ok");
        AccessReviewItemView item = new AccessReviewItemView(6L, "MEMBER", 44L, "bob", "ADMIN",
                null, List.of(), "CONFIRM", 42L, null, "ok");
        UserPrincipal operator = member(42L, "ADMIN");
        when(f.accessReviewService.decide(SPACE, 5L, 6L, request, operator)).thenReturn(item);
        RequestContext.set(operator);
        try {
            assertThat(f.controller.decide(5L, 6L, request).data()).isEqualTo(item);
        } finally {
            RequestContext.clear();
        }
        verify(f.accessReviewService).decide(SPACE, 5L, 6L, request, operator);
    }

    @Test
    void csvResponseCarriesUtf8BodyAndSafeHeaders() {
        Fixture f = new Fixture();
        UserPrincipal principal = member(42L, "AUDITOR");
        String csv = "subject_type,subject_name\nMEMBER,张三\n";
        when(f.accessReviewService.csv(SPACE, 5L, principal)).thenReturn(csv);
        RequestContext.set(principal);
        ResponseEntity<byte[]> response;
        try {
            response = f.controller.exportReview(5L);
        } finally {
            RequestContext.clear();
        }

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        assertThat(response.getHeaders().getContentType().getType()).isEqualTo("text");
        assertThat(response.getHeaders().getContentType().getSubtype()).isEqualTo("csv");
        assertThat(response.getHeaders().getContentType().getCharset())
                .isEqualTo(StandardCharsets.UTF_8);
        assertThat(response.getHeaders().getFirst("Content-Type")).contains("text/csv");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .contains("access-review-5.csv");
        assertThat(response.getBody()).isEqualTo(csv.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void viewerAndEditorCannotReadAuditEndpoints() {
        for (String role : List.of("VIEWER", "EDITOR")) {
            Fixture f = new Fixture();
            RequestContext.set(member(42L, role));
            try {
                assertForbidden(f.controller::legalHolds, role + " legal holds");
                assertForbidden(f.controller::accessReviews, role + " list");
                assertForbidden(() -> f.controller.accessReview(5L), role + " get");
                assertForbidden(() -> f.controller.exportReview(5L), role + " csv");
            } finally {
                RequestContext.clear();
            }
            verify(f.legalHoldService, never()).list(anyLong());
            verify(f.accessReviewService, never()).list(anyLong());
            verify(f.accessReviewService, never()).get(anyLong(), anyLong());
            verify(f.accessReviewService, never()).csv(anyLong(), anyLong(), any());
        }
    }

    @Test
    void auditorAdminOwnerAndPlatformAdminCanReadAuditEndpoints() {
        List<UserPrincipal> allowed = List.of(
                member(42L, "AUDITOR"),
                member(43L, "ADMIN"),
                member(44L, "OWNER"),
                new UserPrincipal(1L, "root", "Root", "ADMIN", SPACE, null, false));
        for (UserPrincipal principal : allowed) {
            Fixture f = new Fixture();
            AccessReviewView review = review(5L);
            when(f.accessReviewService.list(SPACE)).thenReturn(List.of(review));
            when(f.accessReviewService.get(SPACE, 5L)).thenReturn(review);
            when(f.accessReviewService.csv(SPACE, 5L, principal)).thenReturn("csv");
            RequestContext.set(principal);
            try {
                assertThat(f.controller.accessReviews().data()).containsExactly(review);
                assertThat(f.controller.accessReview(5L).data()).isEqualTo(review);
                assertThat(f.controller.exportReview(5L).getStatusCode())
                        .isEqualTo(HttpStatus.OK);
            } finally {
                RequestContext.clear();
            }
            verify(f.accessReviewService).list(SPACE);
            verify(f.accessReviewService).get(SPACE, 5L);
            verify(f.accessReviewService).csv(SPACE, 5L, principal);
        }
    }

    @Test
    void missingSpaceIsRejectedBeforeAnyServiceCall() {
        Fixture f = new Fixture();
        UserPrincipal noSpace = new UserPrincipal(42L, "user42", "User 42", "USER", null,
                "ADMIN", false);
        RequestContext.set(noSpace);
        try {
            assertBadRequest(f.controller::retentionPolicies);
            assertBadRequest(f.controller::legalHolds);
            assertBadRequest(f.controller::accessReviews);
            assertBadRequest(() -> f.controller.exports(1, 20));
            assertBadRequest(() -> f.controller.accessReview(5L));
        } finally {
            RequestContext.clear();
        }
        verifyNoInteractions(f.retentionService, f.legalHoldService, f.exportService,
                f.accessReviewService);
    }

    @Test
    void declaresExpectedEndpointsAndExactRoles() {
        assertThat(ComplianceController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/compliance");
        assertGet("retentionPolicies", "/retention-policies", SpaceRole.VIEWER);
        assertPut("upsertRetentionPolicy", "/retention-policies", SpaceRole.ADMIN);
        assertGet("legalHolds", "/legal-holds", SpaceRole.ADMIN, SpaceRole.AUDITOR);
        assertPost("createLegalHold", "/legal-holds", SpaceRole.ADMIN);
        assertDelete("releaseLegalHold", "/legal-holds/{id}", SpaceRole.ADMIN);
        assertGet("exports", "/exports", SpaceRole.ADMIN);
        assertPost("createExport", "/exports", SpaceRole.ADMIN);
        assertGet("download", "/exports/{id}/download", SpaceRole.ADMIN);
        assertGet("accessReviews", "/access-reviews", SpaceRole.ADMIN, SpaceRole.AUDITOR);
        assertPost("generateAccessReview", "/access-reviews", SpaceRole.ADMIN);
        assertGet("accessReview", "/access-reviews/{id}", SpaceRole.ADMIN, SpaceRole.AUDITOR);
        assertPost("decide", "/access-reviews/{id}/items/{itemId}/decision", SpaceRole.ADMIN);
        assertGet("exportReview", "/access-reviews/{id}/export.csv",
                SpaceRole.ADMIN, SpaceRole.AUDITOR);
    }

    @Test
    void schedulerUsesQuarterlyCronProperty() throws Exception {
        Method quarterly = AccessReviewScheduler.class.getMethod("quarterly");
        Scheduled scheduled = quarterly.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron())
                .isEqualTo("${king-ai.compliance.access-review.cron:0 0 4 1 1,4,7,10 *}");
    }

    private static void assertGet(String method, String path, SpaceRole... roles) {
        Method target = findMethod(method);
        GetMapping mapping = target.getAnnotation(GetMapping.class);
        assertThat(mapping).as(method).isNotNull();
        assertThat(mapping.value()).as(method).containsExactly(path);
        assertRoles(target, roles);
    }

    private static void assertPut(String method, String path, SpaceRole... roles) {
        Method target = findMethod(method);
        PutMapping mapping = target.getAnnotation(PutMapping.class);
        assertThat(mapping).as(method).isNotNull();
        assertThat(mapping.value()).as(method).containsExactly(path);
        assertRoles(target, roles);
    }

    private static void assertPost(String method, String path, SpaceRole... roles) {
        Method target = findMethod(method);
        PostMapping mapping = target.getAnnotation(PostMapping.class);
        assertThat(mapping).as(method).isNotNull();
        assertThat(mapping.value()).as(method).containsExactly(path);
        assertRoles(target, roles);
    }

    private static void assertDelete(String method, String path, SpaceRole... roles) {
        Method target = findMethod(method);
        DeleteMapping mapping = target.getAnnotation(DeleteMapping.class);
        assertThat(mapping).as(method).isNotNull();
        assertThat(mapping.value()).as(method).containsExactly(path);
        assertRoles(target, roles);
    }

    private static void assertRoles(Method target, SpaceRole... roles) {
        RequireSpaceRole annotation = target.getAnnotation(RequireSpaceRole.class);
        assertThat(annotation).as(target.getName()).isNotNull();
        assertThat(annotation.value()).as(target.getName())
                .containsExactlyInAnyOrder(roles);
    }

    private static Method findMethod(String name) {
        List<Method> matches = Arrays.stream(ComplianceController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .toList();
        assertThat(matches).as(name).hasSize(1);
        return matches.get(0);
    }

    private static byte[] write(ResponseEntity<StreamingResponseBody> response) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        return out.toByteArray();
    }

    private static void assertForbidden(ThrowingCallable call, String description) {
        assertThatThrownBy(call).as(description)
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(403));
    }

    private static void assertBadRequest(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(400));
    }

    private static UserPrincipal member(long id, String spaceRole) {
        return new UserPrincipal(id, "user" + id, "User " + id, "USER", SPACE, spaceRole, false);
    }

    private static AccessReviewView review(Long id) {
        return new AccessReviewView(id, SPACE, "OPEN", "findings=0", 42L,
                LocalDateTime.of(2026, 10, 1, 4, 0), null, List.of());
    }

    private static final class Fixture {

        private final RetentionPolicyService retentionService = mock(RetentionPolicyService.class);
        private final LegalHoldService legalHoldService = mock(LegalHoldService.class);
        private final ExportService exportService = mock(ExportService.class);
        private final AccessReviewService accessReviewService = mock(AccessReviewService.class);
        private final AuditLogService auditLogService = mock(AuditLogService.class);
        private final ComplianceController controller = new ComplianceController(
                retentionService, legalHoldService, exportService, accessReviewService,
                auditLogService);
    }
}
