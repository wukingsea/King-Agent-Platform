package com.king.ai.finops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.king.ai.finops.entity.UsageRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UsageLedgerMapper extends BaseMapper<UsageRecord> {

    @Select("SELECT COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens, " +
            "COALESCE(SUM(completion_tokens), 0) AS completion_tokens, " +
            "COALESCE(SUM(total_tokens), 0) AS total_tokens, " +
            "COALESCE(SUM(cost_micros), 0) AS cost_micros " +
            "FROM king_ai_usage_ledger WHERE space_id = #{spaceId} AND created_at >= #{since}")
    UsageTotals totalsSince(@Param("spaceId") Long spaceId, @Param("since") LocalDateTime since);

    @Select("SELECT model, SUM(prompt_tokens) AS prompt_tokens, " +
            "SUM(completion_tokens) AS completion_tokens, SUM(total_tokens) AS total_tokens, " +
            "SUM(cost_micros) AS cost_micros " +
            "FROM king_ai_usage_ledger WHERE space_id = #{spaceId} AND created_at >= #{since} " +
            "GROUP BY model ORDER BY total_tokens DESC")
    List<ModelUsageRow> totalsByModelSince(@Param("spaceId") Long spaceId,
                                           @Param("since") LocalDateTime since);
}
