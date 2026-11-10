package com.overload.overload_server.catalog;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Parses and analyzes the original V1 export without contacting the source. */
public final class ExerciseDbV1 {
    static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Pattern SOURCE_ID = Pattern.compile("[A-Za-z0-9]{7}");
    private static final Pattern VARIANT = Pattern.compile(
            "\\s*(?:\\((?:front|back|side) pov\\)|\\((?:male|female)\\)|\\bv(?:ersion)?\\.?\\s*[0-9]+)$");
    private static final Pattern REPS = Pattern.compile("repeat.{0,70}\\b(?:reps|repetitions)\\b");
    private static final Pattern HOLD = Pattern.compile("\\bhold\\b.{0,65}\\b(?:seconds|minutes|as long as|desired duration)\\b");
    private static final Pattern CARDIO = Pattern.compile("\\b(?:run|running|walk|walking|jog|jogging|pedal|pedaling|cycle|cycling|treadmill|elliptical|stepmill|skierg)\\b");
    private static final Pattern HOLD_NAME = Pattern.compile("\\b(?:plank|hold|pose|yoga)\\b");
    private static final Pattern STRETCH_NAME = Pattern.compile("\\bstretch(?:es|ing)?\\b");

    private ExerciseDbV1() { }

    public enum Flag {
        EXACT_NAME, NORMALIZED_NAME, IDENTICAL_INSTRUCTIONS, IDENTICAL_INSTRUCTIONS_DIFFERENT_NAME,
        VARIANT_NAME, NAME_INSTRUCTION_DISAGREEMENT, PEER_MUSCLE_DISAGREEMENT, SOURCE_ROLE_OVERLAP, MUSCLE_DETAIL_REQUIRED, UNMAPPED_MUSCLE,
        CARDIO_TAG, HOLD_NAME_CUE, STRETCH_NAME_CUE, TYPE_INSTRUCTION_REVIEW
    }
    public enum Match { DIRECT_CANDIDATE, DETAIL_REQUIRED, UNMAPPED }

    public record MuscleHint(String sourceTerm, Match match, List<UUID> possibleMuscleIds) { }
    public record Findings(Set<Flag> flags, Map<Flag, List<String>> peers, List<MuscleHint> primaryHints,
                           List<MuscleHint> secondaryHints, StandardExercise.ExerciseType typeHint,
                           StandardExercise.RecordMethod recordMethodHint) { }
    public record GroupCount(int groups, int rows) { }
    public record Summary(int rows, int uniqueIds, int normalizedNames, int distinctGifUrls, int gifUrlsMatchingId,
                          int equipmentTerms, int muscleTerms, Map<Flag, GroupCount> groups, Map<Flag, Integer> flaggedRows,
                          Map<StandardExercise.ExerciseType, Integer> typeHints,
                          Map<StandardExercise.RecordMethod, Integer> recordMethodHints) { }
    public record Candidate(String sourceId, int position, String name, String gifUrl, List<String> equipment,
                            List<String> bodyParts, List<String> primary, List<String> secondary, List<String> instructions,
                            JsonNode original, Findings findings) {
        @Override public JsonNode original() { return original.deepCopy(); }
    }

    /** Only the parser can construct a validated batch. */
    public static final class Batch {
        private final String sha256;
        private final List<Candidate> rows;
        private final Summary summary;

        private Batch(String sha256, List<Candidate> rows, Summary summary) {
            this.sha256 = sha256;
            this.rows = List.copyOf(rows);
            this.summary = summary;
        }

        public String sha256() { return sha256; }
        public List<Candidate> rows() { return rows; }
        public Summary summary() { return summary; }
    }

    private record Original(String id, String name, String gifUrl, List<String> equipment, List<String> bodyParts,
                            List<String> primary, List<String> secondary, List<String> steps, JsonNode json) { }

    public static Batch parse(byte[] file, int expectedCount, String expectedSha256) {
        String sha256 = digest(file);
        if (expectedSha256 != null && !sha256.equals(expectedSha256)) {
            throw new IllegalArgumentException("ExerciseDB source SHA-256 does not match");
        }
        var data = JSON.readTree(file);
        if (!data.isArray() || data.size() != expectedCount || expectedCount <= 0) {
            throw new IllegalArgumentException("ExerciseDB source must be an array of " + expectedCount + " rows");
        }
        var rows = new ArrayList<Original>();
        var ids = new HashSet<String>();
        int position = 0;
        for (var row : data) {
            position++;
            try {
                var original = parseRow(row);
                if (!ids.add(original.id())) throw new IllegalArgumentException("Duplicate exerciseId " + original.id());
                rows.add(original);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Invalid ExerciseDB source row " + position + ": " + failure.getMessage(), failure);
            }
        }
        return analyze(sha256, rows);
    }

    private static Original parseRow(JsonNode row) {
        if (!row.isObject()) throw new IllegalArgumentException("Expected an object");
        String id = text(row.get("exerciseId"), "exerciseId");
        if (!SOURCE_ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid exerciseId");
        String gifUrl = text(row.get("gifUrl"), "gifUrl");
        var uri = URI.create(gifUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Expected an HTTPS GIF URL");
        }
        return new Original(id, text(row.get("name"), "name"), gifUrl, strings(row, "equipments", false),
                strings(row, "bodyParts", false), strings(row, "targetMuscles", false),
                strings(row, "secondaryMuscles", true), strings(row, "instructions", false), row);
    }

    private static List<String> strings(JsonNode row, String field, boolean emptyAllowed) {
        var values = row.get(field);
        if (values == null || !values.isArray() || !emptyAllowed && values.isEmpty()) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        var items = new ArrayList<String>();
        values.forEach(value -> items.add(text(value, field)));
        return List.copyOf(items);
    }

    private static String text(JsonNode value, String field) {
        if (value == null || !value.isString() || !validText(value.asString())) throw new IllegalArgumentException("Invalid " + field);
        return value.asString();
    }

    static boolean validText(String value) {
        return value != null && !value.isBlank() && value.indexOf('\0') < 0;
    }

    private static Batch analyze(String sha256, List<Original> rows) {
        var peers = new HashMap<String, EnumMap<Flag, List<String>>>();
        var groups = new EnumMap<Flag, GroupCount>(Flag.class);
        group(rows, Original::name, Flag.EXACT_NAME, groups, peers);
        group(rows, row -> normalize(row.name()), Flag.NORMALIZED_NAME, groups, peers);
        group(rows, Original::steps, Flag.IDENTICAL_INSTRUCTIONS, groups, peers);
        group(rows, Original::steps, Flag.IDENTICAL_INSTRUCTIONS_DIFFERENT_NAME, groups, peers);
        group(rows, row -> variantBase(row.name()), Flag.VARIANT_NAME, groups, peers);
        var byId = new HashMap<String, Original>();
        rows.forEach(row -> byId.put(row.id(), row));
        var candidates = new ArrayList<Candidate>();
        var counts = new EnumMap<Flag, Integer>(Flag.class);
        var typeHints = new EnumMap<StandardExercise.ExerciseType, Integer>(StandardExercise.ExerciseType.class);
        var methodHints = new EnumMap<StandardExercise.RecordMethod, Integer>(StandardExercise.RecordMethod.class);
        var equipment = new HashSet<String>();
        var muscles = new HashSet<String>();
        var urls = new HashSet<String>();
        int matchesId = 0;
        for (var row : rows) {
            var related = peers.getOrDefault(row.id(), new EnumMap<>(Flag.class));
            var differentSteps = related.getOrDefault(Flag.NORMALIZED_NAME, List.of()).stream()
                    .filter(id -> !row.steps().equals(byId.get(id).steps())).toList();
            if (!differentSteps.isEmpty()) related.put(Flag.NAME_INSTRUCTION_DISAGREEMENT, differentSteps);
            var disagreement = related.values().stream().flatMap(List::stream).distinct()
                    .filter(id -> !sameMuscles(row, byId.get(id))).sorted().toList();
            if (!disagreement.isEmpty()) related.put(Flag.PEER_MUSCLE_DISAGREEMENT, disagreement);
            var findings = findings(row, related);
            findings.flags().forEach(flag -> counts.merge(flag, 1, Integer::sum));
            typeHints.merge(findings.typeHint(), 1, Integer::sum);
            methodHints.merge(findings.recordMethodHint(), 1, Integer::sum);
            equipment.addAll(row.equipment());
            muscles.addAll(row.primary());
            muscles.addAll(row.secondary());
            urls.add(row.gifUrl());
            if (URI.create(row.gifUrl()).getPath().endsWith("/" + row.id() + ".gif")) matchesId++;
            candidates.add(new Candidate(row.id(), candidates.size() + 1, row.name(), row.gifUrl(), row.equipment(),
                    row.bodyParts(), row.primary(), row.secondary(), row.steps(), row.json().deepCopy(), findings));
        }
        int normalizedNames = (int) rows.stream().map(row -> normalize(row.name())).distinct().count();
        return new Batch(sha256, candidates, new Summary(rows.size(), byId.size(), normalizedNames, urls.size(),
                matchesId, equipment.size(), muscles.size(), Map.copyOf(groups), Map.copyOf(counts),
                Map.copyOf(typeHints), Map.copyOf(methodHints)));
    }

    private static <T> void group(List<Original> rows, Function<Original, T> key, Flag flag, Map<Flag, GroupCount> counts,
                                  Map<String, EnumMap<Flag, List<String>>> peers) {
        var grouped = new LinkedHashMap<T, List<Original>>();
        for (var row : rows) grouped.computeIfAbsent(key.apply(row), ignored -> new ArrayList<>()).add(row);
        int groupCount = 0, rowCount = 0;
        for (var members : grouped.values()) {
            boolean differentNames = flag == Flag.VARIANT_NAME || flag == Flag.IDENTICAL_INSTRUCTIONS_DIFFERENT_NAME;
            if (members.size() < 2 || differentNames
                    && members.stream().map(row -> normalize(row.name())).distinct().count() < 2) continue;
            groupCount++;
            rowCount += members.size();
            for (var member : members) {
                var related = members.stream().filter(peer -> !peer.id().equals(member.id()))
                        .filter(peer -> flag != Flag.IDENTICAL_INSTRUCTIONS_DIFFERENT_NAME
                                || !normalize(peer.name()).equals(normalize(member.name())))
                        .map(Original::id).sorted().toList();
                peers.computeIfAbsent(member.id(), ignored -> new EnumMap<>(Flag.class))
                        .put(flag, related);
            }
        }
        counts.put(flag, new GroupCount(groupCount, rowCount));
    }

    private static Findings findings(Original row, EnumMap<Flag, List<String>> peers) {
        var flags = EnumSet.noneOf(Flag.class);
        flags.addAll(peers.keySet());
        var primary = row.primary().stream().map(ExerciseDbV1::muscleHint).toList();
        var secondary = row.secondary().stream().map(ExerciseDbV1::muscleHint).toList();
        for (var hints : List.of(primary, secondary)) {
            for (var hint : hints) {
                if (hint.match() == Match.UNMAPPED && !normalize(hint.sourceTerm()).equals("cardiovascular system")) flags.add(Flag.UNMAPPED_MUSCLE);
                if (hint.match() == Match.DETAIL_REQUIRED) flags.add(Flag.MUSCLE_DETAIL_REQUIRED);
            }
        }
        if (!Collections.disjoint(terms(row.primary()), terms(row.secondary()))) flags.add(Flag.SOURCE_ROLE_OVERLAP);
        var name = normalize(row.name());
        var instructions = normalize(String.join(" ", row.steps()));
        boolean cardioTag = terms(row.primary()).contains("cardiovascular system") || terms(row.bodyParts()).contains("cardio");
        boolean stretch = STRETCH_NAME.matcher(name).find(), holdName = HOLD_NAME.matcher(name).find();
        boolean reps = REPS.matcher(instructions).find(), hold = HOLD.matcher(instructions).find();
        boolean cardio = cardioTag && CARDIO.matcher(name + " " + instructions).find();
        if (cardioTag) flags.add(Flag.CARDIO_TAG);
        if (holdName) flags.add(Flag.HOLD_NAME_CUE);
        if (stretch) flags.add(Flag.STRETCH_NAME_CUE);
        if (cardioTag && (!cardio || reps) || holdName && reps && !hold || stretch && reps && !hold
                || name.contains("reverse calf") && instructions.contains("raise your heels")
                || name.contains("inverse leg curl") && instructions.contains("thighs towards your chest")) {
            flags.add(Flag.TYPE_INSTRUCTION_REVIEW);
        }
        var type = StandardExercise.ExerciseType.OTHER;
        var record = StandardExercise.RecordMethod.UNSPECIFIED;
        if (stretch) type = StandardExercise.ExerciseType.STRETCH;
        else if (cardio) type = StandardExercise.ExerciseType.CARDIO;
        else if (holdName && hold && !reps) type = StandardExercise.ExerciseType.STATIC_HOLD;
        else if (reps && !cardioTag && !holdName) type = StandardExercise.ExerciseType.WEIGHT;
        if (!flags.contains(Flag.TYPE_INSTRUCTION_REVIEW)) {
            if (type == StandardExercise.ExerciseType.CARDIO) record = StandardExercise.RecordMethod.CARDIO;
            else if (hold && !reps && (stretch || holdName)) record = StandardExercise.RecordMethod.HOLD_DURATION;
            else if (type == StandardExercise.ExerciseType.WEIGHT) record = StandardExercise.RecordMethod.SETS_REPS;
        }
        return new Findings(Collections.unmodifiableSet(flags), Collections.unmodifiableMap(peers), primary, secondary, type, record);
    }

    private static boolean sameMuscles(Original first, Original second) {
        return terms(first.primary()).equals(terms(second.primary())) && terms(first.secondary()).equals(terms(second.secondary()));
    }

    private static Set<String> terms(List<String> values) {
        var terms = new HashSet<String>();
        values.forEach(value -> terms.add(normalize(value)));
        return terms;
    }

    private static String normalize(String value) { return ExerciseAlias.normalize(value); }

    private static String variantBase(String name) {
        String base = normalize(name);
        while (true) {
            String stripped = VARIANT.matcher(base).replaceFirst("").strip();
            if (stripped.equals(base) || stripped.isEmpty()) return base;
            base = stripped;
        }
    }

    private static MuscleHint muscleHint(String raw) {
        String term = normalize(raw);
        // Positions are the stable V32 seed IDs. Multiple options require a movement-specific decision.
        int[] positions = switch (term) {
            case "pectorals", "chest" -> new int[]{1, 2, 3};
            case "upper chest" -> new int[]{1};
            case "lats", "latissimus dorsi" -> new int[]{5};
            case "rhomboids" -> new int[]{6};
            case "traps", "trapezius" -> new int[]{7, 8, 9};
            case "back" -> new int[]{5, 6, 7, 8, 9, 10, 11};
            case "upper back" -> new int[]{6, 7, 8, 9, 11, 14};
            case "lower back", "spine" -> new int[]{10};
            case "delts", "deltoids" -> new int[]{12, 13, 14};
            case "shoulders" -> new int[]{12, 13, 14, 15, 16, 17, 18, 19};
            case "rear deltoids" -> new int[]{14};
            case "rotator cuff" -> new int[]{15, 16, 17, 18};
            case "serratus anterior" -> new int[]{19};
            case "biceps" -> new int[]{20};
            case "brachialis" -> new int[]{21};
            case "triceps" -> new int[]{23, 24, 25};
            case "forearms" -> new int[]{22, 26, 27};
            case "wrist flexors" -> new int[]{26};
            case "wrist extensors" -> new int[]{27};
            case "quads", "quadriceps" -> new int[]{28};
            case "hamstrings" -> new int[]{29};
            case "glutes" -> new int[]{30, 31, 32};
            case "abductors" -> new int[]{31, 32};
            case "adductors", "groin", "inner thighs" -> new int[]{33};
            case "calves" -> new int[]{34, 35};
            case "soleus" -> new int[]{35};
            case "shins" -> new int[]{36};
            case "hip flexors" -> new int[]{37};
            case "abs", "abdominals", "core" -> new int[]{38, 39, 40};
            case "lower abs" -> new int[]{38};
            case "obliques" -> new int[]{39};
            default -> new int[0];
        };
        var ids = Arrays.stream(positions).mapToObj(position ->
                UUID.fromString(String.format(Locale.ROOT, "d3200000-0000-4000-8000-%012d", position))).toList();
        boolean regional = Set.of("spine", "lower back", "upper chest", "lower abs", "groin", "inner thighs", "shins", "hip flexors").contains(term);
        var match = ids.isEmpty() ? Match.UNMAPPED : ids.size() == 1 && !regional ? Match.DIRECT_CANDIDATE : Match.DETAIL_REQUIRED;
        return new MuscleHint(raw, match, ids);
    }

    private static String digest(byte[] file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
