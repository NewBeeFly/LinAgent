-- v0.2 多租户：用户表（本期鉴权唯一数据源）+ conversation 归属列
CREATE TABLE app_user (
  tenant_id  VARCHAR(64)  NOT NULL,
  user_id    VARCHAR(64)  NOT NULL,
  name       VARCHAR(128) NOT NULL,
  created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, user_id)
);
INSERT INTO app_user (tenant_id, user_id, name) VALUES
  ('default', 'linmj', '林同学'),
  ('default', 'tester', '测试');

ALTER TABLE conversation ADD COLUMN tenant_id VARCHAR(64),
                         ADD COLUMN user_id   VARCHAR(64);
UPDATE conversation SET tenant_id = 'default', user_id = 'linmj';
ALTER TABLE conversation ALTER COLUMN tenant_id SET NOT NULL,
                         ALTER COLUMN user_id   SET NOT NULL;
CREATE INDEX idx_conversation_tenant_user ON conversation (tenant_id, user_id);
