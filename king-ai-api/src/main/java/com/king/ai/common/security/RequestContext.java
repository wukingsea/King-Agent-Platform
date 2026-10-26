package com.king.ai.common.security;

import com.king.ai.common.BizException;

public final class RequestContext {

    private static final ThreadLocal<UserPrincipal> HOLDER = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void set(UserPrincipal principal) {
        HOLDER.set(principal);
    }

    public static UserPrincipal get() {
        return HOLDER.get();
    }

    public static UserPrincipal require() {
        UserPrincipal principal = HOLDER.get();
        if (principal == null) {
            throw BizException.unauthorized("请先登录");
        }
        return principal;
    }

    public static Long spaceId() {
        return require().spaceId();
    }

    public static Long requireSpaceId() {
        Long spaceId = spaceId();
        if (spaceId == null) {
            throw BizException.badRequest("请先选择业务空间");
        }
        return spaceId;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
