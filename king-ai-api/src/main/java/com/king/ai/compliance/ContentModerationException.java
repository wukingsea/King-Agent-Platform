package com.king.ai.compliance;

public class ContentModerationException extends RuntimeException {

    private final String category;

    public ContentModerationException(String category) {
        super("内容审核未通过");
        this.category = category;
    }

    public String category() {
        return category;
    }
}
