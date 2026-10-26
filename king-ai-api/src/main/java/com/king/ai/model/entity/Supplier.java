package com.king.ai.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_supplier")
public class Supplier extends BaseEntity {

    private Long spaceId;
    private String name;
    private String type;
    private String baseUrl;
    private String apiKey;
    private String headers;
    private Integer status;
    private String remark;

    @Version
    private Integer version;
}
