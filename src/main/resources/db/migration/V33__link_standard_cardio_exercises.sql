SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE standard_exercise
    ADD COLUMN cardio_activity_id uuid REFERENCES cardio_activity_catalog(id),
    ADD CHECK (cardio_activity_id IS NULL OR record_method = 'CARDIO');

ALTER TABLE routine_cardio_placements
    ADD COLUMN standard_exercise_id uuid REFERENCES standard_exercise(id),
    ADD CHECK (standard_exercise_id IS NULL OR activity_id IS NOT NULL);

ALTER TABLE session_exercises
    DROP CONSTRAINT session_exercises_check,
    ADD COLUMN cardio_activity_id uuid REFERENCES cardio_activity_catalog(id),
    ADD COLUMN cardio_kind text CHECK (cardio_kind IN ('TREADMILL', 'BIKE', 'OUTDOOR', 'STAIRS', 'OTHER')),
    ADD CONSTRAINT session_exercises_check CHECK (
        (kind = 'WEIGHT' AND standard_exercise_id IS NOT NULL AND cardio_activity_id IS NULL AND cardio_kind IS NULL)
        OR (kind = 'CARDIO' AND (
            (standard_exercise_id IS NULL AND cardio_activity_id IS NULL AND cardio_kind IS NULL AND name_at_addition IS NULL)
            OR (standard_exercise_id IS NOT NULL AND cardio_activity_id IS NOT NULL AND cardio_kind IS NOT NULL AND name_at_addition IS NOT NULL)
        ))
    );
