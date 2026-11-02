-- V18+ only. Read-only structural audit; no flags does NOT certify anatomical accuracy.
-- NULL classification is pending review, not OTHER. No inferred mapping or data repair.
SELECT e.id, e.name, e.is_active, e.joint_type,
       e.joint_type IS NULL AS joint_type_missing,
       count(*) FILTER (WHERE em.role = 'PRIMARY' AND m.is_active) AS primary_count,
       count(*) FILTER (WHERE em.role = 'SECONDARY' AND m.is_active) AS secondary_count,
       count(*) FILTER (WHERE em.role = 'PRIMARY' AND m.is_active) = 0 AS active_primary_missing,
       coalesce(bool_or(NOT m.is_active), false) AS inactive_muscle_present,
       coalesce(bool_or(m.name LIKE '%개발 예시%'), false) AS development_label_present
FROM standard_exercise e
LEFT JOIN exercise_muscle em ON em.standard_exercise_id = e.id
LEFT JOIN muscle m ON m.id = em.muscle_id
GROUP BY e.id
ORDER BY e.id;
