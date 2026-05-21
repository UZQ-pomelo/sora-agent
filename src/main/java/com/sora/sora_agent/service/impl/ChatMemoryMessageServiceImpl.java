package com.sora.sora_agent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sora.sora_agent.service.ChatMemoryMessageService;
import com.sora.sora_agent.model.entity.ChatMemoryMessage;
import com.sora.sora_agent.mapper.ChatMemoryMessageMapper;
import org.springframework.stereotype.Service;

/**
* @author Yuki
* @description 针对表【chat_memory_message】的数据库操作Service实现
* @createDate 2026-05-21 21:21:59
*/
@Service
public class ChatMemoryMessageServiceImpl extends ServiceImpl<ChatMemoryMessageMapper, ChatMemoryMessage>
    implements ChatMemoryMessageService {

}




