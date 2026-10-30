package com.king.ai.system.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_space")
public class Space extends BaseEntity {

    private String name;
    private String code;
    private String description;
    private Integer status;
    /** Monthly token budget; 0 means unlimited. */
    private Long monthlyTokenQuota;

    @Version
    private Integer version;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
