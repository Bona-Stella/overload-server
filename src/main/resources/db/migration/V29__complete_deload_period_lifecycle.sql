SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE deload_periods
    ADD COLUMN end_reason TEXT,
    ADD COLUMN end_request_id UUID,
    ADD CONSTRAINT deload_period_end_metadata CHECK (
        end_reason IS NULL AND end_request_id IS NULL
        OR end_reason IS NOT NULL AND (
            status='COMPLETED' AND end_reason='MANUAL_COMPLETION' AND end_request_id IS NOT NULL
            OR status='COMPLETED' AND end_reason='AUTOMATIC_COMPLETION' AND closed_date=maximum_end_date
            OR status='CANCELLED' AND end_reason='USER_CANCELLED' AND end_request_id IS NOT NULL
        )
    ),
    ADD CONSTRAINT deload_period_end_request UNIQUE (user_id,data_generation,end_request_id);

CREATE FUNCTION guard_deload_period_end() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status<>'ACTIVE' THEN
        RAISE EXCEPTION 'Immutable ended deload period' USING ERRCODE='23514';
    END IF;
    IF NEW.status<>'ACTIVE' AND (NEW.end_reason IS NULL OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at) THEN
        RAISE EXCEPTION 'Invalid deload termination' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER deload_period_end_guard BEFORE UPDATE ON deload_periods
    FOR EACH ROW EXECUTE FUNCTION guard_deload_period_end();

-- After a delayed automatic close, full days when normal writes were still locked cannot be assessed as missed.
CREATE FUNCTION deload_stopped_through(p deload_periods) RETURNS DATE LANGUAGE sql STABLE AS $$
    SELECT CASE WHEN (p).end_reason='AUTOMATIC_COMPLETION'
        THEN greatest((p).closed_date,((p).updated_at AT TIME ZONE (p).time_zone)::date-1)
        ELSE (p).closed_date END
$$;

ALTER TABLE deload_analysis_versions
    DROP CONSTRAINT deload_analysis_versions_reason_check,
    ADD COLUMN completed_period_id UUID,
    ADD CONSTRAINT deload_analysis_versions_reason_check
        CHECK(reason IN ('INITIAL','ADOPTED_INITIAL','TIME_ZONE_SET','DISABLED','ENABLED','DELOAD_COMPLETED')),
    ADD CONSTRAINT deload_analysis_completion_origin CHECK ((reason='DELOAD_COMPLETED')=(completed_period_id IS NOT NULL)),
    ADD CONSTRAINT deload_analysis_one_epoch_per_period UNIQUE (completed_period_id),
    ADD CONSTRAINT deload_analysis_completed_period_fk
        FOREIGN KEY(user_id,data_generation,completed_period_id) REFERENCES deload_periods(user_id,data_generation,id);

CREATE VIEW deload_analysis_contexts AS
    SELECT v.*,p.closed_date+1 AS resume_date
    FROM deload_analysis_versions v
    JOIN deload_analysis_versions root ON root.user_id=v.user_id AND root.data_generation=v.data_generation AND root.version=v.epoch
    LEFT JOIN deload_periods p ON p.id=root.completed_period_id;

CREATE OR REPLACE FUNCTION guard_deload_analysis_version() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous deload_analysis_versions%ROWTYPE; created TIMESTAMPTZ;
BEGIN
    IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'Immutable analysis version' USING ERRCODE='23514'; END IF;
    SELECT created_at INTO created FROM users WHERE id=NEW.user_id AND data_generation=NEW.data_generation FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Analysis owner missing' USING ERRCODE='23503'; END IF;
    SELECT * INTO previous FROM deload_analysis_versions WHERE user_id=NEW.user_id AND data_generation=NEW.data_generation ORDER BY version DESC LIMIT 1;
    IF NOT FOUND THEN
        IF NEW.version<>0 OR NEW.epoch<>0 OR NOT NEW.enabled OR NEW.reason NOT IN ('INITIAL','ADOPTED_INITIAL')
           OR NEW.recorded_at<>NEW.epoch_started_at OR NEW.epoch_started_at<created
           OR NEW.reason='INITIAL' AND (NEW.epoch_started_at<>created OR NEW.time_zone IS NOT NULL) THEN
            RAISE EXCEPTION 'Invalid initial analysis' USING ERRCODE='23514';
        END IF;
    ELSE
        IF previous.version=9223372036854775807 OR NEW.version<>previous.version+1 OR NEW.recorded_at<previous.recorded_at
           OR previous.time_zone IS NOT NULL AND NEW.time_zone IS DISTINCT FROM previous.time_zone THEN
            RAISE EXCEPTION 'Invalid analysis version' USING ERRCODE='23514';
        END IF;
        IF NEW.reason='DELOAD_COMPLETED' THEN
            IF NOT previous.enabled OR NOT NEW.enabled OR NEW.epoch<>NEW.version OR NEW.time_zone IS NULL
               OR NOT EXISTS (SELECT 1 FROM deload_periods p WHERE p.id=NEW.completed_period_id
                   AND p.user_id=NEW.user_id AND p.data_generation=NEW.data_generation
                   AND p.status='COMPLETED' AND p.end_reason IS NOT NULL AND p.time_zone=NEW.time_zone
                   AND p.closed_at=NEW.epoch_started_at AND p.updated_at<=NEW.recorded_at) THEN
                RAISE EXCEPTION 'Invalid deload completion epoch' USING ERRCODE='23514';
            END IF;
        ELSIF NEW.reason='ENABLED' THEN
            IF previous.enabled OR NOT NEW.enabled OR NEW.epoch<>NEW.version OR NEW.epoch_started_at<>NEW.recorded_at OR NEW.time_zone IS NULL THEN
                RAISE EXCEPTION 'Invalid reactivation' USING ERRCODE='23514';
            END IF;
        ELSE
            IF NEW.epoch<>previous.epoch OR NEW.epoch_started_at<>previous.epoch_started_at
               OR NOT (NEW.reason='DISABLED' AND previous.enabled AND NOT NEW.enabled AND NEW.time_zone IS NOT NULL
                   OR NEW.reason='TIME_ZONE_SET' AND NEW.enabled=previous.enabled AND previous.time_zone IS NULL AND NEW.time_zone IS NOT NULL) THEN
                RAISE EXCEPTION 'Invalid analysis transition' USING ERRCODE='23514';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION guard_deload_weekly_epoch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM deload_analysis_versions WHERE user_id=NEW.user_id AND data_generation=NEW.data_generation
                  AND version=NEW.epoch AND epoch=version AND enabled AND reason IN ('INITIAL','ADOPTED_INITIAL','ENABLED','DELOAD_COMPLETED')) THEN
        RAISE EXCEPTION 'Analysis epoch missing' USING ERRCODE='23503';
    END IF;
    RETURN NEW;
END $$;
