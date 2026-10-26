package com.king.ai.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_chat_session")
public class ChatSession extends BaseEntity {

    private Long spaceId;
    private Long agentId;
    private Long ownerUserId;
    private String sourceType;
    private String visitorId;
    private String title;
    private Integer messageCount;
    private LocalDateTime lastMessageAt;
}
