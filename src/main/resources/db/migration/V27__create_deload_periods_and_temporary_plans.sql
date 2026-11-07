SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
CREATE TABLE deload_periods (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL,
    request_id UUID NOT NULL,
    request_hash TEXT NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
    source_hash TEXT NOT NULL CHECK(source_hash ~ '^[0-9a-f]{64}$'),
    started_at TIMESTAMPTZ NOT NULL CHECK(isfinite(started_at)),
    start_date DATE NOT NULL CHECK(isfinite(start_date)),
    time_zone TEXT NOT NULL CHECK(deload_time_zone_valid(time_zone)),
    recommended_end_date DATE GENERATED ALWAYS AS (start_date+6) STORED,
    maximum_end_date DATE GENERATED ALWAYS AS (start_date+9) STORED,
    status TEXT NOT NULL CHECK(status IN('ACTIVE','COMPLETED','CANCELLED')),
    version BIGINT NOT NULL CHECK(version>=0),
    method TEXT NOT NULL CHECK(method IN('KEEP','THREE','TWO','REST')),
    split TEXT NOT NULL CHECK(split IN('ORIGINAL','PPL','FRONT_BACK','EQUAL','REST')),
    source_snapshot JSONB NOT NULL CHECK(jsonb_typeof(source_snapshot)='object'),
    closed_at TIMESTAMPTZ,
    closed_date DATE,
    updated_at TIMESTAMPTZ NOT NULL CHECK(isfinite(updated_at)),
    UNIQUE(user_id,data_generation,id),
    UNIQUE(user_id,data_generation,request_id),
    FOREIGN KEY(user_id,data_generation) REFERENCES users(id,data_generation),
    CHECK(start_date=(started_at AT TIME ZONE time_zone)::date AND started_at<=updated_at),
    CHECK(method='REST' AND split='REST' OR method='KEEP' AND split='ORIGINAL'
       OR method='THREE' AND split IN('PPL','EQUAL') OR method='TWO' AND split IN('FRONT_BACK','EQUAL')),
    CHECK(status='ACTIVE' AND closed_at IS NULL AND closed_date IS NULL
       OR status<>'ACTIVE' AND closed_at IS NOT NULL AND isfinite(closed_at) AND closed_at>=started_at AND closed_at<=updated_at
          AND closed_date IS NOT NULL AND isfinite(closed_date) AND closed_date BETWEEN start_date AND maximum_end_date
          AND closed_date=(closed_at AT TIME ZONE time_zone)::date)
);
CREATE UNIQUE INDEX deload_one_active_period ON deload_periods(user_id,data_generation) WHERE status='ACTIVE';
CREATE TABLE deload_temporary_plans (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL,
    period_id UUID NOT NULL,
    position INTEGER NOT NULL CHECK(position BETWEEN 0 AND 6),
    planned_date DATE NOT NULL CHECK(isfinite(planned_date)),
    version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),
    initial_plan JSONB NOT NULL CHECK(jsonb_typeof(initial_plan)='object'),
    UNIQUE(user_id,data_generation,period_id,id),
    UNIQUE(period_id,position),
    UNIQUE(period_id,planned_date),
    FOREIGN KEY(user_id,data_generation,period_id) REFERENCES deload_periods(user_id,data_generation,id)
);
CREATE FUNCTION guard_deload_period_origin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.user_id,NEW.data_generation,NEW.request_id,NEW.request_hash,NEW.source_hash,NEW.started_at,NEW.start_date,NEW.time_zone,NEW.method,NEW.split,NEW.source_snapshot)
       IS DISTINCT FROM ROW(OLD.id,OLD.user_id,OLD.data_generation,OLD.request_id,OLD.request_hash,OLD.source_hash,OLD.started_at,OLD.start_date,OLD.time_zone,OLD.method,OLD.split,OLD.source_snapshot) THEN
        RAISE EXCEPTION 'Immutable deload origin' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER deload_period_origin_immutable BEFORE UPDATE ON deload_periods FOR EACH ROW EXECUTE FUNCTION guard_deload_period_origin();
CREATE FUNCTION guard_deload_initial_plan() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE first_date DATE;
BEGIN
    SELECT start_date INTO first_date FROM deload_periods WHERE id=NEW.period_id AND user_id=NEW.user_id AND data_generation=NEW.data_generation;
    IF NOT FOUND THEN RAISE EXCEPTION 'Deload plan owner missing' USING ERRCODE='23503'; END IF;
    IF NEW.planned_date<first_date OR NEW.planned_date>first_date+6 THEN RAISE EXCEPTION 'Invalid initial deload date' USING ERRCODE='23514'; END IF;
    IF TG_OP='UPDATE' AND ROW(NEW.id,NEW.user_id,NEW.data_generation,NEW.period_id,NEW.initial_plan)
       IS DISTINCT FROM ROW(OLD.id,OLD.user_id,OLD.data_generation,OLD.period_id,OLD.initial_plan) THEN
        RAISE EXCEPTION 'Immutable initial deload plan' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER deload_plan_origin_guard BEFORE INSERT OR UPDATE ON deload_temporary_plans FOR EACH ROW EXECUTE FUNCTION guard_deload_initial_plan();
-- Membership only; G5-1 exposes no deload workout START/FINISH path.
ALTER TABLE workout_sessions ADD COLUMN deload_period_id UUID, ADD COLUMN deload_plan_id UUID;
ALTER TABLE workout_sessions ADD CONSTRAINT workout_deload_membership_pair CHECK((deload_period_id IS NULL)=(deload_plan_id IS NULL));
ALTER TABLE workout_sessions ADD CONSTRAINT workout_deload_plan_scope
    FOREIGN KEY(user_id,data_generation,deload_period_id,deload_plan_id) REFERENCES deload_temporary_plans(user_id,data_generation,period_id,id);
CREATE FUNCTION preserve_deload_session_membership() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.deload_period_id,NEW.deload_plan_id) IS DISTINCT FROM ROW(OLD.deload_period_id,OLD.deload_plan_id) THEN
        RAISE EXCEPTION 'Immutable workout deload membership' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER workout_deload_membership_immutable BEFORE UPDATE ON workout_sessions FOR EACH ROW EXECUTE FUNCTION preserve_deload_session_membership();
