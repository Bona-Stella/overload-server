-- One contract transition. Never reinterpret old personal UUIDs or discard existing workout data.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

LOCK TABLE routines, workout_sessions, routine_weight_placements, session_exercises, performed_sets IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM routines) OR EXISTS (SELECT 1 FROM workout_sessions) THEN
        RAISE EXCEPTION 'V17 requires empty routine/session data; no automatic deletion or migration is permitted';
    END IF;
END;
$$;

-- Locate only the two personal-definition FKs by their catalog identity, not truncated generated names.
DO $$
DECLARE c RECORD; replaced INTEGER := 0;
BEGIN
    FOR c IN SELECT conrelid::regclass AS relation, conname FROM pg_constraint
        WHERE contype='f' AND confrelid='exercise_definitions'::regclass
          AND conrelid IN ('routine_weight_placements'::regclass,'session_exercises'::regclass)
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', c.relation, c.conname);
        replaced := replaced + 1;
    END LOOP;
    IF replaced <> 2 THEN RAISE EXCEPTION 'Expected exactly two personal exercise FKs'; END IF;
END;
$$;

ALTER TABLE routine_weight_placements RENAME COLUMN exercise_definition_id TO standard_exercise_id;
ALTER TABLE routine_weight_placements ADD CONSTRAINT routine_weight_standard_exercise_fk
    FOREIGN KEY (standard_exercise_id) REFERENCES standard_exercise(id);
ALTER TABLE routine_weight_placements ADD COLUMN manufacturer_id UUID REFERENCES manufacturer(id);
ALTER TABLE routine_weight_placements ADD COLUMN load_type TEXT
    CHECK (load_type IS NULL OR load_type IN ('PLATE','STACK','DUMBBELL','BODYWEIGHT'));

-- The existing kind/null CHECK follows the column rename and is retained.
ALTER TABLE session_exercises RENAME COLUMN exercise_definition_id TO standard_exercise_id;
ALTER TABLE session_exercises ADD CONSTRAINT session_exercise_standard_exercise_fk
    FOREIGN KEY (standard_exercise_id) REFERENCES standard_exercise(id);
ALTER TABLE performed_sets ADD COLUMN manufacturer_id UUID REFERENCES manufacturer(id);
ALTER TABLE performed_sets ADD COLUMN load_type TEXT
    CHECK (load_type IS NULL OR load_type IN ('PLATE','STACK','DUMBBELL','BODYWEIGHT'));

-- Keep every original predicate (including closure state requirements), replacing only its schema version.
DO $$
DECLARE c RECORD; replaced INTEGER := 0;
BEGIN
    FOR c IN SELECT conname, pg_get_constraintdef(oid) AS definition FROM pg_constraint
        WHERE conrelid='workout_sessions'::regclass AND contype='c'
          AND pg_get_constraintdef(oid) LIKE '%schemaVersion%'
    LOOP
        IF position('''1''::jsonb' IN c.definition)=0 THEN RAISE EXCEPTION 'Unexpected plan schema CHECK'; END IF;
        EXECUTE format('ALTER TABLE workout_sessions DROP CONSTRAINT %I', c.conname);
        EXECUTE format('ALTER TABLE workout_sessions ADD CONSTRAINT %I %s', c.conname,
            replace(c.definition, '''1''::jsonb', '''2''::jsonb'));
        replaced := replaced + 1;
    END LOOP;
    IF replaced <> 3 THEN RAISE EXCEPTION 'Expected initial/applied/completion schema CHECKs'; END IF;
END;
$$;

-- Completed equipment is part of the original performed fact, also during later numeric corrections.
CREATE FUNCTION preserve_performed_set_equipment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.manufacturer_id, NEW.load_type) IS DISTINCT FROM ROW(OLD.manufacturer_id, OLD.load_type) THEN
        RAISE EXCEPTION 'Immutable performed equipment' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER performed_sets_immutable_equipment BEFORE UPDATE ON performed_sets
    FOR EACH ROW EXECUTE FUNCTION preserve_performed_set_equipment();
