package com.sora.sora_agent.rag;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * 创建自定义的 RAG 检索增强顾问的工厂。
 *
 * <p>相比框架默认 {@code QuestionAnswerAdvisor}，这里可控检索参数（topK=3、
 * 相似度阈值 0.5）并挂空上下文兜底提示（检索无结果时不胡说）。</p>
 *
 * <p>注意：原实现带 {@code excerpt_keywords} 关键词过滤，但该参数需静态传入关键词，
 * 而正确做法是从 query 动态提取，且字符串匹配过滤效果不如向量相似度，故移除。</p>
 */
public class TourAppRagCustomAdvisorFactory {

    /**
     * 创建自定义的 RAG 检索增强顾问。
     *
     * @param vectorStore 向量存储
     * @return 自定义的 RAG 检索增强顾问
     */
    public static Advisor createTourAppRagCustomAdvisor(VectorStore vectorStore) {
        DocumentRetriever documentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(0.5) // 相似度阈值
                .topK(3) // 返回文档数量
                .build();
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .queryAugmenter(TourAppContextualQueryAugmenterFactory.createInstance())
                .build();
    }
}

