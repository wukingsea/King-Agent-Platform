package com.king.ai.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流式输出的增量片段。聊天过程中的分片只追加不重写，避免每 800ms 全量更新消息行。
 */
@Data
@TableName("king_ai_chat_message_delta")
public class ChatMessageDelta {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long messageId;
    private String contentDelta;
    private String reasoningDelta;
    private String toolTrace;
    private String citations;
    private LocalDateTime createdAt;
}
