-- Preserve all legacy records, snapshots and hashes. Unknown provenance stays NULL.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
ALTER TABLE cardio_results ADD COLUMN measurement JSONB;
ALTER TABLE cardio_results ADD CONSTRAINT cardio_measurement_shape CHECK (
    measurement IS NULL OR (
      status='COMPLETED' AND jsonb_typeof(measurement)='object'
      AND measurement ?& ARRAY['actualSpeedKmh','distanceSource']
      AND measurement - ARRAY['actualSpeedKmh','distanceSource'] = '{}'::jsonb
      AND (measurement->'actualSpeedKmh'='null'::jsonb OR
        (jsonb_typeof(measurement->'actualSpeedKmh')='string' AND (measurement->>'actualSpeedKmh') ~ '^(0|[1-9][0-9]{0,13})(\.[0-9]{0,5}[1-9])?$'))
      AND (measurement->'distanceSource'='null'::jsonb OR measurement->>'distanceSource' IN ('MANUAL','CALCULATED'))
      AND (measurement->>'distanceSource' IS NULL OR distance IS NOT NULL)
    )
);
ALTER TABLE cardio_results DROP CONSTRAINT cardio_results_distance_check;
ALTER TABLE cardio_results ADD CONSTRAINT cardio_results_distance_check CHECK (
    CASE WHEN measurement->>'distanceSource'='CALCULATED' THEN
      measurement->>'actualSpeedKmh' IS NOT NULL AND distance IS NOT NULL
      AND distance>=0 AND distance<100000000000000
      AND distance=round((measurement->>'actualSpeedKmh')::numeric*active_ms/3600000,6)
    ELSE distance BETWEEN 0.001 AND 1000 END
);
