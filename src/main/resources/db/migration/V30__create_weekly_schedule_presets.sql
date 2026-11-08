-- Saved presets are independent of applied schedules and their historical evidence.
CREATE TABLE weekly_schedule_presets (
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL,
    id UUID NOT NULL,
    display_order SMALLINT NOT NULL CHECK (display_order BETWEEN 1 AND 4),
    rotation_included BOOLEAN NOT NULL DEFAULT false,
    preset_version BIGINT NOT NULL DEFAULT 0 CHECK (preset_version >= 0),
    assignments JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (
        CASE WHEN jsonb_typeof(assignments) = 'array' THEN jsonb_array_length(assignments) <= 7 ELSE false END
    ),
    PRIMARY KEY (user_id, data_generation, id),
    UNIQUE (user_id, data_generation, display_order) DEFERRABLE INITIALLY IMMEDIATE,
    FOREIGN KEY (user_id, data_generation) REFERENCES users(id, data_generation)
);

CREATE TABLE weekly_schedule_state (
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL,
    active_preset_id UUID NOT NULL,
    auto_enabled BOOLEAN NOT NULL DEFAULT false,
    state_version BIGINT NOT NULL DEFAULT 0 CHECK (state_version >= 0),
    next_rotation_at TIMESTAMPTZ,
    PRIMARY KEY (user_id, data_generation),
    FOREIGN KEY (user_id, data_generation, active_preset_id)
        REFERENCES weekly_schedule_presets(user_id, data_generation, id),
    CHECK (auto_enabled OR next_rotation_at IS NULL)
);

CREATE INDEX weekly_schedule_state_due ON weekly_schedule_state(next_rotation_at, user_id, data_generation)
    WHERE next_rotation_at IS NOT NULL;
