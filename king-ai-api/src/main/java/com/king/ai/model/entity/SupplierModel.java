package com.king.ai.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_supplier_model")
public class SupplierModel extends BaseEntity {

    private Long spaceId;
    private Long supplierId;
    private String name;
    private String model;
    private String type;
    private Integer dimension;
    private Integer status;

    @Version
    private Integer version;
}
