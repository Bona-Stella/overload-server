ALTER TABLE weekly_schedule_presets
    ADD COLUMN name VARCHAR(100) CHECK (name !~ '^[[:space:]]*$');
