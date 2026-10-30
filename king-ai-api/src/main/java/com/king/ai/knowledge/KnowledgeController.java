package com.king.ai.knowledge;

import com.king.ai.common.ApiResponse;
import com.king.ai.common.PageQuery;
import com.king.ai.common.PageResult;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.RequireSpaceRole;
import com.king.ai.common.security.SpaceRole;
import com.king.ai.knowledge.dto.KnowledgeBaseSaveRequest;
import com.king.ai.knowledge.dto.KnowledgeBaseView;
import com.king.ai.knowledge.dto.KnowledgeChunkView;
import com.king.ai.knowledge.dto.KnowledgeDocAclRequest;
import com.king.ai.knowledge.dto.KnowledgeDocDetail;
import com.king.ai.knowledge.dto.KnowledgeDocTextRequest;
import com.king.ai.knowledge.dto.KnowledgeDocView;
import com.king.ai.knowledge.dto.KnowledgeSearchRequest;
import com.king.ai.knowledge.dto.KnowledgeSearchResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @RequireSpaceRole(SpaceRole.VIEWER)
    @GetMapping
    public ApiResponse<PageResult<KnowledgeBaseView>> list(@RequestParam(required = false) String keyword,
                                                           @RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(knowledgeService.list(RequestContext.requireSpaceId(), keyword,
                PageQuery.of(page, size, 20)));
    }

    @RequireSpaceRole(SpaceRole.VIEWER)
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBaseView> detail(@PathVariable Long id) {
        return ApiResponse.ok(knowledgeService.detail(RequestContext.requireSpaceId(), id));
    }

    @PostMapping
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<KnowledgeBaseView> create(@Valid @RequestBody KnowledgeBaseSaveRequest request) {
        return ApiResponse.ok(knowledgeService.create(RequestContext.requireSpaceId(), request));
    }

    @PutMapping("/{id}")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<KnowledgeBaseView> update(@PathVariable Long id,
                                                 @Valid @RequestBody KnowledgeBaseSaveRequest request) {
        return ApiResponse.ok(knowledgeService.update(RequestContext.requireSpaceId(), id, request));
    }

    @DeleteMapping("/{id}")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        knowledgeService.delete(RequestContext.requireSpaceId(), id);
        return ApiResponse.ok();
    }

    @RequireSpaceRole(SpaceRole.VIEWER)
    @GetMapping("/{id}/docs")
    public ApiResponse<PageResult<KnowledgeDocView>> docs(@PathVariable Long id,
                                                          @RequestParam(required = false) String keyword,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(knowledgeService.listDocs(RequestContext.requireSpaceId(), id, keyword,
                page, size, callerRole()));
    }

    @PostMapping("/{id}/docs/text")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<KnowledgeDocView> createTextDoc(@PathVariable Long id,
                                                       @Valid @RequestBody KnowledgeDocTextRequest request) {
        return ApiResponse.ok(knowledgeService.createTextDoc(RequestContext.requireSpaceId(), id, request));
    }

    @PostMapping(value = "/{id}/docs/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<KnowledgeDocView> uploadDoc(@PathVariable Long id,
                                                   @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(knowledgeService.uploadDoc(RequestContext.requireSpaceId(), id, file));
    }

    @RequireSpaceRole(SpaceRole.VIEWER)
    @GetMapping("/{id}/docs/{docId}")
    public ApiResponse<KnowledgeDocDetail> docDetail(@PathVariable Long id, @PathVariable Long docId) {
        return ApiResponse.ok(knowledgeService.docDetail(RequestContext.requireSpaceId(), id, docId,
                callerRole()));
    }

    @PutMapping("/{id}/docs/{docId}/acl")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<KnowledgeDocView> updateDocAcl(@PathVariable Long id, @PathVariable Long docId,
                                                      @Valid @RequestBody KnowledgeDocAclRequest request) {
        return ApiResponse.ok(knowledgeService.updateDocAcl(RequestContext.requireSpaceId(), id, docId,
                request.allowedRoles(), callerRole()));
    }

    @RequireSpaceRole(SpaceRole.VIEWER)
    @GetMapping("/{id}/docs/{docId}/chunks")
    public ApiResponse<PageResult<KnowledgeChunkView>> chunks(@PathVariable Long id,
                                                              @PathVariable Long docId,
                                                              @RequestParam(defaultValue = "1") int page,
                                                              @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(knowledgeService.chunks(RequestContext.requireSpaceId(), id, docId, page, size,
                callerRole()));
    }

    @PostMapping("/{id}/docs/{docId}/reindex")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<KnowledgeDocView> reindex(@PathVariable Long id, @PathVariable Long docId) {
        return ApiResponse.ok(knowledgeService.reindex(RequestContext.requireSpaceId(), id, docId));
    }

    @DeleteMapping("/{id}/docs/{docId}")
    @RequireSpaceRole(SpaceRole.EDITOR)
    public ApiResponse<Void> deleteDoc(@PathVariable Long id, @PathVariable Long docId) {
        knowledgeService.deleteDoc(RequestContext.requireSpaceId(), id, docId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/search")
    @RequireSpaceRole(SpaceRole.VIEWER)
    public ApiResponse<List<KnowledgeSearchResult>> search(@PathVariable Long id,
                                                           @Valid @RequestBody KnowledgeSearchRequest request) {
        return ApiResponse.ok(knowledgeService.search(RequestContext.requireSpaceId(), id,
                request.query(), request.topK(), callerRole()));
    }

    private static String callerRole() {
        var principal = RequestContext.require();
        return principal.isAdmin() ? "OWNER" : principal.spaceRole();
    }
}
