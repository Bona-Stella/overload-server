-- Catalog only: existing personal exercises, routines and workout contracts are unchanged.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

CREATE TABLE standard_exercise (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (name !~ '^[[:space:]]*$'),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE exercise_alias (
    standard_exercise_id UUID NOT NULL REFERENCES standard_exercise(id),
    alias TEXT NOT NULL CHECK (alias !~ '^[[:space:]]*$'),
    normalized_alias TEXT NOT NULL CHECK (normalized_alias !~ '^[[:space:]]*$'),
    PRIMARY KEY (standard_exercise_id, normalized_alias)
);

CREATE TABLE muscle (
    id UUID PRIMARY KEY,
    group_code TEXT NOT NULL CHECK (group_code IN ('CHEST','BACK','SHOULDERS','ARMS','LEGS','CORE')),
    name TEXT NOT NULL CHECK (name !~ '^[[:space:]]*$'),
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE exercise_muscle (
    standard_exercise_id UUID NOT NULL REFERENCES standard_exercise(id),
    muscle_id UUID NOT NULL REFERENCES muscle(id),
    role TEXT NOT NULL CHECK (role IN ('PRIMARY','SECONDARY')),
    PRIMARY KEY (standard_exercise_id, muscle_id)
);

CREATE TABLE manufacturer (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (name !~ '^[[:space:]]*$'),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
