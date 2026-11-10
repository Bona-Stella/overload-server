SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

CREATE TABLE exercise_db_v1_candidate (
    source_exercise_id TEXT COLLATE "C" PRIMARY KEY CHECK (source_exercise_id ~ '^[A-Za-z0-9]{7}$'),
    source_file_sha256 TEXT NOT NULL CHECK (source_file_sha256 ~ '^[0-9a-f]{64}$'),
    source_position INTEGER NOT NULL CHECK (source_position > 0),
    original JSONB NOT NULL CHECK (
        jsonb_typeof(original) = 'object'
        AND original ? 'exerciseId'
        AND original ->> 'exerciseId' IS NOT NULL
        AND original ->> 'exerciseId' = source_exercise_id
    ),
    findings JSONB NOT NULL CHECK (jsonb_typeof(findings) = 'object'),
    review_draft JSONB CHECK (jsonb_typeof(review_draft) = 'object'),
    draft_version BIGINT NOT NULL DEFAULT 0 CHECK (draft_version >= 0),
    UNIQUE (source_file_sha256, source_position)
);
