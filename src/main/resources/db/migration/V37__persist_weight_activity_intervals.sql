ALTER TABLE workout_sessions ADD COLUMN weight_timing_snapshot JSONB;
ALTER TABLE workout_sessions ADD CONSTRAINT workout_weight_timing_shape CHECK (
 weight_timing_snapshot IS NULL OR (jsonb_typeof(weight_timing_snapshot)='object'
 AND weight_timing_snapshot->>'schemaVersion'='1'
 AND jsonb_typeof(weight_timing_snapshot->'complete')='boolean'
 AND jsonb_typeof(weight_timing_snapshot->'intervals')='array'));
CREATE FUNCTION preserve_closed_weight_timing() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status<>'ACTIVE' AND NEW.weight_timing_snapshot IS DISTINCT FROM OLD.weight_timing_snapshot THEN
  RAISE EXCEPTION 'Closed workout timing is immutable';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER workout_weight_timing_immutable BEFORE UPDATE ON workout_sessions FOR EACH ROW EXECUTE FUNCTION preserve_closed_weight_timing();
