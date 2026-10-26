package com.king.ai.chat;

import com.king.ai.llm.CancellationToken;

public interface ChatCancellationRegistry {

    String register(CancellationToken token, Long spaceId, String actorKey);

    boolean cancel(String requestId, Long spaceId, String actorKey, boolean canManage);

    void remove(String requestId);
}
