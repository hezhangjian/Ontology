CREATE TABLE control.agent_conversations (
    id UUID PRIMARY KEY,
    ontology_id UUID NOT NULL REFERENCES control.ontologies(id) ON DELETE CASCADE,
    title VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_conversations_ontology_updated
    ON control.agent_conversations (ontology_id, updated_at DESC);

CREATE TABLE control.agent_messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES control.agent_conversations(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL CHECK (role IN ('assistant', 'user')),
    content TEXT NOT NULL,
    tools TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_messages_conversation_created
    ON control.agent_messages (conversation_id, created_at, id);
