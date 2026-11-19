CREATE TABLE copilot_conversations (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    company_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_copilot_owner ON copilot_conversations(owner_id, tenant_id, company_id, updated_at);
CREATE TABLE copilot_runs (
    run_id VARCHAR(100) PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES copilot_conversations(id),
    request_json TEXT NOT NULL,
    response_json TEXT,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_copilot_runs_conversation ON copilot_runs(conversation_id);
CREATE TABLE copilot_messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES copilot_conversations(id),
    sequence_no BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('user', 'assistant')),
    content TEXT NOT NULL,
    run_id VARCHAR(100) NOT NULL REFERENCES copilot_runs(run_id),
    status VARCHAR(30) NOT NULL,
    response_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (conversation_id, sequence_no),
    UNIQUE (conversation_id, run_id, role)
);
