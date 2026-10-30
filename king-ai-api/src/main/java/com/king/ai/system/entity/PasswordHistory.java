package com.king.ai.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("king_ai_password_history")
public class PasswordHistory {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String passwordHash;
    private LocalDateTime createdAt;
}
