-- Explicit psql invocation with -v user_id=<UUID> -v grant_access=true|false.
\set ON_ERROR_STOP on
\if :{?user_id}
\else
    \echo 'user_id is required'
    SELECT 1/0;
\endif
\if :{?grant_access}
\else
    \echo 'grant_access is required'
    SELECT 1/0;
\endif

BEGIN;
SELECT :'grant_access' IN ('true', 'false') AS valid_access_mode \gset
\if :valid_access_mode
\else
    \echo 'grant_access must be true or false'
    SELECT 1/0;
\endif
SELECT EXISTS (SELECT 1 FROM users WHERE id = :'user_id'::uuid) AS member_exists \gset
\if :member_exists
\else
    \echo 'Member UUID not found'
    SELECT 1/0;
\endif
\if :grant_access
    INSERT INTO exercise_gif_access(user_id) VALUES (:'user_id'::uuid) ON CONFLICT DO NOTHING;
\else
    DELETE FROM exercise_gif_access WHERE user_id = :'user_id'::uuid;
\endif
SELECT user_id, granted_at FROM exercise_gif_access WHERE user_id = :'user_id'::uuid;
COMMIT;
