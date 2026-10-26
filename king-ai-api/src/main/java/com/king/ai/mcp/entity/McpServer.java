package com.king.ai.mcp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_mcp")
public class McpServer extends BaseEntity {

    private Long spaceId;
    private String name;
    private String transport;
    private String url;
    private String headers;
    private Integer status;
    private String remark;

    @Version
    private Integer version;
}
