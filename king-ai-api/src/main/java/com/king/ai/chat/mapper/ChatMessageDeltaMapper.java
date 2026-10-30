package com.king.ai.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.king.ai.chat.entity.ChatMessageDelta;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageDeltaMapper extends BaseMapper<ChatMessageDelta> {
}
