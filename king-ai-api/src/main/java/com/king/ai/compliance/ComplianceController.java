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
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
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
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        UserPrincipal principal = RequestContext.require();
        Long spaceId = RequestContext.requireSpaceId();
        ExportService.Download download = exportService.openDownload(spaceId, id);
        // 文件名来自数据库，必须去掉引号/换行等字符，避免响应头注入。
        String fileName = download.fileName() == null ? "export.bin"
                : download.fileName().replaceAll("[^A-Za-z0-9._-]", "_");
        auditLogService.recordEvent(spaceId, principal.id(), principal.username(),
                "compliance.export_download", "export", String.valueOf(id),
                AuditLogService.STATUS_SUCCESS, "bytes=" + download.size(), null, null, null);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.size())
                .body(new InputStreamResource(download.stream()));
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
