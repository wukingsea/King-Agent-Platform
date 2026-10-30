package com.king.ai.agent;

import com.king.ai.agent.dto.AgentVersionView;
import com.king.ai.agent.release.dto.AgentEvalCaseSaveRequest;
import com.king.ai.agent.release.dto.AgentEvalCaseView;
import com.king.ai.agent.release.dto.CanaryRequest;
import com.king.ai.agent.release.dto.ReleaseDecisionRequest;
import com.king.ai.common.ApiResponse;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agents/{agentId}/release")
@RequiredArgsConstructor
public class AgentReleaseController {

    private final AgentReleaseService releaseService;

    @RequireSpaceRole(SpaceRole.VIEWER)
    @GetMapping("/eval-cases")
    public ApiResponse<List<AgentEvalCaseView>> evalCases(@PathVariable Long agentId) {
        return ApiResponse.ok(releaseService.evalCases(RequestContext.requireSpaceId(), agentId));
    }

    @PostMapping("/eval-cases")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<AgentEvalCaseView> createEvalCase(@PathVariable Long agentId,
                                                         @Valid @RequestBody AgentEvalCaseSaveRequest request) {
        return ApiResponse.ok(releaseService.createEvalCase(
                RequestContext.requireSpaceId(), agentId, request));
    }

    @PutMapping("/eval-cases/{caseId}")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<AgentEvalCaseView> updateEvalCase(@PathVariable Long agentId,
                                                         @PathVariable Long caseId,
                                                         @Valid @RequestBody AgentEvalCaseSaveRequest request) {
        return ApiResponse.ok(releaseService.updateEvalCase(
                RequestContext.requireSpaceId(), agentId, caseId, request));
    }

    @DeleteMapping("/eval-cases/{caseId}")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<Void> deleteEvalCase(@PathVariable Long agentId, @PathVariable Long caseId) {
        releaseService.deleteEvalCase(RequestContext.requireSpaceId(), agentId, caseId);
        return ApiResponse.ok();
    }

    @PostMapping("/submit")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<AgentVersionView> submit(@PathVariable Long agentId) {
        return ApiResponse.ok(releaseService.submit(RequestContext.requireSpaceId(), agentId));
    }

    @PostMapping("/{versionNo}/approve")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<AgentVersionView> approve(@PathVariable Long agentId,
                                                 @PathVariable Integer versionNo,
                                                 @Valid @RequestBody(required = false) ReleaseDecisionRequest request) {
        return ApiResponse.ok(releaseService.approve(RequestContext.requireSpaceId(), agentId,
                versionNo, request == null ? null : request.comment()));
    }

    @PostMapping("/{versionNo}/reject")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<AgentVersionView> reject(@PathVariable Long agentId,
                                                @PathVariable Integer versionNo,
                                                @Valid @RequestBody ReleaseDecisionRequest request) {
        return ApiResponse.ok(releaseService.reject(RequestContext.requireSpaceId(), agentId,
                versionNo, request.comment()));
    }

    @PostMapping("/{versionNo}/canary")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<AgentVersionView> canary(@PathVariable Long agentId,
                                                @PathVariable Integer versionNo,
                                                @Valid @RequestBody CanaryRequest request) {
        return ApiResponse.ok(releaseService.canary(RequestContext.requireSpaceId(), agentId,
                versionNo, request.percent()));
    }

    @PostMapping("/{versionNo}/promote")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<AgentVersionView> promote(@PathVariable Long agentId,
                                                 @PathVariable Integer versionNo) {
        return ApiResponse.ok(releaseService.promote(RequestContext.requireSpaceId(), agentId, versionNo));
    }

    @PostMapping("/{versionNo}/evaluate")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<AgentVersionView> evaluate(@PathVariable Long agentId,
                                                  @PathVariable Integer versionNo) {
        return ApiResponse.ok(releaseService.evaluate(RequestContext.requireSpaceId(), agentId, versionNo));
    }
}
