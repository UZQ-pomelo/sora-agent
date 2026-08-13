-- ============================================================
-- sora-agent PostgreSQL 表结构（手动执行一次）
-- 与实体类 src/main/java/com/sora/sora_agent/model/entity/ChatMemoryMessage.java 对应
-- 用法：psql -U <user> -d <db> -f sql/schema.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS chat_memory_message (
    id              BIGSERIAL   PRIMARY KEY,
    conversation_id VARCHAR(128) NOT NULL,
    message_index   BIGINT      NOT NULL,
    message_type    VARCHAR(32) NOT NULL,
    message_text    TEXT        NULL,
    create_time     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_conversation_index UNIQUE (conversation_id, message_index)
);

CREATE INDEX IF NOT EXISTS idx_conversation ON chat_memory_message (conversation_id, message_index);
