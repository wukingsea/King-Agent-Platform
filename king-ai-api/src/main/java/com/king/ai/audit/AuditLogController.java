package com.king.ai.audit;

import com.king.ai.audit.dto.AuditLogView;
import com.king.ai.common.ApiResponse;
import com.king.ai.common.PageResult;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.RequireSpaceRole;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.common.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    @RequireSpaceRole({SpaceRole.ADMIN, SpaceRole.AUDITOR})
    public ApiResponse<PageResult<AuditLogView>> page(
            @RequestParam(required = false) Long spaceId,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserPrincipal principal = RequestContext.require();
        Long scope;
        if (principal.isAdmin()) {
            scope = spaceId;
        } else {
            scope = RequestContext.requireSpaceId();
        }
        return ApiResponse.ok(auditLogService.page(scope, actorId, action, status, from, to, page, size));
    }
}
