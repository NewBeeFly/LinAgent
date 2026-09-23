-- Task 5：turn.status 新增 'WAITING_APPROVAL'（审批 HITL 中断态）——
-- V1 内联 CHECK 约束按 Postgres 默认命名 turn_status_check，先删后建。
-- 等待轮决议后 resume 补终态（COMPLETED/FAILED），断连/取消转 FAILED（CANCELLED_WHILE_WAITING）。
ALTER TABLE turn DROP CONSTRAINT IF EXISTS turn_status_check;
ALTER TABLE turn ADD CONSTRAINT turn_status_check
    CHECK (status IN ('RUNNING','COMPLETED','FAILED','WAITING_APPROVAL'));
