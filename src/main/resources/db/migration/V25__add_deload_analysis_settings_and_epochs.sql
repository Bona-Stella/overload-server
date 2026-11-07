-- G4-1 schema only. Existing owners require a separately approved, explicit T_adopt.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

CREATE FUNCTION deload_time_zone_valid(zone TEXT) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT zone IS NULL OR EXISTS (SELECT 1 FROM pg_timezone_names WHERE name=zone)
$$;

CREATE TABLE deload_analysis_versions (
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL,
    version BIGINT NOT NULL CHECK(version>=0),
    enabled BOOLEAN NOT NULL,
    epoch BIGINT NOT NULL CHECK(epoch>=0 AND epoch<=version),
    recorded_at TIMESTAMPTZ NOT NULL CHECK(isfinite(recorded_at)),
    epoch_started_at TIMESTAMPTZ NOT NULL CHECK(isfinite(epoch_started_at) AND epoch_started_at<=recorded_at),
    time_zone TEXT CHECK(deload_time_zone_valid(time_zone)),
    reason TEXT NOT NULL CHECK(reason IN ('INITIAL','ADOPTED_INITIAL','TIME_ZONE_SET','DISABLED','ENABLED')),
    PRIMARY KEY(user_id,data_generation,version),
    FOREIGN KEY(user_id,data_generation) REFERENCES users(id,data_generation),
    FOREIGN KEY(user_id,data_generation,epoch) REFERENCES deload_analysis_versions(user_id,data_generation,version)
);

CREATE FUNCTION guard_deload_analysis_version() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous deload_analysis_versions%ROWTYPE; created TIMESTAMPTZ;
BEGIN
    IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'Immutable analysis version' USING ERRCODE='23514'; END IF;
    SELECT created_at INTO created FROM users WHERE id=NEW.user_id AND data_generation=NEW.data_generation FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Analysis owner missing' USING ERRCODE='23503'; END IF;
    SELECT * INTO previous FROM deload_analysis_versions WHERE user_id=NEW.user_id AND data_generation=NEW.data_generation ORDER BY version DESC LIMIT 1;
    IF NOT FOUND THEN
        IF NEW.version<>0 OR NEW.epoch<>0 OR NOT NEW.enabled OR NEW.reason NOT IN ('INITIAL','ADOPTED_INITIAL')
           OR NEW.recorded_at<>NEW.epoch_started_at OR NEW.epoch_started_at<created
           OR NEW.reason='INITIAL' AND (NEW.epoch_started_at<>created OR NEW.time_zone IS NOT NULL) THEN
            RAISE EXCEPTION 'Invalid initial analysis' USING ERRCODE='23514';
        END IF;
    ELSE
        IF previous.version=9223372036854775807 OR NEW.version<>previous.version+1 OR NEW.recorded_at<previous.recorded_at
           OR previous.time_zone IS NOT NULL AND NEW.time_zone IS DISTINCT FROM previous.time_zone THEN
            RAISE EXCEPTION 'Invalid analysis version' USING ERRCODE='23514';
        END IF;
        IF NEW.reason='ENABLED' THEN
            IF previous.enabled OR NOT NEW.enabled OR NEW.epoch<>NEW.version OR NEW.epoch_started_at<>NEW.recorded_at OR NEW.time_zone IS NULL THEN
                RAISE EXCEPTION 'Invalid reactivation' USING ERRCODE='23514';
            END IF;
        ELSE
            IF NEW.epoch<>previous.epoch OR NEW.epoch_started_at<>previous.epoch_started_at
               OR NOT (NEW.reason='DISABLED' AND previous.enabled AND NOT NEW.enabled AND NEW.time_zone IS NOT NULL
                   OR NEW.reason='TIME_ZONE_SET' AND NEW.enabled=previous.enabled AND previous.time_zone IS NULL AND NEW.time_zone IS NOT NULL) THEN
                RAISE EXCEPTION 'Invalid analysis transition' USING ERRCODE='23514';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER deload_analysis_version_guard BEFORE INSERT OR UPDATE ON deload_analysis_versions
    FOR EACH ROW EXECUTE FUNCTION guard_deload_analysis_version();

CREATE TABLE deload_weekly_assessments (
    user_id UUID NOT NULL,
    data_generation BIGINT NOT NULL,
    epoch BIGINT NOT NULL,
    week_start DATE NOT NULL CHECK(isfinite(week_start) AND extract(isodow FROM week_start)=1),
    status TEXT NOT NULL CHECK(status IN ('PENDING','CONFIRMED')),
    e_numerator NUMERIC CHECK(e_numerator>=0 AND scale(e_numerator)=0 AND e_numerator::text NOT IN ('NaN','Infinity','-Infinity')),
    e_denominator NUMERIC CHECK(e_denominator>0 AND scale(e_denominator)=0 AND e_denominator::text NOT IN ('NaN','Infinity','-Infinity')),
    n INTEGER CHECK(n BETWEEN 0 AND 7),
    operation TEXT CHECK(operation IN ('RESET','ADD')),
    contribution_numerator NUMERIC CHECK(contribution_numerator>=0 AND scale(contribution_numerator)=0 AND contribution_numerator::text NOT IN ('NaN','Infinity','-Infinity')),
    contribution_denominator NUMERIC CHECK(contribution_denominator>0 AND scale(contribution_denominator)=0 AND contribution_denominator::text NOT IN ('NaN','Infinity','-Infinity')),
    rule_version INTEGER NOT NULL CHECK(rule_version>0),
    revision BIGINT NOT NULL CHECK(revision>=0),
    input_revision TEXT NOT NULL CHECK(input_revision !~ '^[[:space:]]*$'),
    needs_recheck BOOLEAN NOT NULL DEFAULT false,
    confirmed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL CHECK(isfinite(updated_at)),
    PRIMARY KEY(user_id,data_generation,epoch,week_start),
    FOREIGN KEY(user_id,data_generation,epoch) REFERENCES deload_analysis_versions(user_id,data_generation,version),
    CHECK(status='PENDING' AND e_numerator IS NULL AND e_denominator IS NULL AND n IS NULL AND operation IS NULL
          AND contribution_numerator IS NULL AND contribution_denominator IS NULL AND confirmed_at IS NULL
       OR status='CONFIRMED' AND e_numerator IS NOT NULL AND e_denominator IS NOT NULL AND n IS NOT NULL AND operation IS NOT NULL
          AND contribution_numerator IS NOT NULL AND contribution_denominator IS NOT NULL AND confirmed_at IS NOT NULL
          AND isfinite(confirmed_at) AND confirmed_at<=updated_at AND e_numerator<=n*e_denominator
          AND (operation='RESET' AND e_numerator<=e_denominator AND contribution_numerator=0
            OR operation='ADD' AND e_numerator>e_denominator AND n>0
               AND contribution_numerator<=contribution_denominator
               AND contribution_numerator*e_denominator*n=e_numerator*contribution_denominator))
);
CREATE INDEX deload_weekly_recheck ON deload_weekly_assessments(user_id,data_generation,epoch,week_start) WHERE needs_recheck;
CREATE FUNCTION guard_deload_weekly_epoch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM deload_analysis_versions WHERE user_id=NEW.user_id AND data_generation=NEW.data_generation
                  AND version=NEW.epoch AND epoch=version AND enabled AND reason IN ('INITIAL','ADOPTED_INITIAL','ENABLED')) THEN
        RAISE EXCEPTION 'Analysis epoch missing' USING ERRCODE='23503';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER deload_weekly_epoch_guard BEFORE INSERT OR UPDATE ON deload_weekly_assessments
    FOR EACH ROW EXECUTE FUNCTION guard_deload_weekly_epoch();
