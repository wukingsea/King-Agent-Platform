package com.king.ai.skill;

import com.king.ai.common.ApiResponse;
import com.king.ai.common.PageQuery;
import com.king.ai.common.PageResult;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.RequireSpaceRole;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.skill.dto.SkillDetail;
import com.king.ai.skill.dto.SkillFileCreateRequest;
import com.king.ai.skill.dto.SkillFileUpdateRequest;
import com.king.ai.skill.dto.SkillFileView;
import com.king.ai.skill.dto.SkillSaveRequest;
import com.king.ai.skill.dto.SkillView;
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
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    @RequireSpaceRole(SpaceRole.VIEWER)
    @GetMapping
    public ApiResponse<PageResult<SkillView>> list(@RequestParam(required = false) String keyword,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(skillService.list(RequestContext.requireSpaceId(), keyword,
                PageQuery.of(page, size, 20)));
    }

    @RequireSpaceRole(SpaceRole.VIEWER)
    @GetMapping("/{id}")
    public ApiResponse<SkillDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(skillService.detail(RequestContext.requireSpaceId(), id));
    }

    @PostMapping
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<SkillDetail> create(@Valid @RequestBody SkillSaveRequest request) {
        return ApiResponse.ok(skillService.create(RequestContext.requireSpaceId(), request));
    }

    @PutMapping("/{id}")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<SkillView> update(@PathVariable Long id,
                                         @Valid @RequestBody SkillSaveRequest request) {
        return ApiResponse.ok(skillService.update(RequestContext.requireSpaceId(), id, request));
    }

    @DeleteMapping("/{id}")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        skillService.delete(RequestContext.requireSpaceId(), id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/files")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<SkillFileView> createFile(@PathVariable Long id,
                                                 @Valid @RequestBody SkillFileCreateRequest request) {
        return ApiResponse.ok(skillService.createFile(RequestContext.requireSpaceId(), id, request));
    }

    @PutMapping("/{id}/files/{fileId}")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<SkillFileView> updateFile(@PathVariable Long id,
                                                 @PathVariable Long fileId,
                                                 @Valid @RequestBody SkillFileUpdateRequest request) {
        return ApiResponse.ok(skillService.updateFile(RequestContext.requireSpaceId(), id, fileId, request));
    }

    @DeleteMapping("/{id}/files/{fileId}")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<Void> deleteFile(@PathVariable Long id, @PathVariable Long fileId) {
        skillService.deleteFile(RequestContext.requireSpaceId(), id, fileId);
        return ApiResponse.ok();
    }
}
