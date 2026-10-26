package com.king.ai.chat;

public record ChatActor(Long userId, String visitorId, String actorName, Long spaceId,
                        String spaceRole, boolean canManage) {

    public static ChatActor user(Long userId, String visitorId, String username, Long spaceId,
                                 String spaceRole, boolean canManage) {
        return new ChatActor(userId, visitorId, username, spaceId,
                spaceRole == null || spaceRole.isBlank() ? "VIEWER" : spaceRole, canManage);
    }

    public static ChatActor visitor(String visitorId, Long spaceId) {
        return new ChatActor(null, visitorId, "visitor:" + visitorId, spaceId, "VIEWER", false);
    }

    public String key() {
        return userId == null ? "visitor:" + visitorId : "user:" + userId;
    }

    public String displayName() {
        return userId == null ? "visitor:" + visitorId : actorName;
    }

    public boolean isVisitor() {
        return userId == null;
    }
}
