-- ============================================================
-- sora-agent MySQL 表结构（手动执行一次）
-- 与实体类 src/main/java/com/sora/sora_agent/model/entity/ChatMemoryMessage.java 对应
-- 用法：mysql -u<user> -p <db> < sql/schema.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS chat_memory_message (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    conversation_id VARCHAR(128) NOT NULL COMMENT '对话id（支持命名空间前缀，如 manus:xxx，用于多 agent 类型隔离）',
    message_index   BIGINT       NOT NULL COMMENT '会话内消息序号（自增，从 0 开始）',
    message_type    VARCHAR(32)  NOT NULL COMMENT '消息类型：user/assistant/system 等（存小写）',
    message_text    TEXT         NULL     COMMENT '消息文本',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（不传则由数据库默认填充）',
    PRIMARY KEY (id),
    KEY idx_conversation (conversation_id, message_index) COMMENT '按会话取历史 + 会话列表分组查询'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '对话记忆消息表';
