package com.king.ai.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.king.ai.system.entity.Space;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SpaceMapper extends BaseMapper<Space> {

    @Select("SELECT * FROM king_ai_space WHERE code = #{code} LIMIT 1")
    Space selectAnyByCode(@Param("code") String code);

    @Update("UPDATE king_ai_space SET deleted_at = now(), updated_at = now() "
            + "WHERE id = #{id} AND deleted_at IS NULL")
    int softDeleteById(@Param("id") Long id);

    @Update("UPDATE king_ai_space SET deleted_at = NULL, status = 0, updated_at = now() WHERE id = #{id}")
    int restoreById(@Param("id") Long id);
}
