package com.king.ai.system;

import com.king.ai.common.ApiResponse;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.RequireAdmin;
import com.king.ai.system.dto.MemberUpdateRequest;
import com.king.ai.system.dto.SpaceCreateRequest;
import com.king.ai.system.dto.SpaceMemberView;
import com.king.ai.system.dto.SpaceUpdateRequest;
import com.king.ai.system.dto.SpaceView;
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
@RequestMapping("/api/spaces")
@RequiredArgsConstructor
public class SpaceController {

    private final SpaceService spaceService;

    @GetMapping
    public ApiResponse<List<SpaceView>> list() {
        return ApiResponse.ok(spaceService.listFor(RequestContext.require()));
    }

    @PostMapping
    @RequireAdmin
    public ApiResponse<SpaceView> create(@Valid @RequestBody SpaceCreateRequest request) {
        return ApiResponse.ok(spaceService.create(request, RequestContext.require().id()));
    }

    @PutMapping("/{id}")
    public ApiResponse<SpaceView> update(@PathVariable Long id,
                                         @Valid @RequestBody SpaceUpdateRequest request) {
        return ApiResponse.ok(spaceService.update(RequestContext.require(), id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        spaceService.delete(RequestContext.require(), id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/members")
    public ApiResponse<List<SpaceMemberView>> members(@PathVariable Long id) {
        return ApiResponse.ok(spaceService.members(RequestContext.require(), id));
    }

    @PutMapping("/{id}/members")
    public ApiResponse<Void> replaceMembers(@PathVariable Long id,
                                            @RequestBody MemberUpdateRequest request) {
        spaceService.replaceMembers(RequestContext.require(), id, request);
        return ApiResponse.ok();
    }
}
