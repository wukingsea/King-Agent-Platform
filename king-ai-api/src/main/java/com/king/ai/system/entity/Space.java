package com.king.ai.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_space")
public class Space extends BaseEntity {

    private String name;
    private String code;
    private String description;
    private Integer status;

    @Version
    private Integer version;
}
