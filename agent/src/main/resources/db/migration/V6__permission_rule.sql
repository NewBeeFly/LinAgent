CREATE TABLE permission_rule (
  id BIGSERIAL PRIMARY KEY,
  tenant_id VARCHAR NOT NULL,
  user_id VARCHAR NOT NULL,
  tool_name VARCHAR NOT NULL,     -- 'shell' / 'write_file'
  pattern VARCHAR NOT NULL,       -- '*'（工具级）或命令/路径前缀（'pip install *'）
  effect VARCHAR NOT NULL,        -- MVP 仅 'ALLOW'；deny/ask 值预留二期
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, user_id, tool_name, pattern)
);
