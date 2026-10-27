package com.king.ai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_supplier")
public class Supplier extends BaseEntity {

    private Long spaceId;
    private String name;
    private String type;
    private String baseUrl;
    private String apiKey;
    /**
     * 清空密钥时轮换时间必须能写回 NULL；默认 NOT_NULL 策略会跳过 null 字段，
     * 导致无密钥的供应商仍显示为近期轮换。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime apiKeyRotatedAt;
    private String headers;
    private Integer status;
    private String remark;

    @Version
    private Integer version;
}
