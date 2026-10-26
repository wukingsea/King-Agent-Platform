package com.king.ai.common;

import java.util.List;

public record PageResult<T>(List<T> list, long total, int page, int size, int totalPages, boolean hasNext, boolean hasPrevious) {

    public static <T> PageResult<T> of(List<T> list, long total, int page, int size) {
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        boolean hasNext = page < totalPages;
        boolean hasPrevious = page > 1;
        return new PageResult<>(list, total, page, size, totalPages, hasNext, hasPrevious);
    }
}
