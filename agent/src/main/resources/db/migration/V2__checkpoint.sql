-- PG checkpoint saver 表结构（PostgresSaver 期望的 schema，与 saver 内置 CREATE IF NOT EXISTS DDL 一致）。
-- saver 以 CREATE_NONE 模式使用（建表交给 Flyway），避免 saver 构造期 DDL 与 Flyway 初始化的先后次序冲突。
CREATE TABLE IF NOT EXISTS GraphThread (
     thread_id UUID PRIMARY KEY,
     thread_name VARCHAR(255),
     is_released BOOLEAN DEFAULT FALSE NOT NULL
);

CREATE TABLE IF NOT EXISTS GraphCheckpoint (
     checkpoint_id UUID PRIMARY KEY,
     parent_checkpoint_id UUID,
     thread_id UUID NOT NULL,
     node_id VARCHAR(255),
     next_node_id VARCHAR(255),
     state_data JSONB NOT NULL,
     state_content_type VARCHAR(100) NOT NULL,
     saved_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

     CONSTRAINT fk_thread
         FOREIGN KEY(thread_id)
         REFERENCES GraphThread(thread_id)
         ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_lg4jcheckpoint_thread_id ON GraphCheckpoint(thread_id);
CREATE INDEX IF NOT EXISTS idx_lg4jcheckpoint_thread_id_saved_at_desc ON GraphCheckpoint(thread_id, saved_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_lg4jthread_thread_name_unreleased ON GraphThread(thread_name) WHERE is_released = FALSE;
