package com.sora.sora_agent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 自动提取元数据
 */
public @Component
class MyKeywordMetadataEnricher{

    @Resource
    private ChatModel chatModel;

    List<Document> enrichedDocuments(List<Document> documents) {
        KeywordMetadataEnricher enricher = new KeywordMetadataEnricher(this.chatModel,5);
        return enricher.apply(documents);
    }
}
