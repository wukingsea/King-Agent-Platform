package com.king.ai.compliance;

import com.king.ai.audit.AuditLogService;
import com.king.ai.common.ApiResponse;
import com.king.ai.common.BizException;
import com.king.ai.common.PageResult;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.RequireSpaceRole;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.common.security.UserPrincipal;
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
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/compliance")
@RequiredArgsConstructor
public class ComplianceController {

    private final RetentionPolicyService retentionPolicyService;
    private final LegalHoldService legalHoldService;
    private final ExportService exportService;
    private final AccessReviewService accessReviewService;
    private final AuditLogService auditLogService;

    @GetMapping("/retention-policies")
    @RequireSpaceRole(SpaceRole.VIEWER)
    public ApiResponse<List<RetentionPolicyView>> retentionPolicies() {
        return ApiResponse.ok(retentionPolicyService.list(RequestContext.requireSpaceId()));
    }

    @PutMapping("/retention-policies")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<RetentionPolicyView> upsertRetentionPolicy(
            @Valid @RequestBody RetentionPolicyUpdateRequest request) {
        UserPrincipal principal = RequestContext.require();
        return ApiResponse.ok(retentionPolicyService.upsert(
                RequestContext.requireSpaceId(), request, principal.id()));
    }

    @GetMapping("/legal-holds")
    @RequireSpaceRole({SpaceRole.ADMIN, SpaceRole.AUDITOR})
    public ApiResponse<List<LegalHoldView>> legalHolds() {
        requireAuditorOrAdmin(RequestContext.require());
        return ApiResponse.ok(legalHoldService.list(RequestContext.requireSpaceId()));
    }

    @PostMapping("/legal-holds")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<LegalHoldView> createLegalHold(
            @Valid @RequestBody LegalHoldCreateRequest request) {
        UserPrincipal principal = RequestContext.require();
        return ApiResponse.ok(legalHoldService.create(
                RequestContext.requireSpaceId(), request, principal.id()));
    }

    @DeleteMapping("/legal-holds/{id}")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<Void> releaseLegalHold(@PathVariable Long id) {
        legalHoldService.release(RequestContext.requireSpaceId(), id,
                RequestContext.require().id());
        return ApiResponse.ok(null);
    }

    @GetMapping("/exports")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<PageResult<ExportJobView>> exports(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(exportService.page(RequestContext.requireSpaceId(), page, size));
    }

    @PostMapping("/exports")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<ExportJobView> createExport(@Valid @RequestBody ExportCreateRequest request) {
        UserPrincipal principal = RequestContext.require();
        return ApiResponse.ok(exportService.create(
                RequestContext.requireSpaceId(), request, principal.id()));
    }

    @GetMapping("/exports/{id}/download")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ResponseEntity<StreamingResponseBody> download(@PathVariable Long id) {
        UserPrincipal principal = RequestContext.require();
        Long spaceId = RequestContext.requireSpaceId();
        ExportService.Download download = exportService.openDownload(spaceId, id);
        String fileName = safeFileName(download.fileName());
        StreamingResponseBody body = out -> streamDownload(out, spaceId, id, principal, download);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(downloadMediaType(download.format()))
                .contentLength(download.size())
                .body(body);
    }

    /**
     * 只有响应体完整写出才记 SUCCESS；读取或写出失败记 FAILURE，避免“审计成功但客户端并未拿到文件”。
     */
    private void streamDownload(OutputStream out, Long spaceId, Long id, UserPrincipal principal,
                                ExportService.Download download) throws IOException {
        CountingOutputStream counter = new CountingOutputStream(out);
        try (InputStream in = download.stream()) {
            in.transferTo(counter);
            counter.flush();
            auditDownload(spaceId, id, principal, AuditLogService.STATUS_SUCCESS, counter.count());
        } catch (IOException | RuntimeException ex) {
            try {
                auditDownload(spaceId, id, principal, AuditLogService.STATUS_FAILURE,
                        counter.count());
            } catch (RuntimeException auditFailure) {
                ex.addSuppressed(auditFailure);
            }
            throw ex;
        }
    }

    private void auditDownload(Long spaceId, Long id, UserPrincipal principal, String status,
                               long written) {
        auditLogService.recordEvent(spaceId, principal.id(), principal.username(),
                "compliance.export_download", "export", String.valueOf(id),
                status, "bytes=" + written, null, null, null);
    }

    // 文件名来自数据库，必须去掉引号/换行等字符，避免响应头注入。
    private static String safeFileName(String fileName) {
        return fileName == null ? "export.bin"
                : fileName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static MediaType downloadMediaType(String format) {
        return format != null && "CSV".equalsIgnoreCase(format)
                ? MediaType.parseMediaType("text/csv; charset=UTF-8")
                : MediaType.APPLICATION_OCTET_STREAM;
    }

    private static final class CountingOutputStream extends FilterOutputStream {

        private long count;

        private CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        private long count() {
            return count;
        }
    }

    @GetMapping("/access-reviews")
    @RequireSpaceRole({SpaceRole.ADMIN, SpaceRole.AUDITOR})
    public ApiResponse<List<AccessReviewView>> accessReviews() {
        requireAuditorOrAdmin(RequestContext.require());
        return ApiResponse.ok(accessReviewService.list(RequestContext.requireSpaceId()));
    }

    @PostMapping("/access-reviews")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<AccessReviewView> generateAccessReview() {
        UserPrincipal principal = RequestContext.require();
        return ApiResponse.ok(accessReviewService.generate(
                RequestContext.requireSpaceId(), principal.id()));
    }

    @GetMapping("/access-reviews/{id}")
    @RequireSpaceRole({SpaceRole.ADMIN, SpaceRole.AUDITOR})
    public ApiResponse<AccessReviewView> accessReview(@PathVariable Long id) {
        requireAuditorOrAdmin(RequestContext.require());
        return ApiResponse.ok(accessReviewService.get(RequestContext.requireSpaceId(), id));
    }

    @PostMapping("/access-reviews/{id}/items/{itemId}/decision")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<AccessReviewItemView> decide(@PathVariable Long id,
                                                    @PathVariable Long itemId,
                                                    @Valid @RequestBody
                                                    AccessReviewDecisionRequest request) {
        UserPrincipal principal = RequestContext.require();
        return ApiResponse.ok(accessReviewService.decide(RequestContext.requireSpaceId(), id,
                itemId, request, principal));
    }

    @GetMapping("/access-reviews/{id}/export.csv")
    @RequireSpaceRole({SpaceRole.ADMIN, SpaceRole.AUDITOR})
    public ResponseEntity<byte[]> exportReview(@PathVariable Long id) {
        UserPrincipal principal = RequestContext.require();
        requireAuditorOrAdmin(principal);
        String csv = accessReviewService.csv(RequestContext.requireSpaceId(), id, principal);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"access-review-" + id + ".csv\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code @RequireSpaceRole} 的数组匹配是“任一角色 or 更高等级”，而 AUDITOR 与 VIEWER
     * 同级别；这里再按精确角色判一次，避免 VIEWER/EDITOR 读到审计类数据。
     */
    private static void requireAuditorOrAdmin(UserPrincipal principal) {
        if (principal.isAdmin()) {
            return;
        }
        SpaceRole role = principal.roleInSpace();
        if (role != SpaceRole.OWNER && role != SpaceRole.ADMIN && role != SpaceRole.AUDITOR) {
            throw BizException.forbidden("需要管理员或审计员权限");
        }
    }
}
