package com.king.ai.chat;

import com.king.ai.common.Ids;
import com.king.ai.llm.CancellationToken;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "king-ai.token-store", havingValue = "memory")
public class MemoryChatCancellationRegistry implements ChatCancellationRegistry {

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public String register(CancellationToken token, Long spaceId, String actorKey) {
        String requestId = Ids.publicId();
        entries.put(requestId, new Entry(token, spaceId, actorKey));
        return requestId;
    }

    @Override
    public boolean cancel(String requestId, Long spaceId, String actorKey, boolean canManage) {
        if (requestId == null) {
            return false;
        }
        Entry entry = entries.get(requestId);
        if (entry == null) {
            return false;
        }
        if (spaceId != null && !Objects.equals(entry.spaceId(), spaceId)) {
            return false;
        }
        if (!canManage && !Objects.equals(entry.actorKey(), actorKey)) {
            return false;
        }
        entry.token().cancel();
        return true;
    }

    @Override
    public void remove(String requestId) {
        if (requestId != null) {
            entries.remove(requestId);
        }
    }

    private record Entry(CancellationToken token, Long spaceId, String actorKey) {
    }
}
