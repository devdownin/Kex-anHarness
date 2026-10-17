-- Lectures d'une compétence de domaine par read_skill : la seule mesure d'usage qui ne soit pas
-- inventée. Une invariante, injectée en entier, n'en accumule jamais.
ALTER TABLE kex_agent_learning ADD COLUMN reads INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE kex_agent_learning ADD COLUMN last_read_at TIMESTAMP;
