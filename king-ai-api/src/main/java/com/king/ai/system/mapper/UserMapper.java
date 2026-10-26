package com.king.ai.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.king.ai.system.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
