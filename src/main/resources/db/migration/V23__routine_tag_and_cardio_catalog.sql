-- Additive only: never infer tags or catalog identities for existing plans/snapshots.
ALTER TABLE routines ADD COLUMN tag text NULL CHECK (tag IN ('PUSH','PULL','LEGS','FRONT','BACK','FULL','UPPER','LOWER','CARDIO','MIXED'));
CREATE TABLE cardio_activity_catalog (
 id uuid PRIMARY KEY,
 category text NOT NULL CHECK(category IN ('RUNNING','WALKING','CYCLING')),
 name text NOT NULL,
 kind text NOT NULL CHECK(kind IN ('TREADMILL','BIKE','OUTDOOR','STAIRS','OTHER')),
 active boolean NOT NULL DEFAULT true
);
INSERT INTO cardio_activity_catalog(id,category,name,kind) VALUES
 ('ca000000-0000-4000-8000-000000000001','RUNNING','러닝머신','TREADMILL'),
 ('ca000000-0000-4000-8000-000000000002','RUNNING','야외 러닝','OUTDOOR'),
 ('ca000000-0000-4000-8000-000000000003','RUNNING','트랙 러닝','OUTDOOR'),
 ('ca000000-0000-4000-8000-000000000004','WALKING','실내 걷기','TREADMILL'),
 ('ca000000-0000-4000-8000-000000000005','WALKING','야외 걷기','OUTDOOR'),
 ('ca000000-0000-4000-8000-000000000006','WALKING','등산','OUTDOOR'),
 ('ca000000-0000-4000-8000-000000000007','CYCLING','실내 자전거','BIKE'),
 ('ca000000-0000-4000-8000-000000000008','CYCLING','야외 자전거','BIKE');
ALTER TABLE routine_cardio_placements ADD COLUMN activity_id uuid NULL REFERENCES cardio_activity_catalog(id);
