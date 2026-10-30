package com.king.ai.system.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_user")
public class User extends BaseEntity {

    private String username;
    private String password;
    private String nickname;
    private String email;
    private String role;
    private Integer status;
    private Integer mustChangePassword;
    /**
     * 只允许登录流程通过显式 wrapper 写入：整实体 updateById 会把并发登录写入的
     * 较新时间覆盖回加载时的旧值，因此 from-entity 更新永不携带该列。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime lastLoginAt;

    private Integer mfaEnabled;
    private String mfaSecret;
    private String mfaRecoveryCodes;
    private LocalDateTime mfaEnrolledAt;

    @Version
    private Integer version;
}
