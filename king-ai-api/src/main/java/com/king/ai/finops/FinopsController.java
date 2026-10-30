package com.king.ai.finops;

import com.king.ai.audit.AuditLogService;
import com.king.ai.common.ApiResponse;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.common.web.ClientIpResolver;
import com.king.ai.common.web.RequestIdFilter;
import com.king.ai.finops.dto.QuotaUpdateRequest;
import com.king.ai.finops.dto.UsageSummary;
import com.king.ai.system.SpaceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/finops")
@RequiredArgsConstructor
public class FinopsController {

    private final UsageLedger usageLedger;
    private final SpaceService spaceService;
    private final AuditLogService auditLogService;
    private final ClientIpResolver clientIpResolver;

    @GetMapping("/usage")
    public ApiResponse<UsageSummary> usage() {
        return ApiResponse.ok(usageLedger.summary(RequestContext.requireSpaceId()));
    }

    @PutMapping("/quota")
    public ApiResponse<UsageSummary> updateQuota(@Valid @RequestBody QuotaUpdateRequest request,
                                                 HttpServletRequest httpRequest) {
        var principal = RequestContext.require();
        Long spaceId = RequestContext.requireSpaceId();
        spaceService.requireSpaceRole(principal, spaceId, SpaceRole.OWNER);
        UsageSummary summary = usageLedger.updateQuota(spaceId, request.monthlyTokenQuota());
        auditLogService.recordEvent(spaceId, principal.id(), principal.username(),
                "finops.quota_update", "space", String.valueOf(spaceId),
                AuditLogService.STATUS_SUCCESS,
                "monthlyTokenQuota=" + request.monthlyTokenQuota(),
                clientIpResolver.resolve(httpRequest), httpRequest.getHeader("User-Agent"),
                MDC.get(RequestIdFilter.MDC_KEY));
        return ApiResponse.ok(summary);
    }
}
