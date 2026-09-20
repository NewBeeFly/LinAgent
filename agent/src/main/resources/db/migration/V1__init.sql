CREATE TABLE conversation (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(200),
    thread_id       VARCHAR(100) NOT NULL,
    compact_summary TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE turn (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    seq             INT         NOT NULL,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    finish_reason   VARCHAR(50),
    usage           JSONB,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    UNIQUE (conversation_id, seq)
);

CREATE TABLE message (
    id          BIGSERIAL PRIMARY KEY,
    turn_id     BIGINT       NOT NULL REFERENCES turn(id) ON DELETE CASCADE,
    seq         INT          NOT NULL,
    msg_type    VARCHAR(20)  NOT NULL CHECK (msg_type IN
        ('USER','THINKING','TEXT','TOOL_CALL','TOOL_RESULT','ERROR','SUMMARY')),
    content     TEXT,
    call_id     VARCHAR(100),
    tool_name   VARCHAR(100),
    arguments   JSONB,
    result      TEXT,
    success     BOOLEAN,
    duration_ms BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (turn_id, seq)
);

CREATE INDEX idx_turn_conversation ON turn(conversation_id, seq);
CREATE INDEX idx_message_turn ON message(turn_id, seq);
