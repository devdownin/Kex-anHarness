-- Résultats privés à durée limitée et sérialisation des écritures de contexte entre répliques.
CREATE TABLE kex_harness_lock (
    id VARCHAR(64) PRIMARY KEY
);
CREATE TABLE kex_tool_result (
    id VARCHAR(36) PRIMARY KEY,
    owner VARCHAR(1024) NOT NULL,
    scope VARCHAR(128) NOT NULL,
    tool VARCHAR(1024) NOT NULL,
    arguments TEXT NOT NULL,
    content TEXT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    characters INTEGER NOT NULL
);
CREATE INDEX kex_tool_result_owner ON kex_tool_result(owner);
CREATE INDEX kex_tool_result_expiry ON kex_tool_result(expires_at);

-- Les identifiants privés Kex sont des SHA-256 (64 caractères), pas les UUID de 36
-- caractères du schéma par défaut de Spring AI. Préserver le contenu lors de l'élargissement.
-- Créer d'abord la table couvre aussi une base neuve : l'initialiseur Spring AI peut passer après Flyway.
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(128) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    timestamp TIMESTAMP NOT NULL,
    sequence_id BIGINT NOT NULL
);
ALTER TABLE SPRING_AI_CHAT_MEMORY ALTER COLUMN conversation_id SET DATA TYPE VARCHAR(128);
CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
    ON SPRING_AI_CHAT_MEMORY(conversation_id, timestamp);
CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_SEQUENCE_ID_IDX
    ON SPRING_AI_CHAT_MEMORY(conversation_id, sequence_id);
