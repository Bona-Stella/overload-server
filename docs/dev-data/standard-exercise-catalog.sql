-- Development examples only; not an approved production exercise/muscle taxonomy.
-- Applies only to the verified personal overload_dev cluster. No schema edits/deletes.
-- Fixed UUIDs reserve these examples; a conflicting UUID/name aborts the transaction.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
DO $$
BEGIN
  IF current_database() <> 'overload_dev' OR current_user <> 'overload_dev'
     OR (SELECT system_identifier::text FROM pg_control_system()) <> '7701299869222748199'
     OR (SELECT max(version::integer) FROM flyway_schema_history WHERE success) <> 17 THEN
    RAISE EXCEPTION 'Expected the verified local development V17 database';
  END IF;
END $$;

INSERT INTO standard_exercise(id,name) VALUES
 ('d1700000-0000-4000-8000-000000000001','바벨 벤치프레스'),
 ('d1700000-0000-4000-8000-000000000002','덤벨 벤치프레스'),
 ('d1700000-0000-4000-8000-000000000003','불가리안 스플릿 스쿼트'),
 ('d1700000-0000-4000-8000-000000000004','하이로우'),
 ('d1700000-0000-4000-8000-000000000005','랫풀다운')
ON CONFLICT (id) DO NOTHING;

-- NFKC -> Unicode whitespace collapse/trim -> Locale.ROOT lowercase.
-- These deliberately small examples normalize identically in Java and the frontend.
INSERT INTO exercise_alias(standard_exercise_id,alias,normalized_alias) VALUES
 ('d1700000-0000-4000-8000-000000000001','바벨벤치프레스','바벨벤치프레스'),
 ('d1700000-0000-4000-8000-000000000001','Barbell Bench Press','barbell bench press'),
 ('d1700000-0000-4000-8000-000000000002','덤벨벤치','덤벨벤치'),
 ('d1700000-0000-4000-8000-000000000002','Dumbbell Bench Press','dumbbell bench press'),
 ('d1700000-0000-4000-8000-000000000003','불스스','불스스'),
 ('d1700000-0000-4000-8000-000000000003','Bulgarian Split Squat','bulgarian split squat'),
 ('d1700000-0000-4000-8000-000000000004','하이 로우','하이 로우'),
 ('d1700000-0000-4000-8000-000000000004','High Row','high row'),
 ('d1700000-0000-4000-8000-000000000005','랫 풀다운','랫 풀다운'),
 ('d1700000-0000-4000-8000-000000000005','Lat Pulldown','lat pulldown')
ON CONFLICT (standard_exercise_id,normalized_alias) DO NOTHING;

-- Coarse development labels; revise when the production catalog is reviewed.
INSERT INTO muscle(id,group_code,name) VALUES
 ('d1720000-0000-4000-8000-000000000001','CHEST','가슴 (개발 예시)'),
 ('d1720000-0000-4000-8000-000000000002','ARMS','삼두 (개발 예시)'),
 ('d1720000-0000-4000-8000-000000000003','LEGS','대퇴사두 (개발 예시)'),
 ('d1720000-0000-4000-8000-000000000004','LEGS','둔근 (개발 예시)'),
 ('d1720000-0000-4000-8000-000000000005','BACK','광배 (개발 예시)'),
 ('d1720000-0000-4000-8000-000000000006','BACK','상부 등 (개발 예시)')
ON CONFLICT (id) DO NOTHING;

INSERT INTO exercise_muscle(standard_exercise_id,muscle_id,role) VALUES
 ('d1700000-0000-4000-8000-000000000001','d1720000-0000-4000-8000-000000000001','PRIMARY'),
 ('d1700000-0000-4000-8000-000000000001','d1720000-0000-4000-8000-000000000002','SECONDARY'),
 ('d1700000-0000-4000-8000-000000000002','d1720000-0000-4000-8000-000000000001','PRIMARY'),
 ('d1700000-0000-4000-8000-000000000002','d1720000-0000-4000-8000-000000000002','SECONDARY'),
 ('d1700000-0000-4000-8000-000000000003','d1720000-0000-4000-8000-000000000003','PRIMARY'),
 ('d1700000-0000-4000-8000-000000000003','d1720000-0000-4000-8000-000000000004','PRIMARY'),
 ('d1700000-0000-4000-8000-000000000004','d1720000-0000-4000-8000-000000000005','PRIMARY'),
 ('d1700000-0000-4000-8000-000000000004','d1720000-0000-4000-8000-000000000006','PRIMARY'),
 ('d1700000-0000-4000-8000-000000000005','d1720000-0000-4000-8000-000000000005','PRIMARY'),
 ('d1700000-0000-4000-8000-000000000005','d1720000-0000-4000-8000-000000000006','SECONDARY')
ON CONFLICT (standard_exercise_id,muscle_id) DO NOTHING;

INSERT INTO manufacturer(id,name) VALUES
 ('d1740000-0000-4000-8000-000000000001','파나타'),
 ('d1740000-0000-4000-8000-000000000002','해머스트렝스')
ON CONFLICT (id) DO NOTHING;

DO $$
BEGIN
  IF (SELECT count(*) FROM standard_exercise) <> 5
     OR (SELECT count(*) FROM exercise_alias) <> 10
     OR (SELECT count(*) FROM muscle) <> 6
     OR (SELECT count(*) FROM exercise_muscle) <> 10
     OR (SELECT count(*) FROM manufacturer) <> 2 THEN
    RAISE EXCEPTION 'Unexpected catalog contents; no data replacement permitted';
  END IF;
  IF EXISTS (
    SELECT 1 FROM (VALUES
      ('d1700000-0000-4000-8000-000000000001'::uuid,'바벨 벤치프레스'),
      ('d1700000-0000-4000-8000-000000000002'::uuid,'덤벨 벤치프레스'),
      ('d1700000-0000-4000-8000-000000000003'::uuid,'불가리안 스플릿 스쿼트'),
      ('d1700000-0000-4000-8000-000000000004'::uuid,'하이로우'),
      ('d1700000-0000-4000-8000-000000000005'::uuid,'랫풀다운')
    ) AS expected(id,name) LEFT JOIN standard_exercise actual USING(id)
    WHERE actual.name IS DISTINCT FROM expected.name OR actual.is_active IS DISTINCT FROM true
  ) THEN RAISE EXCEPTION 'Conflicting exercise UUID/name; stopped'; END IF;
  IF EXISTS (
    SELECT 1 FROM (VALUES
      ('d1740000-0000-4000-8000-000000000001'::uuid,'파나타'),
      ('d1740000-0000-4000-8000-000000000002'::uuid,'해머스트렝스')
    ) expected(id,name) LEFT JOIN manufacturer actual USING(id)
    WHERE actual.name IS DISTINCT FROM expected.name OR actual.is_active IS DISTINCT FROM true
  ) THEN RAISE EXCEPTION 'Conflicting manufacturer UUID/name; stopped'; END IF;
  IF EXISTS (
    SELECT 1 FROM (VALUES
      ('d1720000-0000-4000-8000-000000000001'::uuid,'CHEST','가슴 (개발 예시)'),
      ('d1720000-0000-4000-8000-000000000002'::uuid,'ARMS','삼두 (개발 예시)'),
      ('d1720000-0000-4000-8000-000000000003'::uuid,'LEGS','대퇴사두 (개발 예시)'),
      ('d1720000-0000-4000-8000-000000000004'::uuid,'LEGS','둔근 (개발 예시)'),
      ('d1720000-0000-4000-8000-000000000005'::uuid,'BACK','광배 (개발 예시)'),
      ('d1720000-0000-4000-8000-000000000006'::uuid,'BACK','상부 등 (개발 예시)')
    ) expected(id,group_code,name) LEFT JOIN muscle actual USING(id)
    WHERE actual.name IS DISTINCT FROM expected.name OR actual.group_code IS DISTINCT FROM expected.group_code
      OR actual.is_active IS DISTINCT FROM true
  ) THEN RAISE EXCEPTION 'Conflicting development muscle UUID; stopped'; END IF;
  IF EXISTS (
    SELECT 1 FROM exercise_muscle WHERE role <> CASE
      WHEN muscle_id='d1720000-0000-4000-8000-000000000002'::uuid
        OR (standard_exercise_id='d1700000-0000-4000-8000-000000000005'::uuid
          AND muscle_id='d1720000-0000-4000-8000-000000000006'::uuid)
      THEN 'SECONDARY' ELSE 'PRIMARY' END
  ) THEN RAISE EXCEPTION 'Conflicting development muscle role; stopped'; END IF;
END $$;
COMMIT;
