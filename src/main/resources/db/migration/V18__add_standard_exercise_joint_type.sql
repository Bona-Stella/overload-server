-- Unknown classifications remain NULL; OTHER means explicitly not applicable.
-- Do not infer joint mechanics from names, load types or muscle mapping counts.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE standard_exercise
    ADD COLUMN joint_type TEXT,
    ADD CONSTRAINT standard_exercise_joint_type_check
        CHECK (joint_type IS NULL OR joint_type IN ('COMPOUND', 'ISOLATION', 'OTHER'));

COMMENT ON COLUMN standard_exercise.joint_type IS
    'Reviewed joint classification. NULL = unclassified; OTHER = explicitly not applicable. Never inferred from muscle count.';
