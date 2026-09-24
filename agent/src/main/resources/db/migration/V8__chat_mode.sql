-- 会话模式（chat modes）：AUTO 智能路由 / STANDARD 标准 Agent / CHAT 纯聊天（无工具）。
-- 会话级持久字段；存量与新建会话默认 STANDARD。
ALTER TABLE conversation ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'STANDARD';
