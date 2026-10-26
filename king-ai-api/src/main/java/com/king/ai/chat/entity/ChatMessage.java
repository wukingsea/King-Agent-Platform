package com.king.ai.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_chat_message")
public class ChatMessage extends BaseEntity {

    private Long sessionId;
    private String role;
    private String content;
    private String reasoning;
    private String toolTrace;
    private String citations;
    private String status;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long durationMs;
}
