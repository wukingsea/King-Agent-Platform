package com.king.ai.knowledge.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("king_ai_chunk_vector")
public class ChunkVector {

    @TableId(value = "chunk_id", type = IdType.INPUT)
    private Long chunkId;

    private Long spaceId;
    private Long knowledgeBaseId;
    private Long docId;
    private String embedding;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
