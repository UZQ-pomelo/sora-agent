package com.sora.sora_agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sora.sora_agent.model.dto.ConversationSummary;
import com.sora.sora_agent.model.entity.ChatMemoryMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 针对表【chat_memory_message】的数据库操作Mapper
 *
 * @author Yuki
 * @createDate 2026-05-21 21:21:59
 */
public interface ChatMemoryMessageMapper extends BaseMapper<ChatMemoryMessage> {

    /**
     * 会话列表：按 conversation_id 分组，取首条用户消息做标题、最后消息 id 做排序。
     *
     * @param namespace 命名空间前缀（含冒号，如 {@code manus:}）；返回该前缀开头的会话
     */
    @Select("SELECT c.conversation_id AS conversationId, "
            + "  (SELECT m.message_text FROM chat_memory_message m "
            + "   WHERE m.conversation_id = c.conversation_id AND LOWER(m.message_type) = 'user' "
            + "   ORDER BY m.message_index ASC LIMIT 1) AS title, "
            + "  COUNT(*) AS messageCount, "
            + "  SUM(LENGTH(COALESCE(c.message_text, ''))) AS totalChars, "
            + "  MAX(c.id) AS lastId, "
            + "  MAX(c.create_time) AS lastTime "
            + "FROM chat_memory_message c "
            + "WHERE c.conversation_id LIKE CONCAT(#{namespace}, '%') "
            + "GROUP BY c.conversation_id "
            + "ORDER BY lastId DESC")
    List<ConversationSummary> listConversations(@Param("namespace") String namespace);
}
