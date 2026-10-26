package com.king.ai.model;

import com.king.ai.common.ApiResponse;
import com.king.ai.common.PageQuery;
import com.king.ai.common.PageResult;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.RequireSpaceRole;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.model.dto.ModelImportRequest;
import com.king.ai.model.dto.ModelView;
import com.king.ai.model.dto.SupplierSaveRequest;
import com.king.ai.model.dto.SupplierTestResult;
import com.king.ai.model.dto.SupplierView;
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
@RequestMapping("/api/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;
    private final ModelService modelService;

    @RequireSpaceRole(SpaceRole.VIEWER)
    @GetMapping
    public ApiResponse<PageResult<SupplierView>> list(@RequestParam(required = false) String keyword,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(supplierService.list(RequestContext.requireSpaceId(), keyword,
                PageQuery.of(page, size, 20)));
    }

    @PostMapping
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<SupplierView> create(@Valid @RequestBody SupplierSaveRequest request) {
        return ApiResponse.ok(supplierService.create(RequestContext.requireSpaceId(), request));
    }

    @PutMapping("/{id}")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<SupplierView> update(@PathVariable Long id,
                                            @Valid @RequestBody SupplierSaveRequest request) {
        return ApiResponse.ok(supplierService.update(RequestContext.requireSpaceId(), id, request));
    }

    @DeleteMapping("/{id}")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        supplierService.delete(RequestContext.requireSpaceId(), id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/test")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<SupplierTestResult> test(@PathVariable Long id) {
        return ApiResponse.ok(supplierService.test(RequestContext.requireSpaceId(), id));
    }

    @PostMapping("/{id}/import-models")
    @RequireSpaceRole(SpaceRole.ADMIN)
    public ApiResponse<List<ModelView>> importModels(@PathVariable Long id,
                                                     @Valid @RequestBody ModelImportRequest request) {
        return ApiResponse.ok(modelService.importModels(RequestContext.requireSpaceId(), id, request));
    }
}
