-- A resolved week outside its ON interval is neither a zero-performance RESET nor an unresolved PENDING.
-- Existing rows, settings history and V1~V25 remain unchanged.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
ALTER TABLE deload_weekly_assessments
    DROP CONSTRAINT deload_weekly_assessments_status_check,
    DROP CONSTRAINT deload_weekly_assessments_check;
ALTER TABLE deload_weekly_assessments
    ADD CONSTRAINT deload_weekly_assessments_status_check CHECK(status IN ('PENDING','CONFIRMED','EXCLUDED')),
    ADD CONSTRAINT deload_weekly_assessments_check CHECK(
        status='PENDING' AND e_numerator IS NULL AND e_denominator IS NULL AND n IS NULL AND operation IS NULL
          AND contribution_numerator IS NULL AND contribution_denominator IS NULL AND confirmed_at IS NULL
        OR status='CONFIRMED' AND e_numerator IS NOT NULL AND e_denominator IS NOT NULL AND n IS NOT NULL AND operation IS NOT NULL
          AND contribution_numerator IS NOT NULL AND contribution_denominator IS NOT NULL AND confirmed_at IS NOT NULL
          AND isfinite(confirmed_at) AND confirmed_at<=updated_at AND e_numerator<=n*e_denominator
          AND (operation='RESET' AND e_numerator<=e_denominator AND contribution_numerator=0
            OR operation='ADD' AND e_numerator>e_denominator AND n>0
               AND contribution_numerator<=contribution_denominator
               AND contribution_numerator*e_denominator*n=e_numerator*contribution_denominator)
        OR status='EXCLUDED' AND e_numerator IS NULL AND e_denominator IS NULL AND n IS NULL AND operation IS NULL
          AND contribution_numerator IS NULL AND contribution_denominator IS NULL AND confirmed_at IS NOT NULL
          AND isfinite(confirmed_at) AND confirmed_at<=updated_at
    );
