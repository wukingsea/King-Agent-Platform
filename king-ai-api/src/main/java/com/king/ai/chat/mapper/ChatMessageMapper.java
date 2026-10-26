package com.king.ai.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.king.ai.chat.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    @Select("SELECT COALESCE(SUM(m.prompt_tokens + m.completion_tokens), 0) " +
            "FROM king_ai_chat_message m JOIN king_ai_chat_session s ON s.id = m.session_id " +
            "WHERE s.space_id = #{spaceId}")
    long sumTokensBySpace(@Param("spaceId") Long spaceId);
}
