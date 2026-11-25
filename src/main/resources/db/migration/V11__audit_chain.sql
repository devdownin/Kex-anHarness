-- Chaîne de l'audit : chaque entrée porte l'empreinte de la précédente. Une ligne modifiée, retirée
-- ou insérée après coup rompt la chaîne à cet endroit, et la vérification le dit
-- (GET /api/agent/supervision/audit/integrity). Les entrées écrites avant cette migration n'ont pas de
-- rang : elles restent lisibles, comptées à part, mais ne peuvent pas être vérifiées.
ALTER TABLE kex_supervision_audit ADD COLUMN seq BIGINT;
ALTER TABLE kex_supervision_audit ADD COLUMN previous_hash VARCHAR(64);
ALTER TABLE kex_supervision_audit ADD COLUMN entry_hash VARCHAR(64);
-- Unique : deux répliques qui écriraient en même temps le même rang ne peuvent pas faire bifurquer la
-- chaîne ; la seconde échoue sur ce rang et recommence à partir du nouveau dernier maillon.
CREATE UNIQUE INDEX kex_supervision_audit_seq ON kex_supervision_audit (seq);
