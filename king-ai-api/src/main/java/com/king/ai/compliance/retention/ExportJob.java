package com.king.ai.compliance.retention;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.king.ai.system.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("king_ai_export_job")
public class ExportJob extends BaseEntity {

    private Long spaceId;
    private String exportType;
    private String format;
    private String status;
    private LocalDateTime fromAt;
    private LocalDateTime toAt;
    private String fileName;
    private String filePath;
    private Long rowCount;
    private String errorMessage;
    private Long requestedBy;
    private LocalDateTime expiresAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @Version
    private Integer version;
}
