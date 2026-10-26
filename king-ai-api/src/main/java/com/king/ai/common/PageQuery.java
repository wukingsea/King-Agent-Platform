package com.king.ai.common;

/**
 * Normalized pagination parameters shared by list endpoints. The upper bound mirrors the
 * MyBatis-Plus pagination interceptor's {@code maxLimit} so callers cannot request unbounded pages.
 */
public record PageQuery(int page, int size) {

    public static final int MAX_SIZE = 200;

    public static PageQuery of(Integer page, Integer size, int defaultSize) {
        int safePage = page == null ? 1 : Math.max(1, page);
        int requested = size == null ? defaultSize : size;
        int safeSize = Math.min(MAX_SIZE, Math.max(1, requested));
        return new PageQuery(safePage, safeSize);
    }
}
