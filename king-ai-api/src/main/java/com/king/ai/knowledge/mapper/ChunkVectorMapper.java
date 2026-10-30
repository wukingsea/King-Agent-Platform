package com.king.ai.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.king.ai.knowledge.entity.ChunkVector;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChunkVectorMapper extends BaseMapper<ChunkVector> {

    /**
     * Keyset page over one knowledge base's vectors. Only the columns the scorer needs are
     * selected so a large knowledge base never has to be materialised in memory at once.
     */
    @Select("SELECT chunk_id, embedding FROM king_ai_chunk_vector "
            + "WHERE knowledge_base_id = #{knowledgeBaseId} AND chunk_id > #{afterChunkId} "
            + "ORDER BY chunk_id LIMIT #{limit}")
    List<ChunkVector> scanBatch(@Param("knowledgeBaseId") Long knowledgeBaseId,
                                @Param("afterChunkId") long afterChunkId,
                                @Param("limit") int limit);
}
