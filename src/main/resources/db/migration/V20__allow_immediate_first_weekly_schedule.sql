-- Preserve every recorded schedule. Only the first version may start off Monday.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE weekly_schedule_versions
    DROP CONSTRAINT weekly_schedule_versions_effective_from_check;
ALTER TABLE weekly_schedule_versions
    ADD CONSTRAINT weekly_schedule_versions_effective_from_check
    CHECK (version = 1 OR extract(isodow FROM effective_from) = 1);
