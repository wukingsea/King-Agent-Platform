package com.king.ai.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.king.ai.agent.entity.Agent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

@Mapper
public interface AgentMapper extends BaseMapper<Agent> {

    /**
     * Archived agents are hidden from every regular query, but session history still
     * needs their names. These lookups deliberately bypass the logical-delete filter.
     */
    @Select("SELECT * FROM king_ai_agent WHERE id = #{id}")
    Agent selectAnyById(@Param("id") Long id);

    @Select("<script>SELECT * FROM king_ai_agent WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<Agent> selectAnyByIds(@Param("ids") Collection<Long> ids);

    @Update("UPDATE king_ai_agent SET deleted_at = now(), updated_at = now() "
            + "WHERE id = #{id} AND deleted_at IS NULL")
    int softDeleteById(@Param("id") Long id);
}
