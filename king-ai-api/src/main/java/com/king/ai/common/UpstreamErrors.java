package com.king.ai.common;

/**
 * Client-facing messages for outbound connectivity failures. Upstream exception text can echo
 * request headers, tokens or vendor internals, so it is logged server-side only and never
 * returned to the browser.
 */
public final class UpstreamErrors {

    public static final String CONNECTION_TEST_FAILED =
            "连接测试失败，请检查服务地址、凭据与网络连通性";

    private UpstreamErrors() {
    }
}
