package com.king.ai.system;

import com.king.ai.common.ApiResponse;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.RequireSpaceRole;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.system.dto.WorkspaceStatsView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspace")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceStatsService statsService;

    @RequireSpaceRole(SpaceRole.VIEWER)
    @GetMapping("/stats")
    public ApiResponse<WorkspaceStatsView> stats() {
        return ApiResponse.ok(statsService.stats(RequestContext.requireSpaceId()));
    }
}
