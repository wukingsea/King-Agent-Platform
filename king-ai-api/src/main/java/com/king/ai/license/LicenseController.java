package com.king.ai.license;

import com.king.ai.common.ApiResponse;
import com.king.ai.common.security.RequireAdmin;
import com.king.ai.license.dto.LicenseStatusView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/license")
@RequireAdmin
@RequiredArgsConstructor
public class LicenseController {

    private final LicenseService licenseService;

    @GetMapping
    public ApiResponse<LicenseStatusView> status() {
        return ApiResponse.ok(licenseService.status());
    }
}
