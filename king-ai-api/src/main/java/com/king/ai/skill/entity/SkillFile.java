package com.king.ai.skill.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_skill_file")
public class SkillFile extends BaseEntity {

    private Long skillId;
    private Long parentId;
    private String name;
    private Integer type;
    private String content;
    private Integer locked;
    private Integer sortOrder;
}
