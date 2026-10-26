package com.king.ai.mcp;

import com.king.ai.common.ApiResponse;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.RequireSpaceRole;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.mcp.dto.McpSaveRequest;
import com.king.ai.mcp.dto.McpTestResult;
import com.king.ai.mcp.dto.McpView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/mcp-servers")
@RequiredArgsConstructor
public class McpController {

    private final McpService mcpService;

    @RequireSpaceRole(SpaceRole.VIEWER)
    @GetMapping
    public ApiResponse<List<McpView>> list(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(mcpService.list(RequestContext.requireSpaceId(), keyword));
    }

    @PostMapping
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<McpView> create(@Valid @RequestBody McpSaveRequest request) {
        return ApiResponse.ok(mcpService.create(RequestContext.requireSpaceId(), request));
    }

    @PutMapping("/{id}")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<McpView> update(@PathVariable Long id,
                                       @Valid @RequestBody McpSaveRequest request) {
        return ApiResponse.ok(mcpService.update(RequestContext.requireSpaceId(), id, request));
    }

    @DeleteMapping("/{id}")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        mcpService.delete(RequestContext.requireSpaceId(), id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/test")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<McpTestResult> test(@PathVariable Long id) {
        return ApiResponse.ok(mcpService.test(RequestContext.requireSpaceId(), id));
    }
}
