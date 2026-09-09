package com.sora.sora_agent.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 单数据源配置（PostgreSQL）。
 * <p>
 * 唯一 PostgreSQL 数据源同时服务：
 * <ul>
 *   <li>MyBatis-Plus 对话记忆（chat_memory_message 表）</li>
 *   <li>pgvector 向量存储（RAG 检索）</li>
 * </ul>
 * 显式声明避免 Spring Boot 自动配置因 {@code @ConditionalOnMissingBean} 跳过主数据源。
 * </p>
 */
@Configuration
public class DataSourceConfig {

    /**
     * 主数据源属性绑定 — 使用标准 {@code spring.datasource.*} 前缀。
     */
    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * PostgreSQL 主数据源 — 通过 {@link DataSourceProperties} 构建，
     * 自动处理 {@code url} 到 HikariCP {@code jdbcUrl} 的映射。
     */
    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().build();
    }

    /**
     * JdbcTemplate — pgvector 依赖此 Bean 进行数据库操作（复用主数据源）。
     */
    @Bean
    public JdbcTemplate postgresJdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
