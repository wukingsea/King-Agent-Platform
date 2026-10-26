package com.king.ai.system;

import com.king.ai.common.ApiResponse;
import com.king.ai.common.PageResult;
import com.king.ai.common.security.RequireAdmin;
import com.king.ai.common.security.RequestContext;
import com.king.ai.system.dto.PasswordResetRequest;
import com.king.ai.system.dto.UserCreateRequest;
import com.king.ai.system.dto.UserOption;
import com.king.ai.system.dto.UserUpdateRequest;
import com.king.ai.system.dto.UserView;
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
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    @RequireAdmin
    public ApiResponse<PageResult<UserView>> page(@RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(userService.page(Math.max(1, page), Math.min(100, Math.max(1, size)), keyword));
    }

    @GetMapping("/options")
    @RequireAdmin
    public ApiResponse<List<UserOption>> options() {
        return ApiResponse.ok(userService.options());
    }

    @PostMapping
    @RequireAdmin
    public ApiResponse<UserView> create(@Valid @RequestBody UserCreateRequest request) {
        return ApiResponse.ok(userService.create(request));
    }

    @PutMapping("/{id}")
    @RequireAdmin
    public ApiResponse<UserView> update(@PathVariable Long id,
                                        @Valid @RequestBody UserUpdateRequest request) {
        return ApiResponse.ok(userService.update(id, request, RequestContext.require().id()));
    }

    @PutMapping("/{id}/password")
    @RequireAdmin
    public ApiResponse<Void> resetPassword(@PathVariable Long id,
                                           @Valid @RequestBody PasswordResetRequest request) {
        userService.resetPassword(id, request.newPassword());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @RequireAdmin
    public ApiResponse<Void> delete(@PathVariable Long id) {
        userService.delete(id, RequestContext.require().id());
        return ApiResponse.ok();
    }
}
