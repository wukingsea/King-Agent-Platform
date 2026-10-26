package com.king.ai.knowledge;

import com.king.ai.common.Texts;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class KnowledgeTextSplitter {

    private static final int MAX_CHUNKS = 5000;
    private static final String BREAK_CHARS = "。！？；.!?;";

    public List<String> split(String text, int chunkSize, int overlap) {
        if (Texts.isBlank(text)) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        int size = Math.max(100, Math.min(chunkSize, 6000));
        int lap = Math.max(0, Math.min(overlap, Math.max(0, size / 2)));
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int tentativeEnd = Math.min(start + size, normalized.length());
            int end = tentativeEnd;
            if (tentativeEnd < normalized.length()) {
                int minimumBreak = start + Math.max(1, size / 2);
                end = findBreak(normalized, minimumBreak, tentativeEnd);
            }
            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= normalized.length()) {
                break;
            }
            int next = end - lap;
            if (next <= start) {
                next = end;
            }
            while (next < normalized.length() && Character.isWhitespace(normalized.charAt(next))) {
                next++;
            }
            start = next;
            if (chunks.size() >= MAX_CHUNKS) {
                throw new IllegalArgumentException("文档分块数量超过上限，请拆分后再上传");
            }
        }
        return chunks;
    }

    private static int findBreak(String text, int minimum, int maximum) {
        for (int i = maximum - 1; i >= minimum; i--) {
            char value = text.charAt(i);
            if (value == '\n') {
                return i + 1;
            }
            if (BREAK_CHARS.indexOf(value) >= 0) {
                int end = i + 1;
                while (end < maximum && isClosing(value == '。' || value == '！' || value == '？' ? '”' : '"',
                        text.charAt(end))) {
                    end++;
                }
                return end;
            }
        }
        return maximum;
    }

    private static boolean isClosing(char quote, char value) {
        return value == quote || value == '）' || value == ')' || value == '】' || value == '」';
    }
}
