-- M1. Preserve old rows/JSON; new versions use recorded_at after an explicit boundary.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
ALTER TABLE weekly_schedule_versions DROP CONSTRAINT weekly_schedule_versions_effective_from_check;
CREATE INDEX weekly_schedule_recorded ON weekly_schedule_versions(user_id,data_generation,recorded_at,version DESC);

CREATE TABLE workout_calendar_control (
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL,
    time_zone TEXT NOT NULL,
    baseline_version BIGINT NOT NULL,
    review_from_date DATE NOT NULL,
    reviewed_through DATE NOT NULL,
    PRIMARY KEY(user_id,data_generation),
    FOREIGN KEY(user_id,data_generation) REFERENCES users(id,data_generation),
    FOREIGN KEY(user_id,data_generation,baseline_version) REFERENCES weekly_schedule_versions(user_id,data_generation,version) DEFERRABLE INITIALLY DEFERRED,
    CHECK(reviewed_through >= review_from_date - 1)
);

-- Deployment requires management writes to be quiesced. Lock existing owners before promotion.
CREATE FUNCTION initialize_workout_calendar(owner_id UUID, generation BIGINT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE item RECORD; at_time TIMESTAMPTZ; local_day DATE;
BEGIN
    PERFORM 1 FROM users WHERE id=owner_id AND data_generation=generation FOR UPDATE;
    IF NOT FOUND OR EXISTS(SELECT 1 FROM workout_calendar_control WHERE user_id=owner_id AND data_generation=generation) THEN RETURN; END IF;
    SELECT * INTO item FROM weekly_schedule_versions WHERE user_id=owner_id AND data_generation=generation ORDER BY version DESC LIMIT 1;
    IF FOUND THEN
        at_time := clock_timestamp(); local_day := (at_time AT TIME ZONE item.time_zone)::date;
        INSERT INTO weekly_schedule_versions(user_id,data_generation,version,recorded_at,effective_from,time_zone,assignments)
        VALUES(item.user_id,item.data_generation,item.version+1,at_time,local_day,item.time_zone,item.assignments);
        INSERT INTO workout_calendar_control VALUES(item.user_id,item.data_generation,item.time_zone,item.version+1,local_day,local_day-1);
    END IF;
END $$;
SELECT initialize_workout_calendar(user_id,data_generation) FROM (SELECT DISTINCT user_id,data_generation FROM weekly_schedule_versions ORDER BY user_id) owners;

ALTER TABLE workout_sessions ADD COLUMN schedule_version_at_start BIGINT;
ALTER TABLE workout_sessions ADD CONSTRAINT workout_start_schedule_scope
    FOREIGN KEY(user_id,data_generation,schedule_version_at_start) REFERENCES weekly_schedule_versions(user_id,data_generation,version);
CREATE FUNCTION preserve_workout_schedule_origin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.schedule_version_at_start IS DISTINCT FROM OLD.schedule_version_at_start THEN
        RAISE EXCEPTION 'Immutable START schedule origin' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER workout_schedule_origin_immutable BEFORE UPDATE ON workout_sessions FOR EACH ROW EXECUTE FUNCTION preserve_workout_schedule_origin();

CREATE TABLE daily_training_assessments (
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL,
    workout_date DATE NOT NULL,
    first_session_id UUID,
    first_missed_at TIMESTAMPTZ,
    missed_schedule_version BIGINT,
    missed_routine_id UUID,
    missed_plan_version BIGINT,
    state TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK(state IN ('UNKNOWN','PENDING_ACTIVE','PERFORMED','MISSED','EMPTY')),
    revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0),
    needs_recheck BOOLEAN NOT NULL DEFAULT true,
    next_check_at TIMESTAMPTZ,
    reviewed_at TIMESTAMPTZ,
    PRIMARY KEY(user_id,data_generation,workout_date),
    FOREIGN KEY(user_id,data_generation) REFERENCES users(id,data_generation),
    FOREIGN KEY(user_id,data_generation,first_session_id) REFERENCES workout_sessions(user_id,data_generation,id),
    FOREIGN KEY(user_id,data_generation,missed_schedule_version) REFERENCES weekly_schedule_versions(user_id,data_generation,version),
    FOREIGN KEY(user_id,data_generation,missed_routine_id) REFERENCES routines(user_id,data_generation,id),
    CHECK(first_missed_at IS NULL OR missed_schedule_version IS NOT NULL AND missed_routine_id IS NOT NULL)
);
CREATE INDEX daily_training_recheck ON daily_training_assessments(user_id,data_generation,workout_date) WHERE needs_recheck;
CREATE FUNCTION preserve_daily_training_origin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.first_session_id IS NOT NULL OR OLD.first_missed_at IS NOT NULL) AND NEW.first_session_id IS DISTINCT FROM OLD.first_session_id
       OR OLD.first_missed_at IS NOT NULL AND ROW(NEW.first_missed_at,NEW.missed_schedule_version,NEW.missed_routine_id,NEW.missed_plan_version)
          IS DISTINCT FROM ROW(OLD.first_missed_at,OLD.missed_schedule_version,OLD.missed_routine_id,OLD.missed_plan_version) THEN
        RAISE EXCEPTION 'Immutable first training evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER daily_training_origin_immutable BEFORE UPDATE ON daily_training_assessments FOR EACH ROW EXECUTE FUNCTION preserve_daily_training_origin();

-- Source-specific dirty markers live in the same transaction as facts, including lazy expiry.
CREATE FUNCTION mark_training_date_dirty() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner_id UUID; generation BIGINT; day DATE;
BEGIN
    IF TG_TABLE_NAME='workout_sessions' THEN
        IF ROW(NEW.status,NEW.deleted_at) IS NOT DISTINCT FROM ROW(OLD.status,OLD.deleted_at) THEN RETURN NEW; END IF;
        owner_id:=NEW.user_id; generation:=NEW.data_generation; day:=NEW.workout_date;
    ELSE
        IF TG_OP='UPDATE' THEN
            IF TG_TABLE_NAME='performed_sets' THEN
                IF ROW(NEW.weight,NEW.reps,NEW.deleted_at,NEW.bodyweight_load) IS NOT DISTINCT FROM ROW(OLD.weight,OLD.reps,OLD.deleted_at,OLD.bodyweight_load) THEN RETURN NEW; END IF;
            ELSIF ROW(NEW.status,NEW.active_ms,NEW.distance,NEW.measurement,NEW.deleted_at,NEW.timer_ms,NEW.corrected,NEW.average_bpm) IS NOT DISTINCT FROM ROW(OLD.status,OLD.active_ms,OLD.distance,OLD.measurement,OLD.deleted_at,OLD.timer_ms,OLD.corrected,OLD.average_bpm) THEN RETURN NEW;
            END IF;
        END IF;
        SELECT user_id,data_generation,workout_date INTO owner_id,generation,day FROM workout_sessions WHERE id=NEW.session_id;
    END IF;
    INSERT INTO daily_training_assessments(user_id,data_generation,workout_date,revision) VALUES(owner_id,generation,day,1)
    ON CONFLICT(user_id,data_generation,workout_date) DO UPDATE SET needs_recheck=true,next_check_at=NULL,revision=daily_training_assessments.revision+1;
    RETURN NEW;
END $$;
CREATE TRIGGER workout_training_dirty AFTER UPDATE ON workout_sessions FOR EACH ROW EXECUTE FUNCTION mark_training_date_dirty();
CREATE TRIGGER sets_training_dirty AFTER INSERT OR UPDATE ON performed_sets FOR EACH ROW EXECUTE FUNCTION mark_training_date_dirty();
CREATE TRIGGER cardio_training_dirty AFTER INSERT OR UPDATE ON cardio_results FOR EACH ROW EXECUTE FUNCTION mark_training_date_dirty();
