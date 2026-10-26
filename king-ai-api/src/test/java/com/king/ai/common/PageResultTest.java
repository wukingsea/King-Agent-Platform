package com.king.ai.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PageResultTest {

    @Test
    void testPageResultMetadata() {
        PageResult<String> result = PageResult.of(List.of("a", "b", "c"), 10, 1, 3);
        
        assertEquals(3, result.list().size());
        assertEquals(10, result.total());
        assertEquals(1, result.page());
        assertEquals(3, result.size());
        assertEquals(4, result.totalPages());
        assertTrue(result.hasNext());
        assertFalse(result.hasPrevious());
    }

    @Test
    void testLastPage() {
        PageResult<String> result = PageResult.of(List.of("a"), 10, 4, 3);
        
        assertEquals(4, result.totalPages());
        assertFalse(result.hasNext());
        assertTrue(result.hasPrevious());
    }

    @Test
    void testEmptyResult() {
        PageResult<String> result = PageResult.of(List.of(), 0, 1, 10);
        
        assertEquals(0, result.total());
        assertEquals(0, result.totalPages());
        assertFalse(result.hasNext());
        assertFalse(result.hasPrevious());
    }
}
