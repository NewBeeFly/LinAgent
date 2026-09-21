-- v0.2 多租户：conversation 归属列（tenant_id/user_id 加列 + 回填 + 索引）。
-- 自 V4 拆出后置（Task 1 裁决）：NOT NULL 约束与本任务的写路径改造同步生效，
-- 避免约束先行导致新建会话插入失败。
ALTER TABLE conversation ADD COLUMN tenant_id VARCHAR(64),
                         ADD COLUMN user_id   VARCHAR(64);
UPDATE conversation SET tenant_id = 'default', user_id = 'linmj';
ALTER TABLE conversation ALTER COLUMN tenant_id SET NOT NULL,
                         ALTER COLUMN user_id   SET NOT NULL;
CREATE INDEX idx_conversation_tenant_user ON conversation (tenant_id, user_id);
