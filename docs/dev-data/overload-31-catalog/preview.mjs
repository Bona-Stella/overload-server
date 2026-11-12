import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const read = path => readFileSync(path, 'utf8');
const parse = path => JSON.parse(read(path));
const catalog = parse(new URL('./catalog.json', import.meta.url));
const migration = read(new URL('../../../src/main/resources/db/migration/V32__expand_standard_exercise_catalog.sql', import.meta.url));
const names = new Set([...migration.matchAll(/'d3200000-[^']+', '[A-Z]+', '([^']+)'/g)].map(match => match[1]));
const byKey = new Map(catalog.exercises.map(row => [row.key, row]));
const unique = values => new Set(values).size === values.length;

assert.equal(names.size, 40);
assert.equal(byKey.size, 31);
assert.equal(catalog.exercises.length, byKey.size);
assert.ok(unique(catalog.exercises.map(row => row.id)));
assert.equal(catalog.exercises.filter(row => row.type === 'WEIGHT' && row.method === 'SETS_REPS').length, 29);
assert.equal(catalog.exercises.filter(row => row.type === 'CARDIO' && row.method === 'CARDIO').length, 2);
assert.equal(catalog.exercises.filter(row => row.jointType === 'COMPOUND').length, 15);
assert.equal(catalog.exercises.filter(row => row.jointType === 'ISOLATION').length, 14);
assert.deepEqual(catalog.exercises.filter(row => row.sourceId).map(row => [row.key, row.sourceId]),
  [['pull_machine_high_row', 'nZZZy9m']]);
assert.equal(catalog.highRowSource.id, 'nZZZy9m');
assert.equal(catalog.highRowSource.position, 729);
for (const row of catalog.exercises) {
  assert.ok(row.steps.length >= 4 && row.steps.length <= 6 && row.steps.every(step => /[가-힣]/u.test(step)), row.key);
  assert.ok(/[가-힣]/u.test(row.name), row.key);
  if (row.type === 'WEIGHT') {
    assert.ok(['COMPOUND', 'ISOLATION'].includes(row.jointType), row.key);
    assert.ok(['PUSH', 'PULL', 'LEGS'].includes(row.ppl), row.key);
    assert.ok(['FRONT', 'BACK'].includes(row.frontBack), row.key);
  } else {
    assert.deepEqual([row.jointType, row.ppl, row.frontBack], [null, null, null], row.key);
  }
  const targets = [...row.primary, ...row.secondary];
  assert.ok(unique(targets) && targets.every(name => names.has(name)), row.key);
}

function verifyHandoff(path, sourcePath) {
  const handoff = parse(path);
  assert.deepEqual(new Set(Object.values(handoff.muscle_master_reference_names).flat()), names);
  assert.equal(handoff.standard_exercises.length, 31);
  assert.equal(handoff.strength_routine.length, 33);
  assert.equal(handoff.cardio_routine.length, 4);
  assert.deepEqual(handoff.cardio_rest_days, ['WED', 'SAT', 'SUN']);
  assert.deepEqual(handoff.excluded_exercises, ['Baseball']);
  const routines = [...handoff.strength_routine, ...handoff.cardio_routine];
  assert.ok(routines.every(slot => byKey.has(slot.exercise_review_key)));
  assert.ok(handoff.strength_routine.every(slot => byKey.get(slot.exercise_review_key).type === 'WEIGHT'));
  assert.ok(handoff.cardio_routine.every(slot => byKey.get(slot.exercise_review_key).type === 'CARDIO'
    && !handoff.cardio_rest_days.includes(slot.day)));
  const fridayRaise = routines.find(slot => slot.day === 'FRI' && slot.exercise_review_key === 'push_dumbbell_lateral_raise');
  assert.equal(fridayRaise.completed_repetitions_by_set[2], 20);
  const romanian = routines.find(slot => slot.day === 'WED' && slot.exercise_review_key === 'legs_romanian_deadlift');
  assert.equal(romanian.weight_source_notation, '2×10kg');
  assert.equal(romanian.machine_reference, 'Barbell Romanian Deadlift');

  assert.ok(unique(handoff.standard_exercises.map(exercise => exercise.review_key)));
  const loads = { WEIGHT_STACK: 'STACK', PLATE_LOADED: 'PLATE', DUMBBELL: 'DUMBBELL', BODYWEIGHT: 'BODYWEIGHT', CARDIO: null };
  // The original handoff is retained. Apply the later user corrections only to the expected comparison.
  const corrections = {
    push_hammer_shoulder_press: { load: 'STACK' },
    push_standing_machine_lateral_raise: { frontBack: 'FRONT' },
    push_machine_dips_press: { frontBack: 'FRONT' },
    push_dumbbell_lateral_raise: { frontBack: 'FRONT' },
    legs_machine_crunch: { name: '머신 업도미널 크런치', ppl: 'LEGS' },
    legs_rogers_hip_press: { frontBack: 'BACK' },
    legs_captains_chair_leg_raise: { ppl: 'LEGS' },
  };
  for (const exercise of handoff.standard_exercises) {
    const row = byKey.get(exercise.review_key);
    assert.ok(row, exercise.review_key);
    const expected = {
      name: exercise.standard_name_ko, load: loads[exercise.resistance_mode_confirmed],
      ppl: exercise.ppl_candidate, frontBack: exercise.front_back_candidate,
      ...corrections[exercise.review_key],
    };
    const aliases = expected.name === exercise.standard_name_ko ? exercise.search_aliases_ko_en
      : [...exercise.search_aliases_ko_en, exercise.standard_name_ko];
    assert.deepEqual(
      [row.name, row.english, row.aliases, row.type, row.method, row.load, row.ppl, row.frontBack,
        row.primary, row.secondary, row.steps, row.modelStatus, row.sourceId],
      [expected.name, exercise.english_reference_name, aliases,
        exercise.exercise_type, exercise.recording_type, expected.load,
        expected.ppl, expected.frontBack, exercise.primary_muscles_candidate,
        exercise.secondary_muscles_candidate, exercise.instruction_steps_ko,
        exercise.model_match_status, exercise.exercise_db_source_id],
      exercise.review_key,
    );
    const slots = routines.filter(slot => slot.exercise_review_key === exercise.review_key);
    const matchesManufacturer = slot => slot.manufacturer_reference === row.manufacturer ||
      (row.key === 'legs_pendulum_squat' && row.manufacturer === 'Arsenal Strength'
        && slot.manufacturer_reference === 'Hammer Strength');
    assert.ok(slots.length && slots.every(slot => matchesManufacturer(slot)
      && slot.machine_reference === row.machine), exercise.review_key);
  }
  const bytes = readFileSync(sourcePath);
  assert.equal(createHash('sha256').update(bytes).digest('hex'), catalog.highRowSource.sha256);
  const sourceRows = JSON.parse(bytes);
  assert.equal(sourceRows.length, 1500);
  const source = sourceRows[728];
  assert.deepEqual([source.exerciseId, source.name, source.gifUrl],
    [catalog.highRowSource.id, catalog.highRowSource.name, catalog.highRowSource.gif]);
  assert.equal(sourceRows.filter(row => row.exerciseId === 'nZZZy9m').length, 1);
  console.error('입력 대조 통과 · 표준 운동 31개(웨이트 29, 유산소 2), 루틴 배치 33/4건, V32 근육명 40개, 단일 원본 729번');
}

const args = process.argv.slice(2);
const approve = args[0] === '--approve';
if (approve) args.shift();
const [handoffPath, sourcePath, resolutionsPath, ...extra] = args;
if (extra.length || Boolean(handoffPath) !== Boolean(sourcePath)) {
  throw new Error('사용법: node preview.mjs [--approve] [handoff.json exercises.json [resolutions.json]]');
}
if (handoffPath) verifyHandoff(handoffPath, sourcePath);
if (approve) assert.equal(handoffPath && parse(handoffPath).approval_context.catalog_muscle_and_split_review, 'USER_APPROVED');
const reuse = resolutionsPath ? parse(resolutionsPath) : {};
assert.ok(reuse && typeof reuse === 'object' && !Array.isArray(reuse));
for (const [key, id] of Object.entries(reuse)) {
  assert.ok(byKey.has(key) && (id === null || /^[\da-f]{8}-(?:[\da-f]{4}-){3}[\da-f]{12}$/i.test(id)), key);
}
const sqlJson = value => "'" + JSON.stringify(value).replaceAll("'", "''") + "'::jsonb";
const plan = read(new URL('./catalog-plan.sql', import.meta.url))
  .replace('/* CATALOG_JSON */', () => sqlJson(catalog))
  .replace('/* REUSE_JSON */', () => sqlJson(reuse))
  .replace('/* APPROVE */', approve ? 'true' : 'false');
process.stdout.write('BEGIN;\n' + plan + '\nROLLBACK;\n');
