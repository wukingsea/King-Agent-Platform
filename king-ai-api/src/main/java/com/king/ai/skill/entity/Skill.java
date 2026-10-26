package com.king.ai.skill.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_skill")
public class Skill extends BaseEntity {

    private Long spaceId;
    private String name;
    private String description;
    private String version;
    private Integer status;

    @Version
    private Integer lockVersion;
}
