package com.king.ai.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextsTest {

    @Test
    void testIsBlank() {
        assertTrue(Texts.isBlank(null));
        assertTrue(Texts.isBlank(""));
        assertTrue(Texts.isBlank("   "));
        assertFalse(Texts.isBlank("text"));
    }

    @Test
    void testTrimTo() {
        assertNull(Texts.trimTo(null, 10));
        assertEquals("abc", Texts.trimTo("abc", 10));
        assertEquals("abc", Texts.trimTo("abcdef", 3));
    }

    @Test
    void testOneLine() {
        assertEquals("", Texts.oneLine(null, 10));
        assertEquals("hello world", Texts.oneLine("hello\nworld", 20));
        assertEquals("hello wo", Texts.oneLine("hello world", 8));
    }

    @Test
    void testIsValidHttpUrl() {
        assertTrue(Texts.isValidHttpUrl("http://example.com"));
        assertTrue(Texts.isValidHttpUrl("https://example.com/path"));
        assertFalse(Texts.isValidHttpUrl("ftp://example.com"));
        assertFalse(Texts.isValidHttpUrl("not-a-url"));
        assertFalse(Texts.isValidHttpUrl(null));
    }
}
