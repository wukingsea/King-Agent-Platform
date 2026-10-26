package com.king.ai.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BizExceptionTest {

    @Test
    void testExceptionCodes() {
        BizException badRequest = BizException.badRequest("bad");
        assertEquals(400, badRequest.getCode());
        assertEquals("bad", badRequest.getMessage());

        BizException unauthorized = BizException.unauthorized("unauth");
        assertEquals(401, unauthorized.getCode());

        BizException forbidden = BizException.forbidden("forbidden");
        assertEquals(403, forbidden.getCode());

        BizException notFound = BizException.notFound("not found");
        assertEquals(404, notFound.getCode());

        BizException conflict = BizException.conflict("conflict");
        assertEquals(409, conflict.getCode());

        BizException tooMany = BizException.tooManyRequests("too many");
        assertEquals(429, tooMany.getCode());
    }
}
