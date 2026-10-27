package com.king.ai.compliance;

import com.king.ai.common.ApiResponse;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.RequireSpaceRole;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.compliance.dto.ComplianceSettingUpdateRequest;
import com.king.ai.compliance.dto.ComplianceSettingView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/compliance-settings")
@RequiredArgsConstructor
public class ComplianceSettingController {

    private final ComplianceSettingsService service;

    @GetMapping
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<ComplianceSettingView> get() {
        return get(RequestContext.requireSpaceId());
    }

    @PutMapping
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<ComplianceSettingView> update(
            @Valid @RequestBody ComplianceSettingUpdateRequest request) {
        return update(RequestContext.requireSpaceId(), request);
    }

    ApiResponse<ComplianceSettingView> get(Long spaceId) {
        return ApiResponse.ok(service.view(spaceId));
    }

    ApiResponse<ComplianceSettingView> update(Long spaceId, ComplianceSettingUpdateRequest request) {
        return ApiResponse.ok(service.update(spaceId, request));
    }
}
