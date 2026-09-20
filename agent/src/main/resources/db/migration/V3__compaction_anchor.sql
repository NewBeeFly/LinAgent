-- 压缩锚点：已摘要到哪个 turn seq（fix round 1）。
-- 压缩的 token 估算基准 = compact_summary 体量 + 锚点之后的新增消息体量，
-- 与"给模型的记忆"（摘要 + 新线程历史）同量纲；已摘要的展示历史不重复计入。
ALTER TABLE conversation ADD COLUMN compacted_turn_seq INT NOT NULL DEFAULT 0;
