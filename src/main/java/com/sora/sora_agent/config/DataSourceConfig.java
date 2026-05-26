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
 * 多数据源配置。
 * <p>
 * 显式声明 MySQL（主）和 PostgreSQL（pgvector）两个数据源，
 * 避免 Spring Boot 自动配置因 {@code @ConditionalOnMissingBean} 而跳过主数据源。
 * </p>
 */
@Configuration
public class DataSourceConfig {

    /**
     * MySQL 数据源属性绑定 — 使用标准 {@code spring.datasource.*} 前缀。
     */
    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSourceProperties mysqlDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * MySQL 主数据源 — 通过 {@link DataSourceProperties} 构建，
     * 自动处理 {@code url} 到 HikariCP {@code jdbcUrl} 的映射。
     */
    @Bean
    @Primary
    public DataSource mysqlDataSource(DataSourceProperties mysqlDataSourceProperties) {
        return mysqlDataSourceProperties.initializeDataSourceBuilder().build();
    }

    /**
     * PostgreSQL 数据源 — 供 pgvector 向量存储使用。
     */
    @Bean
    @ConfigurationProperties(prefix = "app.datasource.postgresql")
    public DataSource postgresDataSource() {
        return org.springframework.boot.jdbc.DataSourceBuilder.create().build();
    }

    /**
     * PostgreSQL JdbcTemplate — pgvector 依赖此 Bean 进行数据库操作。
     * <p>
     * 显式指定 {@code postgresDataSource} 防止 Spring 注入 {@code @Primary} 的 MySQL 数据源。
     * </p>
     */
    @Bean
    public JdbcTemplate postgresJdbcTemplate(@Qualifier("postgresDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
