-- v0.2 多租户：用户表（本期鉴权唯一数据源）。
-- conversation 归属列（tenant_id/user_id 加列 + 回填 + 索引）后置到 V5，
-- 避免 NOT NULL 约束先于写路径改造生效导致 conversation 写入失败。
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
