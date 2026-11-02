-- New evidence starts here. Never reconstruct prior schedules or overwrite workout facts.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

CREATE TABLE routine_plan_history (
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL CHECK (data_generation >= 0),
    routine_id UUID NOT NULL,
    routine_version BIGINT NOT NULL CHECK (routine_version >= 0),
    recorded_at TIMESTAMPTZ NOT NULL,
    deleted BOOLEAN NOT NULL,
    plan JSONB NOT NULL CHECK (jsonb_typeof(plan) = 'object'),
    PRIMARY KEY (routine_id, routine_version),
    FOREIGN KEY (user_id, data_generation, routine_id) REFERENCES routines(user_id, data_generation, id)
);
CREATE INDEX routine_plan_history_scope ON routine_plan_history(user_id, data_generation, recorded_at, routine_id, routine_version);

CREATE TABLE weekly_schedule_versions (
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL CHECK (data_generation >= 0),
    version BIGINT NOT NULL CHECK (version > 0),
    recorded_at TIMESTAMPTZ NOT NULL,
    effective_from DATE NOT NULL CHECK (extract(isodow FROM effective_from) = 1),
    time_zone TEXT NOT NULL CHECK (time_zone !~ '^[[:space:]]*$'),
    assignments JSONB NOT NULL CHECK (jsonb_typeof(assignments) = 'array' AND jsonb_array_length(assignments) <= 7),
    PRIMARY KEY (user_id, data_generation, version),
    FOREIGN KEY (user_id, data_generation) REFERENCES users(id, data_generation)
);
CREATE INDEX weekly_schedule_effective ON weekly_schedule_versions(user_id, data_generation, effective_from, version DESC);

CREATE FUNCTION preserve_weekly_planning_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Immutable planning history' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER routine_plan_history_immutable BEFORE UPDATE ON routine_plan_history
    FOR EACH ROW EXECUTE FUNCTION preserve_weekly_planning_history();
CREATE TRIGGER weekly_schedule_versions_immutable BEFORE UPDATE ON weekly_schedule_versions
    FOR EACH ROW EXECUTE FUNCTION preserve_weekly_planning_history();
