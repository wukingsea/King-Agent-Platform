package com.king.ai.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_user_space")
public class UserSpace extends BaseEntity {

    private Long userId;
    private Long spaceId;
    private String role;
}
