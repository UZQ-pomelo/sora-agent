package com.sora.sora_agent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量数据库配置 — PostgreSQL + pgvector。
 * <p>
 * 使用独立的 PostgreSQL 数据源, 文档加载后写入向量库供 RAG 检索。
 * </p>
 */
@Configuration
public class TourAppVectorStoreConfig {

    @Resource
    private TourAppDocumentLoader tourAppDocumentLoader;
    @Resource
    private MyKeywordMetadataEnricher myKeywordMetadataEnricher;

    /**
     * pgvector 向量存储 Bean, Bean 名为 {@code tourappVectorStore}。
     *
     * @param dashscopeEmbeddingModel DashScope Embedding 模型
     * @param jdbcTemplate            PostgreSQL JdbcTemplate (独立数据源)
     */
    @Bean
    VectorStore tourappVectorStore(EmbeddingModel dashscopeEmbeddingModel,
                                   @Qualifier("postgresJdbcTemplate") JdbcTemplate jdbcTemplate) {
        PgVectorStore store = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .initializeSchema(true)
                .build();

        // 显式触发建表: afterPropertiesSet() 在 @Bean 返回后才会被 Spring 回调,
        // 但这里需要在 add() 之前完成建表, 所以手动调用一次。
        store.afterPropertiesSet();

        List<Document> documents = tourAppDocumentLoader.loadMarkdowns();

        //自动补充关键词元信息
        List<Document> enrichedDocuments = myKeywordMetadataEnricher.enrichedDocuments(documents);
        // DashScope embedding API 单次最多 10 条，分批写入
        int batchSize = 10;
        for (int i = 0; i < documents.size(); i += batchSize) {
            int end = Math.min(i + batchSize, documents.size());
            store.add(documents.subList(i, end));
        }
        return store;
    }

}
