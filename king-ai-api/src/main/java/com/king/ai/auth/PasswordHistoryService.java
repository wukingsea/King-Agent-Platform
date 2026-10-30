package com.king.ai.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.king.ai.common.BizException;
import com.king.ai.system.entity.PasswordHistory;
import com.king.ai.system.mapper.PasswordHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Remembers the last few password hashes so a user cannot cycle back to a recently used
 * password. Hashes are stored with the same encoder as the user table.
 */
@Service
@RequiredArgsConstructor
public class PasswordHistoryService {

    private static final int HISTORY_LIMIT = 5;

    private final PasswordHistoryMapper historyMapper;
    private final PasswordEncoder passwordEncoder;

    public void ensureNotReused(Long userId, String rawPassword) {
        List<PasswordHistory> recent = recent(userId);
        for (PasswordHistory entry : recent) {
            if (passwordEncoder.matches(rawPassword, entry.getPasswordHash())) {
                throw BizException.badRequest("新密码不能与最近 " + HISTORY_LIMIT + " 次使用的密码相同");
            }
        }
    }

    @Transactional
    public void record(Long userId, String encodedPassword) {
        PasswordHistory entry = new PasswordHistory();
        entry.setUserId(userId);
        entry.setPasswordHash(encodedPassword);
        entry.setCreatedAt(LocalDateTime.now());
        historyMapper.insert(entry);
        List<PasswordHistory> recent = recent(userId);
        if (recent.size() >= HISTORY_LIMIT) {
            Long oldestKept = recent.get(recent.size() - 1).getId();
            historyMapper.delete(new LambdaQueryWrapper<PasswordHistory>()
                    .eq(PasswordHistory::getUserId, userId)
                    .lt(PasswordHistory::getId, oldestKept));
        }
    }

    private List<PasswordHistory> recent(Long userId) {
        return historyMapper.selectList(new LambdaQueryWrapper<PasswordHistory>()
                .eq(PasswordHistory::getUserId, userId)
                .orderByDesc(PasswordHistory::getId)
                .last("LIMIT " + HISTORY_LIMIT));
    }
}
