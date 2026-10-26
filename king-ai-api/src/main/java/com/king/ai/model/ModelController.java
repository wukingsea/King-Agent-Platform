package com.king.ai.model;

import com.king.ai.common.ApiResponse;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.RequireSpaceRole;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.model.dto.ModelCreateRequest;
import com.king.ai.model.dto.ModelOption;
import com.king.ai.model.dto.ModelUpdateRequest;
import com.king.ai.model.dto.ModelView;
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
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelService modelService;

    @RequireSpaceRole(SpaceRole.VIEWER)
    @GetMapping
    public ApiResponse<List<ModelView>> list(@RequestParam(required = false) Long supplierId,
                                             @RequestParam(required = false) String type,
                                             @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(modelService.list(RequestContext.requireSpaceId(), supplierId, type, keyword));
    }

    @RequireSpaceRole(SpaceRole.VIEWER)
    @GetMapping("/options")
    public ApiResponse<List<ModelOption>> options(@RequestParam(required = false) String type) {
        return ApiResponse.ok(modelService.options(RequestContext.requireSpaceId(), type));
    }

    @PostMapping
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<ModelView> create(@Valid @RequestBody ModelCreateRequest request) {
        return ApiResponse.ok(modelService.create(RequestContext.requireSpaceId(), request));
    }

    @PutMapping("/{id}")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<ModelView> update(@PathVariable Long id,
                                         @Valid @RequestBody ModelUpdateRequest request) {
        return ApiResponse.ok(modelService.update(RequestContext.requireSpaceId(), id, request));
    }

    @DeleteMapping("/{id}")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        modelService.delete(RequestContext.requireSpaceId(), id);
        return ApiResponse.ok();
    }
}
