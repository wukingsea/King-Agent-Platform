package com.king.ai.common;

public final class OptimisticLocks {

    private OptimisticLocks() {
    }

    public static void requireUpdated(int rows, String resource) {
        if (rows == 0) {
            throw BizException.conflict(resource + "已被其他用户修改，请刷新后重试");
        }
    }
}
