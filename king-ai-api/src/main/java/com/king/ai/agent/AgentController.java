package com.king.ai.agent;

import com.king.ai.agent.dto.AgentOption;
import com.king.ai.agent.dto.AgentSaveRequest;
import com.king.ai.agent.dto.AgentVersionView;
import com.king.ai.agent.dto.AgentView;
import com.king.ai.common.ApiResponse;
import com.king.ai.common.PageQuery;
import com.king.ai.common.PageResult;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.RequireSpaceRole;
import com.king.ai.common.security.SpaceRole;
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
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @RequireSpaceRole(SpaceRole.VIEWER)
    @GetMapping
    public ApiResponse<PageResult<AgentView>> list(@RequestParam(required = false) String keyword,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(agentService.list(RequestContext.requireSpaceId(), keyword,
                PageQuery.of(page, size, 20), canReadSystemPrompt()));
    }

    @RequireSpaceRole(SpaceRole.VIEWER)
    @GetMapping("/options")
    public ApiResponse<List<AgentOption>> options(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(agentService.options(RequestContext.requireSpaceId(), keyword, 200));
    }

    @RequireSpaceRole(SpaceRole.VIEWER)
    @GetMapping("/{id}")
    public ApiResponse<AgentView> detail(@PathVariable Long id) {
        return ApiResponse.ok(agentService.detail(RequestContext.requireSpaceId(), id,
                canReadSystemPrompt()));
    }

    /**
     * System prompts are editable configuration, not runtime metadata: roles below EDITOR only
     * need agent metadata, so the prompt is withheld to avoid leaking proprietary instructions.
     */
    private static boolean canReadSystemPrompt() {
        return RequestContext.require().hasSpaceRole(SpaceRole.EDITOR);
    }

    @PostMapping
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<AgentView> create(@Valid @RequestBody AgentSaveRequest request) {
        return ApiResponse.ok(agentService.create(RequestContext.requireSpaceId(), request));
    }

    @PutMapping("/{id}")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<AgentView> update(@PathVariable Long id,
                                         @Valid @RequestBody AgentSaveRequest request) {
        return ApiResponse.ok(agentService.update(RequestContext.requireSpaceId(), id, request));
    }

    @DeleteMapping("/{id}")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        agentService.delete(RequestContext.requireSpaceId(), id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/publish")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<AgentView> publish(@PathVariable Long id) {
        return ApiResponse.ok(agentService.publish(RequestContext.requireSpaceId(), id));
    }

    @PostMapping("/{id}/unpublish")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<AgentView> unpublish(@PathVariable Long id) {
        return ApiResponse.ok(agentService.unpublish(RequestContext.requireSpaceId(), id));
    }

    @RequireSpaceRole(SpaceRole.VIEWER)
    @GetMapping("/{id}/versions")
    public ApiResponse<List<AgentVersionView>> versions(@PathVariable Long id) {
        return ApiResponse.ok(agentService.versions(RequestContext.requireSpaceId(), id));
    }

    @PostMapping("/{id}/versions/{versionNo}/rollback")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<AgentView> rollback(@PathVariable Long id, @PathVariable Integer versionNo) {
        return ApiResponse.ok(agentService.rollback(RequestContext.requireSpaceId(), id, versionNo));
    }
}
