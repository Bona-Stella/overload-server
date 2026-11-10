SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE standard_exercise
    ADD COLUMN exercise_type TEXT NOT NULL DEFAULT 'WEIGHT'
        CHECK (exercise_type IN ('WEIGHT', 'CARDIO', 'STATIC_HOLD', 'STRETCH', 'OTHER')),
    ADD COLUMN record_method TEXT NOT NULL DEFAULT 'SETS_REPS'
        CHECK (record_method IN ('SETS_REPS', 'CARDIO', 'HOLD_DURATION', 'UNSPECIFIED')),
    ADD COLUMN ppl_split TEXT CHECK (ppl_split IN ('PUSH', 'PULL', 'LEGS')),
    ADD COLUMN front_back_split TEXT CHECK (front_back_split IN ('FRONT', 'BACK')),
    ADD COLUMN instructions_ko TEXT CHECK (instructions_ko !~ '^[[:space:]]*$'),
    ADD COLUMN equipment_description TEXT CHECK (equipment_description !~ '^[[:space:]]*$'),
    ADD COLUMN representative_gif_key TEXT CHECK (representative_gif_key !~ '^[[:space:]]*$'),
    ADD COLUMN muscle_targets_reviewed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN splits_reviewed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE muscle
    ADD COLUMN default_ppl_split TEXT CHECK (default_ppl_split IN ('PUSH', 'PULL', 'LEGS')),
    ADD COLUMN default_front_back_split TEXT CHECK (default_front_back_split IN ('FRONT', 'BACK'));

INSERT INTO muscle (id, group_code, name, default_ppl_split, default_front_back_split) VALUES
    ('d3200000-0000-4000-8000-000000000001', 'CHEST', '대흉근 상부', 'PUSH', 'FRONT'),
    ('d3200000-0000-4000-8000-000000000002', 'CHEST', '대흉근 중부', 'PUSH', 'FRONT'),
    ('d3200000-0000-4000-8000-000000000003', 'CHEST', '대흉근 하부', 'PUSH', 'FRONT'),
    ('d3200000-0000-4000-8000-000000000004', 'CHEST', '소흉근', 'PUSH', 'FRONT'),
    ('d3200000-0000-4000-8000-000000000005', 'BACK', '광배근', 'PULL', 'BACK'),
    ('d3200000-0000-4000-8000-000000000006', 'BACK', '능형근', 'PULL', 'BACK'),
    ('d3200000-0000-4000-8000-000000000007', 'BACK', '상부승모근', 'PULL', 'BACK'),
    ('d3200000-0000-4000-8000-000000000008', 'BACK', '중부승모근', 'PULL', 'BACK'),
    ('d3200000-0000-4000-8000-000000000009', 'BACK', '하부승모근', 'PULL', 'BACK'),
    ('d3200000-0000-4000-8000-000000000010', 'BACK', '척추기립근', 'PULL', 'BACK'),
    ('d3200000-0000-4000-8000-000000000011', 'BACK', '대원근', 'PULL', 'BACK'),
    ('d3200000-0000-4000-8000-000000000012', 'SHOULDERS', '전면삼각근', 'PUSH', 'FRONT'),
    ('d3200000-0000-4000-8000-000000000013', 'SHOULDERS', '측면삼각근', 'PUSH', 'FRONT'),
    ('d3200000-0000-4000-8000-000000000014', 'SHOULDERS', '후면삼각근', 'PULL', 'BACK'),
    ('d3200000-0000-4000-8000-000000000015', 'SHOULDERS', '극상근', NULL, NULL),
    ('d3200000-0000-4000-8000-000000000016', 'SHOULDERS', '극하근', NULL, NULL),
    ('d3200000-0000-4000-8000-000000000017', 'SHOULDERS', '소원근', NULL, NULL),
    ('d3200000-0000-4000-8000-000000000018', 'SHOULDERS', '견갑하근', NULL, NULL),
    ('d3200000-0000-4000-8000-000000000019', 'SHOULDERS', '전거근', NULL, NULL),
    ('d3200000-0000-4000-8000-000000000020', 'ARMS', '상완이두근', 'PULL', 'BACK'),
    ('d3200000-0000-4000-8000-000000000021', 'ARMS', '상완근', NULL, NULL),
    ('d3200000-0000-4000-8000-000000000022', 'ARMS', '상완요골근', NULL, NULL),
    ('d3200000-0000-4000-8000-000000000023', 'ARMS', '상완삼두근 장두', 'PUSH', 'FRONT'),
    ('d3200000-0000-4000-8000-000000000024', 'ARMS', '상완삼두근 외측두', 'PUSH', 'FRONT'),
    ('d3200000-0000-4000-8000-000000000025', 'ARMS', '상완삼두근 내측두', 'PUSH', 'FRONT'),
    ('d3200000-0000-4000-8000-000000000026', 'ARMS', '전완굴근군', NULL, NULL),
    ('d3200000-0000-4000-8000-000000000027', 'ARMS', '전완신근군', NULL, NULL),
    ('d3200000-0000-4000-8000-000000000028', 'LEGS', '대퇴사두근', 'LEGS', 'FRONT'),
    ('d3200000-0000-4000-8000-000000000029', 'LEGS', '햄스트링', 'LEGS', 'BACK'),
    ('d3200000-0000-4000-8000-000000000030', 'LEGS', '대둔근', 'LEGS', 'BACK'),
    ('d3200000-0000-4000-8000-000000000031', 'LEGS', '중둔근', 'LEGS', 'BACK'),
    ('d3200000-0000-4000-8000-000000000032', 'LEGS', '소둔근', 'LEGS', 'BACK'),
    ('d3200000-0000-4000-8000-000000000033', 'LEGS', '내전근군', 'LEGS', NULL),
    ('d3200000-0000-4000-8000-000000000034', 'LEGS', '비복근', 'LEGS', 'BACK'),
    ('d3200000-0000-4000-8000-000000000035', 'LEGS', '가자미근', 'LEGS', 'BACK'),
    ('d3200000-0000-4000-8000-000000000036', 'LEGS', '전경골근', 'LEGS', NULL),
    ('d3200000-0000-4000-8000-000000000037', 'LEGS', '고관절 굴곡근군(장요근 중심)', 'LEGS', NULL),
    ('d3200000-0000-4000-8000-000000000038', 'CORE', '복직근', NULL, 'FRONT'),
    ('d3200000-0000-4000-8000-000000000039', 'CORE', '복사근군', NULL, NULL),
    ('d3200000-0000-4000-8000-000000000040', 'CORE', '복횡근', NULL, NULL);

ALTER TABLE exercise_muscle ADD COLUMN primary_order INTEGER;

-- Preserve the prior catalog display order for existing PRIMARY links.
WITH ranked AS (
    SELECT standard_exercise_id, muscle_id,
           row_number() OVER (PARTITION BY standard_exercise_id ORDER BY muscle_id) AS position
    FROM exercise_muscle
    WHERE role = 'PRIMARY'
)
UPDATE exercise_muscle link
SET primary_order = ranked.position
FROM ranked
WHERE link.standard_exercise_id = ranked.standard_exercise_id AND link.muscle_id = ranked.muscle_id;

ALTER TABLE exercise_muscle
    ADD CONSTRAINT exercise_muscle_role_order_check CHECK (
        (role = 'PRIMARY' AND primary_order IS NOT NULL AND primary_order > 0)
        OR (role = 'SECONDARY' AND primary_order IS NULL)
    ),
    ADD CONSTRAINT exercise_muscle_primary_order_unique UNIQUE (standard_exercise_id, primary_order);

CREATE TABLE exercise_source (
    source_system TEXT NOT NULL CHECK (source_system IN ('EXERCISEDB_V1')),
    source_exercise_id TEXT NOT NULL CHECK (source_exercise_id !~ '^[[:space:]]*$'),
    standard_exercise_id UUID NOT NULL REFERENCES standard_exercise(id),
    original_name TEXT NOT NULL CHECK (original_name !~ '^[[:space:]]*$'),
    original_gif_reference TEXT CHECK (original_gif_reference !~ '^[[:space:]]*$'),
    PRIMARY KEY (source_system, source_exercise_id)
);

CREATE INDEX exercise_source_standard_exercise_idx ON exercise_source (standard_exercise_id);
