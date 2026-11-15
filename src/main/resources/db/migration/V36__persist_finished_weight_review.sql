ALTER TABLE workout_sessions ADD COLUMN completion_weight_review_snapshot JSONB;
ALTER TABLE workout_sessions ADD CHECK (completion_weight_review_snapshot IS NULL OR (status='FINISHED' AND jsonb_typeof(completion_weight_review_snapshot)='object'));
ALTER TABLE session_weight_approval_requests ADD COLUMN final_review BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE session_weight_approval_requests ADD CHECK (NOT final_review OR (created_after_closure AND status='APPLIED'));
CREATE UNIQUE INDEX session_one_final_review_approval ON session_weight_approval_requests(session_id,session_exercise_id) WHERE final_review;
CREATE FUNCTION preserve_finished_weight_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status<>'ACTIVE' AND NEW.completion_weight_review_snapshot IS DISTINCT FROM OLD.completion_weight_review_snapshot THEN
  RAISE EXCEPTION 'Finished weight review is immutable';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER workout_review_immutable BEFORE UPDATE ON workout_sessions FOR EACH ROW EXECUTE FUNCTION preserve_finished_weight_review();
