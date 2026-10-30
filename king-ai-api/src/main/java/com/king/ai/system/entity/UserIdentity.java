package com.king.ai.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_user_identity")
public class UserIdentity extends BaseEntity {

    private Long userId;
    private String provider;
    private String subject;
    private String email;
}
