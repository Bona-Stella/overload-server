-- Explicit per-set additional load only. Legacy weight and approval evidence remain unchanged.
-- NULL preserves old JSON/hashes. An object with a null value represents an explicit clear.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
ALTER TABLE performed_sets ADD COLUMN bodyweight_load JSONB;
ALTER TABLE performed_sets ADD CONSTRAINT performed_sets_bodyweight_load_check CHECK (
    bodyweight_load IS NULL OR (
        load_type = 'BODYWEIGHT'
        AND jsonb_typeof(bodyweight_load) = 'object'
        AND bodyweight_load ? 'additionalWeightKg'
        AND bodyweight_load - 'additionalWeightKg' = '{}'::jsonb
        AND (
            bodyweight_load->'additionalWeightKg' = 'null'::jsonb
            OR (jsonb_typeof(bodyweight_load->'additionalWeightKg') = 'string'
                AND bodyweight_load->>'additionalWeightKg' ~ '^(0|[1-9][0-9]{0,13})(\.[0-9]{0,5}[1-9])?$')
        )
    ) IS TRUE
);
