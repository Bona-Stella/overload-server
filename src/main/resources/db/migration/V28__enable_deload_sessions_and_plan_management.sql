SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE workout_sessions ALTER COLUMN source_routine_id DROP NOT NULL;
ALTER TABLE workout_sessions ADD CONSTRAINT workout_source_kind CHECK
 ((deload_period_id IS NULL AND source_routine_id IS NOT NULL) OR (deload_period_id IS NOT NULL AND source_routine_id IS NULL AND schedule_version_at_start IS NULL));
ALTER TABLE workout_sessions ADD COLUMN first_deload_activity_at TIMESTAMPTZ CHECK(first_deload_activity_at IS NULL OR isfinite(first_deload_activity_at)),
 ADD COLUMN abandoned_at TIMESTAMPTZ CHECK(abandoned_at IS NULL OR isfinite(abandoned_at));
ALTER TABLE workout_sessions ADD CONSTRAINT workout_abandoned_empty CHECK
 (abandoned_at IS NULL OR (deload_period_id IS NOT NULL AND first_deload_activity_at IS NULL AND deleted_at IS NOT NULL AND status<>'ACTIVE'));
ALTER TABLE workout_sessions ADD CHECK(first_deload_activity_at IS NULL OR deload_period_id IS NOT NULL);
CREATE UNIQUE INDEX workout_one_live_deload_plan ON workout_sessions(deload_plan_id) WHERE deload_plan_id IS NOT NULL AND abandoned_at IS NULL;
CREATE INDEX workout_deload_activity ON workout_sessions(user_id,data_generation,deload_period_id) WHERE first_deload_activity_at IS NOT NULL;
CREATE FUNCTION preserve_deload_activity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.first_deload_activity_at IS NOT NULL AND NEW.first_deload_activity_at IS DISTINCT FROM OLD.first_deload_activity_at
   OR OLD.abandoned_at IS NOT NULL AND NEW.abandoned_at IS DISTINCT FROM OLD.abandoned_at THEN
   RAISE EXCEPTION 'Immutable deload activity or abandonment' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER workout_deload_activity_immutable BEFORE UPDATE ON workout_sessions FOR EACH ROW EXECUTE FUNCTION preserve_deload_activity();
CREATE FUNCTION remember_deload_activity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE session_key UUID; activity_at TIMESTAMPTZ;
BEGIN
 IF TG_TABLE_NAME='performed_sets' THEN
   session_key:=NEW.session_id; activity_at:=NEW.completed_at;
 ELSIF NEW.status='COMPLETED' AND NEW.active_ms>0 THEN
   session_key:=NEW.session_id; activity_at:=NEW.occurred_at;
 ELSE RETURN NEW;
 END IF;
 UPDATE workout_sessions SET first_deload_activity_at=activity_at
   WHERE id=session_key AND deload_period_id IS NOT NULL AND first_deload_activity_at IS NULL;
 RETURN NEW;
END $$;
CREATE TRIGGER performed_deload_activity AFTER INSERT OR UPDATE ON performed_sets FOR EACH ROW EXECUTE FUNCTION remember_deload_activity();
CREATE TRIGGER cardio_deload_activity AFTER INSERT OR UPDATE ON cardio_results FOR EACH ROW EXECUTE FUNCTION remember_deload_activity();

ALTER TABLE deload_periods ADD COLUMN current_method TEXT, ADD COLUMN current_split TEXT,
 ADD COLUMN last_change_id UUID, ADD COLUMN last_change_hash TEXT CHECK(last_change_hash IS NULL OR last_change_hash ~ '^[0-9a-f]{64}$');
UPDATE deload_periods SET current_method=method,current_split=split;
ALTER TABLE deload_periods ALTER COLUMN current_method SET NOT NULL, ALTER COLUMN current_split SET NOT NULL;
ALTER TABLE deload_periods ADD CHECK((last_change_id IS NULL)=(last_change_hash IS NULL)), ADD CHECK
 (current_method='REST' AND current_split='REST' OR current_method='KEEP' AND current_split='ORIGINAL'
 OR current_method='THREE' AND current_split IN('PPL','EQUAL') OR current_method='TWO' AND current_split IN('FRONT_BACK','EQUAL'));
-- Defaults are not authoritative for a new period; the insert trigger derives them from its immutable initial method.
CREATE FUNCTION initialize_deload_method() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN NEW.current_method:=NEW.method; NEW.current_split:=NEW.split; RETURN NEW; END $$;
CREATE TRIGGER deload_method_initial BEFORE INSERT ON deload_periods FOR EACH ROW EXECUTE FUNCTION initialize_deload_method();

ALTER TABLE deload_temporary_plans ADD COLUMN current_plan JSONB, ADD COLUMN deleted_at TIMESTAMPTZ CHECK(deleted_at IS NULL OR isfinite(deleted_at));
CREATE FUNCTION deload_initial_content(plan JSONB) RETURNS JSONB LANGUAGE sql IMMUTABLE AS $$
 SELECT jsonb_build_object('label',plan->'label',
 'weights',coalesce((SELECT jsonb_agg((w->'original')||jsonb_build_object('initialWeightKg',w->'weightKg','approvedWeightKg',NULL,'setCount',w->'setCount','minReps',w->'minReps','maxReps',w->'maxReps') ORDER BY pos) FROM jsonb_array_elements(plan->'weights') WITH ORDINALITY AS weights(w,pos)),'[]'::jsonb),
 'cardio',coalesce((SELECT jsonb_agg((c->'original')||jsonb_build_object('plan',c->'target') ORDER BY pos) FROM jsonb_array_elements(plan->'cardio') WITH ORDINALITY AS cardio(c,pos)),'[]'::jsonb))
$$;
UPDATE deload_temporary_plans SET current_plan=deload_initial_content(initial_plan);
ALTER TABLE deload_temporary_plans ALTER COLUMN current_plan SET NOT NULL;
ALTER TABLE deload_temporary_plans ADD CHECK(jsonb_typeof(current_plan)='object');
ALTER TABLE deload_temporary_plans DROP CONSTRAINT deload_temporary_plans_period_id_planned_date_key,
 DROP CONSTRAINT deload_temporary_plans_period_id_position_key;
-- A deferred live-date uniqueness constraint permits a real atomic swap while deleted plans keep their dates.
ALTER TABLE deload_temporary_plans ADD COLUMN live_date DATE GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN planned_date END) STORED;
ALTER TABLE deload_temporary_plans ADD CONSTRAINT deload_live_date UNIQUE(period_id,live_date) DEFERRABLE INITIALLY DEFERRED;
CREATE UNIQUE INDEX deload_live_position ON deload_temporary_plans(period_id,position) WHERE deleted_at IS NULL;
CREATE OR REPLACE FUNCTION guard_deload_initial_plan() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE first_date DATE;
BEGIN
 SELECT start_date INTO first_date FROM deload_periods WHERE id=NEW.period_id AND user_id=NEW.user_id AND data_generation=NEW.data_generation;
 IF NOT FOUND THEN RAISE EXCEPTION 'Deload plan owner missing' USING ERRCODE='23503'; END IF;
 IF NEW.planned_date<first_date OR NEW.planned_date>first_date+(CASE WHEN TG_OP='INSERT' THEN 6 ELSE 9 END) THEN
   RAISE EXCEPTION 'Invalid deload date' USING ERRCODE='23514';
 END IF;
 IF TG_OP='INSERT' THEN NEW.current_plan:=deload_initial_content(NEW.initial_plan);
 ELSIF ROW(NEW.id,NEW.user_id,NEW.data_generation,NEW.period_id,NEW.initial_plan) IS DISTINCT FROM ROW(OLD.id,OLD.user_id,OLD.data_generation,OLD.period_id,OLD.initial_plan) THEN
   RAISE EXCEPTION 'Immutable initial deload plan' USING ERRCODE='23514';
 END IF;
 IF TG_OP='UPDATE' AND ROW(NEW.current_plan,NEW.planned_date,NEW.deleted_at) IS DISTINCT FROM ROW(OLD.current_plan,OLD.planned_date,OLD.deleted_at)
  AND EXISTS(SELECT 1 FROM workout_sessions WHERE deload_plan_id=OLD.id AND abandoned_at IS NULL) THEN
   RAISE EXCEPTION 'Started deload plan is frozen' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION reject_deload_approval() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM workout_sessions WHERE id=NEW.session_id AND deload_period_id IS NOT NULL) THEN
   RAISE EXCEPTION 'Deload cannot approve normal weight' USING ERRCODE='23514';
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER deload_approval_guard BEFORE INSERT OR UPDATE ON session_weight_approval_requests FOR EACH ROW EXECUTE FUNCTION reject_deload_approval();
CREATE TRIGGER deload_growth_guard BEFORE INSERT OR UPDATE ON progress_change_events FOR EACH ROW EXECUTE FUNCTION reject_deload_approval();

-- Preserve the G3 invalidation algorithm for normal facts; deload facts cannot create ordinary daily evidence.
CREATE OR REPLACE FUNCTION mark_training_date_dirty() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner_id UUID; generation BIGINT; day DATE;
BEGIN
 IF TG_TABLE_NAME='workout_sessions' THEN
   IF NEW.deload_period_id IS NOT NULL OR ROW(NEW.status,NEW.deleted_at) IS NOT DISTINCT FROM ROW(OLD.status,OLD.deleted_at) THEN RETURN NEW; END IF;
   owner_id:=NEW.user_id; generation:=NEW.data_generation; day:=NEW.workout_date;
 ELSE
   IF TG_OP='UPDATE' THEN
     IF TG_TABLE_NAME='performed_sets' THEN
       IF ROW(NEW.weight,NEW.reps,NEW.deleted_at,NEW.bodyweight_load) IS NOT DISTINCT FROM ROW(OLD.weight,OLD.reps,OLD.deleted_at,OLD.bodyweight_load) THEN RETURN NEW; END IF;
     ELSIF ROW(NEW.status,NEW.active_ms,NEW.distance,NEW.measurement,NEW.deleted_at,NEW.timer_ms,NEW.corrected,NEW.average_bpm) IS NOT DISTINCT FROM ROW(OLD.status,OLD.active_ms,OLD.distance,OLD.measurement,OLD.deleted_at,OLD.timer_ms,OLD.corrected,OLD.average_bpm) THEN RETURN NEW;
     END IF;
   END IF;
   SELECT user_id,data_generation,workout_date INTO owner_id,generation,day FROM workout_sessions WHERE id=NEW.session_id AND deload_period_id IS NULL;
   IF NOT FOUND THEN RETURN NEW; END IF;
 END IF;
 INSERT INTO daily_training_assessments(user_id,data_generation,workout_date,revision) VALUES(owner_id,generation,day,1)
 ON CONFLICT(user_id,data_generation,workout_date) DO UPDATE SET needs_recheck=true,next_check_at=NULL,revision=daily_training_assessments.revision+1;
 RETURN NEW;
END $$;
