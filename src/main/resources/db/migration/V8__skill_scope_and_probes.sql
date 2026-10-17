-- Portée et déclencheurs fixés à la proposition ; rejeux contrastifs ajoutés pendant la revue.
-- Une compétence antérieure garde scope NULL : lue comme DOMAIN sans déclencheur, elle reste
-- injectée exactement comme avant.
ALTER TABLE kex_agent_learning ADD COLUMN scope VARCHAR(16);
ALTER TABLE kex_agent_learning ADD COLUMN triggers TEXT;
ALTER TABLE kex_agent_learning ADD COLUMN probes TEXT;
