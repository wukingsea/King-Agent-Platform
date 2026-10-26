package com.king.ai.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.king.ai.agent.entity.Agent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentMapper extends BaseMapper<Agent> {
}
