-- Render with preview.mjs. Execute inside an explicit transaction.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
SET LOCAL standard_conforming_strings = on;

DO $$
BEGIN
    IF (SELECT max(version::integer) FROM flyway_schema_history WHERE success AND version IS NOT NULL) IS DISTINCT FROM 35 THEN
        RAISE EXCEPTION 'Expected the verified V35 catalog contract';
    END IF;
END $$;

LOCK TABLE manufacturer, standard_exercise, exercise_alias, exercise_muscle, exercise_source
    IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE handoff_payload (data jsonb NOT NULL, reuse jsonb NOT NULL, approve boolean NOT NULL) ON COMMIT DROP;
INSERT INTO handoff_payload VALUES (/* CATALOG_JSON */, /* REUSE_JSON */, /* APPROVE */);

CREATE TEMP TABLE handoff_exercise ON COMMIT DROP AS
SELECT x.*, x.id AS requested_id, COALESCE((p.reuse ->> x.key)::uuid, x.id) AS resolved_id
FROM handoff_payload p
CROSS JOIN LATERAL jsonb_to_recordset(p.data -> 'exercises') AS x(
    key text, id uuid, name text, english text, aliases jsonb, type text, method text, load text,
    manufacturer text, machine text, "jointType" text, ppl text, "frontBack" text, "primary" jsonb, "secondary" jsonb,
    steps jsonb, equipment text, "modelStatus" text, "cardioKind" text, "sourceId" text);

CREATE TEMP TABLE handoff_brand ON COMMIT DROP AS
SELECT x.*, m.matches, COALESCE(m.ids[1], x.id) AS resolved_id
FROM handoff_payload p
CROSS JOIN LATERAL jsonb_to_recordset(p.data -> 'manufacturers') AS x(name text, names jsonb, id uuid)
CROSS JOIN LATERAL (
    SELECT count(*) AS matches, array_agg(b.id ORDER BY b.id) AS ids
    FROM manufacturer b
    WHERE EXISTS (
        SELECT 1 FROM jsonb_array_elements_text(x.names) alias(name)
        WHERE regexp_replace(lower(normalize(b.name, NFKC)), '[[:space:]]+', '', 'g')
            = regexp_replace(lower(normalize(alias.name, NFKC)), '[[:space:]]+', '', 'g')
    )
) m;

CREATE TEMP TABLE handoff_muscle ON COMMIT DROP AS
SELECT h.key, target.role, target.position, target.name, m.id AS muscle_id
FROM handoff_exercise h
CROSS JOIN LATERAL (
    SELECT 'PRIMARY' AS role, ordinal::int AS position, name
    FROM jsonb_array_elements_text(h."primary") WITH ORDINALITY AS a(name, ordinal)
    UNION ALL
    SELECT 'SECONDARY', NULL::int, name FROM jsonb_array_elements_text(h."secondary") AS a(name)
) target
LEFT JOIN muscle m ON m.name = target.name AND m.is_active AND m.id::text LIKE 'd3200000-0000-4000-8000-0000000000__';

CREATE TEMP TABLE handoff_cardio ON COMMIT DROP AS
SELECT h.key, c.id, h."cardioKind"
FROM handoff_exercise h
JOIN cardio_activity_catalog c ON c.category = 'RUNNING' AND c.kind = h."cardioKind"
    AND c.id = CASE h."cardioKind"
        WHEN 'TREADMILL' THEN 'ca000000-0000-4000-8000-000000000001'::uuid
        WHEN 'OUTDOOR' THEN 'ca000000-0000-4000-8000-000000000002'::uuid END
    AND c.active;

CREATE TEMP TABLE handoff_existing ON COMMIT DROP AS
SELECT h.key, e.id, e.name
FROM handoff_exercise h
JOIN standard_exercise e ON e.id NOT IN (SELECT requested_id FROM handoff_exercise)
    AND (
        regexp_replace(lower(normalize(e.name, NFKC)), '[[:space:]]+', '', 'g') =
            regexp_replace(lower(normalize(h.name, NFKC)), '[[:space:]]+', '', 'g')
        OR (h.key = 'legs_machine_crunch' AND regexp_replace(normalize(e.name, NFKC), '[[:space:]]+', '', 'g') = '머신크런치')
        OR (h.key = 'pull_machine_high_row' AND e.id = 'd1700000-0000-4000-8000-000000000004')
        OR (h.key IN ('pull_convergent_lat_pulldown','pull_mts_front_pulldown')
            AND e.id = 'd1700000-0000-4000-8000-000000000005')
    );

DO $$
DECLARE conflict text;
BEGIN
    IF (SELECT count(*) FROM handoff_exercise) <> 31
        OR (SELECT count(DISTINCT resolved_id) FROM handoff_exercise) <> 31
        OR (SELECT count(*) FROM handoff_cardio) <> 2
        OR EXISTS (
            SELECT 1 FROM handoff_payload p CROSS JOIN LATERAL jsonb_object_keys(p.reuse) AS r(key)
            WHERE NOT EXISTS (SELECT 1 FROM handoff_exercise h WHERE h.key = r.key)
        ) THEN
        RAISE EXCEPTION 'Invalid handoff, duplicate standard UUID, or missing RUNNING setting';
    END IF;
    IF EXISTS (SELECT 1 FROM handoff_exercise WHERE
        (type = 'WEIGHT' AND ("jointType" IS NULL OR "jointType" NOT IN ('COMPOUND', 'ISOLATION')
            OR ppl IS NULL OR "frontBack" IS NULL))
        OR (type = 'CARDIO' AND ("jointType" IS NOT NULL OR ppl IS NOT NULL OR "frontBack" IS NOT NULL))) THEN
        RAISE EXCEPTION 'Incomplete weight classification or unexpected cardio classification';
    END IF;
    IF EXISTS (SELECT 1 FROM handoff_muscle WHERE muscle_id IS NULL)
        OR EXISTS (SELECT 1 FROM handoff_muscle GROUP BY key, name HAVING count(*) <> 1) THEN
        RAISE EXCEPTION 'Unknown, ambiguous or repeated V32 muscle';
    END IF;

    SELECT string_agg(b.name, ', ' ORDER BY b.name) INTO conflict
    FROM handoff_brand b LEFT JOIN manufacturer m ON m.id = b.resolved_id
    WHERE b.matches > 1 OR (m.id IS NOT NULL AND (NOT m.is_active OR b.matches = 0));
    IF conflict IS NOT NULL THEN RAISE EXCEPTION 'Manufacturer conflict: %', conflict; END IF;

    SELECT string_agg(h.key || '=' || e.id::text, ', ' ORDER BY h.key, e.id) INTO conflict
    FROM handoff_exercise h JOIN handoff_existing e USING(key)
    WHERE h.resolved_id = h.requested_id AND NOT (SELECT reuse ? h.key FROM handoff_payload);
    IF conflict IS NOT NULL THEN
        RAISE EXCEPTION 'Verify existing exercise identity before selecting a UUID: %', conflict;
    END IF;

    SELECT string_agg(h.key || '=' || h.resolved_id::text, ', ' ORDER BY h.key) INTO conflict
    FROM handoff_exercise h
    LEFT JOIN standard_exercise e ON e.id = h.resolved_id
    LEFT JOIN handoff_cardio c USING(key)
    WHERE (h.resolved_id <> h.requested_id AND e.id IS NULL)
       OR (e.id IS NOT NULL AND (
              e.exercise_type <> h.type OR e.record_method <> h.method
              OR e.cardio_activity_id IS DISTINCT FROM c.id
              OR (h.resolved_id = h.requested_id AND e.name <> h.name)
              OR (h.name = '머신 숄더 프레스' AND position(lower(h.manufacturer)
                    IN lower(COALESCE(e.equipment_description, ''))) = 0)
       ));
    IF conflict IS NOT NULL THEN RAISE EXCEPTION 'Standard exercise UUID or variant conflict: %', conflict; END IF;

    IF NOT EXISTS (
        SELECT 1 FROM handoff_exercise h CROSS JOIN handoff_payload p
        WHERE h.key = 'pull_machine_high_row' AND h."sourceId" = 'nZZZy9m'
            AND h."sourceId" = p.data #>> '{highRowSource,id}'
            AND p.data #>> '{highRowSource,position}' = '729'
    ) OR (SELECT count("sourceId") FROM handoff_exercise) <> 1 THEN
        RAISE EXCEPTION 'Only the designated high row may have an ExerciseDB source';
    END IF;
    IF EXISTS (
        SELECT 1 FROM handoff_payload p
        JOIN exercise_db_v1_candidate c ON c.source_exercise_id = 'nZZZy9m'
        WHERE c.source_position <> 729
           OR c.source_file_sha256 <> (p.data #>> '{highRowSource,sha256}')
           OR (c.original ->> 'name') IS DISTINCT FROM (p.data #>> '{highRowSource,name}')
           OR (c.original ->> 'gifUrl') IS DISTINCT FROM (p.data #>> '{highRowSource,gif}')
    ) THEN
        RAISE EXCEPTION 'The staged high-row original differs from the verified source';
    END IF;
    IF EXISTS (
        SELECT 1 FROM handoff_payload p CROSS JOIN handoff_exercise h
        JOIN exercise_source s ON s.source_system = 'EXERCISEDB_V1'
            AND s.source_exercise_id = h."sourceId"
        WHERE s.standard_exercise_id <> h.resolved_id
           OR s.original_name IS DISTINCT FROM (p.data #>> '{highRowSource,name}')
           OR s.original_gif_reference IS DISTINCT FROM (p.data #>> '{highRowSource,gif}')
    ) THEN
        RAISE EXCEPTION 'ExerciseDB nZZZy9m already has a different link or original metadata';
    END IF;
END $$;

CREATE TEMP TABLE handoff_new ON COMMIT DROP AS
SELECT h.key FROM handoff_exercise h
WHERE NOT EXISTS (SELECT 1 FROM standard_exercise e WHERE e.id = h.resolved_id);

INSERT INTO manufacturer(id, name)
SELECT b.resolved_id, b.name FROM handoff_brand b WHERE b.matches = 0;

INSERT INTO standard_exercise(
    id, name, exercise_type, record_method, joint_type, ppl_split, front_back_split, instructions_ko,
    equipment_description, cardio_activity_id, muscle_targets_reviewed, splits_reviewed)
SELECT h.resolved_id, h.name, h.type, h.method, h."jointType", h.ppl, h."frontBack",
    (SELECT string_agg(step, E'\n' ORDER BY ordinal)
     FROM jsonb_array_elements_text(h.steps) WITH ORDINALITY AS t(step, ordinal)),
    concat_ws(' · ', h.manufacturer, h.machine, h.equipment), c.id, false, false
FROM handoff_exercise h JOIN handoff_new n USING(key)
LEFT JOIN handoff_cardio c USING(key);

INSERT INTO exercise_alias(standard_exercise_id, alias, normalized_alias)
SELECT DISTINCT ON (h.resolved_id, normalized) h.resolved_id, a.alias, normalized
FROM handoff_exercise h JOIN handoff_new n USING(key)
CROSS JOIN LATERAL jsonb_array_elements_text(jsonb_build_array(h.english) || h.aliases)
    WITH ORDINALITY AS a(alias, ordinal)
CROSS JOIN LATERAL (
    SELECT lower(btrim(regexp_replace(normalize(a.alias, NFKC), '[[:space:]]+', ' ', 'g'))) AS normalized
) k
ORDER BY h.resolved_id, normalized, a.ordinal;

INSERT INTO exercise_muscle(standard_exercise_id, muscle_id, role, primary_order)
SELECT h.resolved_id, m.muscle_id, m.role, m.position
FROM handoff_muscle m JOIN handoff_exercise h USING(key) JOIN handoff_new n USING(key);

INSERT INTO exercise_source(source_system, source_exercise_id, standard_exercise_id, original_name, original_gif_reference)
SELECT 'EXERCISEDB_V1', h."sourceId", h.resolved_id,
    p.data #>> '{highRowSource,name}', p.data #>> '{highRowSource,gif}'
FROM handoff_exercise h CROSS JOIN handoff_payload p
WHERE h."sourceId" IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM exercise_source s WHERE s.source_system = 'EXERCISEDB_V1' AND s.source_exercise_id = h."sourceId"
);

-- Approval uses the same catalog contract as the insert. An explicitly reused
-- row must already match the reviewed metadata and ordered muscle links.
DO $$
DECLARE mismatch text;
BEGIN
    IF NOT (SELECT approve FROM handoff_payload) THEN RETURN; END IF;
    SELECT string_agg(h.key, ', ' ORDER BY h.key) INTO mismatch
    FROM handoff_exercise h
    JOIN standard_exercise e ON e.id = h.resolved_id
    WHERE NOT e.is_active OR
        ROW(e.name, e.joint_type, e.ppl_split, e.front_back_split, e.instructions_ko, e.equipment_description)
            IS DISTINCT FROM ROW(h.name, h."jointType", h.ppl, h."frontBack",
                (SELECT string_agg(step, E'\n' ORDER BY ordinal)
                 FROM jsonb_array_elements_text(h.steps) WITH ORDINALITY AS t(step, ordinal)),
                concat_ws(' · ', h.manufacturer, h.machine, h.equipment))
        OR EXISTS (
            (SELECT m.muscle_id, m.role, m.position FROM handoff_muscle m WHERE m.key = h.key
             EXCEPT
             SELECT m.muscle_id, m.role, m.primary_order FROM exercise_muscle m WHERE m.standard_exercise_id = e.id)
            UNION ALL
            (SELECT m.muscle_id, m.role, m.primary_order FROM exercise_muscle m WHERE m.standard_exercise_id = e.id
             EXCEPT
             SELECT m.muscle_id, m.role, m.position FROM handoff_muscle m WHERE m.key = h.key)
        )
        OR EXISTS (
            SELECT 1 FROM jsonb_array_elements_text(jsonb_build_array(h.english) || h.aliases) AS a(alias)
            WHERE NOT EXISTS (
                SELECT 1 FROM exercise_alias s WHERE s.standard_exercise_id = e.id
                AND s.normalized_alias = lower(btrim(regexp_replace(normalize(a.alias, NFKC), '[[:space:]]+', ' ', 'g')))
            )
        );
    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'Review approval requires matching catalog content: %', mismatch;
    END IF;
END $$;

UPDATE standard_exercise e
SET muscle_targets_reviewed = true, splits_reviewed = true,
    updated_at = greatest(clock_timestamp(), e.updated_at + interval '1 microsecond')
FROM handoff_exercise h CROSS JOIN handoff_payload p
WHERE p.approve AND e.id = h.resolved_id AND (NOT e.muscle_targets_reviewed OR NOT e.splits_reviewed);

SELECT h.key, h.name, h.resolved_id AS standard_exercise_id, b.resolved_id AS manufacturer_id,
       h.manufacturer, h.machine, h.load AS placement_load_type, e.cardio_activity_id, h."sourceId" AS source_id,
       n.key IS NOT NULL AS inserted, e.joint_type, e.ppl_split, e.front_back_split,
       e.muscle_targets_reviewed, e.splits_reviewed
FROM handoff_exercise h
JOIN standard_exercise e ON e.id = h.resolved_id
LEFT JOIN handoff_brand b ON b.name = h.manufacturer
LEFT JOIN handoff_new n USING(key)
ORDER BY h.key;
