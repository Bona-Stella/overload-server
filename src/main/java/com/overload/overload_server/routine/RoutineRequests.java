package com.overload.overload_server.routine;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Local, strict HTTP boundary; does not change the existing authentication/workout JSON mapper. */
public final class RoutineRequests {
	private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
	private RoutineRequests() { }
	public enum Mode { NEW, EXISTING, RESTORE }
	/** Reuses the existing closed CardioPlan contract without changing the routine HTTP boundary. */
	public static Optional<JsonNode> canonicalCardioPlan(JsonNode value) {
		try {
			var p = cardioPlan(value);
			return Optional.of(JSON.valueToTree(new RoutineResponses.Plan(p.kind(),p.name(),p.targetSeconds(),RoutineResponses.decimal(p.targetDistanceKm()),
					p.intensityMode()==null?null:new RoutineResponses.Intensity(p.intensityMode(),RoutineResponses.decimal(p.intensityValue())),
					RoutineResponses.decimal(p.inclinePercent()),RoutineResponses.decimal(p.resistance()))));
		} catch (RoutineFailure failure) { return Optional.empty(); }
	}
	public record Weight(Mode mode, UUID id, UUID standardExerciseId, UUID manufacturerId, String loadType,
			int setCount, int minReps, int maxReps, BigDecimal initialWeightKg) { }
	public record Plan(String kind, String name, int targetSeconds, BigDecimal targetDistanceKm,
			String intensityMode, BigDecimal intensityValue, BigDecimal inclinePercent, BigDecimal resistance) { }
	public record Cardio(Mode mode, UUID id, Plan plan) { }
	public record Approval(UUID placementId, BigDecimal nextApprovedWeightKg, Instant occurredAt) { }
	public record Create(UUID id, long dataGeneration, String name, List<Weight> weightPlacements, List<Cardio> cardioPlacements) { }
	public record Update(long dataGeneration, long expectedVersion, String name, List<Weight> weightPlacements,
			List<Cardio> cardioPlacements, List<Approval> weightApprovals) { }
	public record Delete(long dataGeneration, long expectedVersion) { }

	public static Create create(String raw) {
		var body = parse(raw);
		fields(body, "id", "dataGeneration", "name", "weightPlacements", "cardioPlacements");
		var weights = weights(body, true); var cardio = cardio(body, true); composition(weights, cardio);
		return new Create(uuid(text(body, "id")), counter(body, "dataGeneration"), name(body, "name"), weights, cardio);
	}
	public static Update update(String raw) {
		var body = parse(raw);
		fields(body, "dataGeneration", "expectedVersion", "name", "weightPlacements", "cardioPlacements", "weightApprovals");
		var weights = weights(body, false); var cardio = cardio(body, false); composition(weights, cardio);
		var approvals = new ArrayList<Approval>(); var seen = new HashSet<UUID>();
		for (var item : array(body, "weightApprovals")) {
			fields(item, "placementId", "nextApprovedWeightKg", "occurredAt");
			UUID id = uuid(text(item, "placementId"));
			if (!seen.add(id) || weights.stream().noneMatch(weight -> weight.id().equals(id) && weight.mode() != Mode.NEW)) throw invalid();
			String time = text(item, "occurredAt");
			if (!time.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,3})?Z") || time.startsWith("0000")) throw invalid();
			try { approvals.add(new Approval(id, decimal(item, "nextApprovedWeightKg"), Instant.parse(time))); }
			catch (java.time.DateTimeException failure) { throw invalid(); }
		}
		return new Update(counter(body, "dataGeneration"), counter(body, "expectedVersion"), name(body, "name"), weights, cardio, List.copyOf(approvals));
	}
	public static Delete delete(String raw) {
		var body = parse(raw); fields(body, "dataGeneration", "expectedVersion");
		return new Delete(counter(body, "dataGeneration"), counter(body, "expectedVersion"));
	}
	public static UUID uuid(String value) {
		try {
			UUID id = UUID.fromString(value);
			if (!id.toString().equalsIgnoreCase(value)) throw invalid();
			return id;
		} catch (IllegalArgumentException failure) { throw invalid(); }
	}
	private static JsonNode parse(String raw) {
		try { return JSON.readTree(raw); }
		catch (tools.jackson.core.JacksonException failure) { throw invalid(); }
	}
	private static void fields(JsonNode node, String... required) {
		if (node == null || !node.isObject() || node.size() != required.length) throw invalid();
		var names = Set.of(required);
		for (String field : node.propertyNames()) if (!names.contains(field)) throw invalid();
	}
	private static Iterable<JsonNode> array(JsonNode node, String field) {
		var value = node.get(field); if (value == null || !value.isArray()) throw invalid(); return value;
	}
	private static String text(JsonNode node, String field) {
		var value = node.get(field); if (value == null || !value.isString()) throw invalid(); return value.asString();
	}
	private static String name(JsonNode node, String field) {
		String value = text(node, field);
		if (value.isBlank() || value.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw invalid();
		return value;
	}
	private static long counter(JsonNode node, String field) {
		String value = text(node, field);
		if (!value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
		try { return Long.parseLong(value); } catch (NumberFormatException failure) { throw invalid(); }
	}
	private static int count(JsonNode node, String field) {
		var value = node.get(field);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) throw invalid();
		return value.intValue();
	}
	private static BigDecimal decimal(JsonNode node, String field) {
		String value = text(node, field);
		// At most 14 integral and 6 fractional digits. Canonical decimal strings cannot round on persistence.
		if (!value.matches("(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{0,5}[1-9])?")) throw invalid();
		return new BigDecimal(value).stripTrailingZeros();
	}
	private static BigDecimal optionalDecimal(JsonNode node, String field, String low, String high) {
		var value = node.get(field); if (value == null) throw invalid(); if (value.isNull()) return null;
		var result = decimal(node, field);
		if (result.compareTo(new BigDecimal(low)) < 0 || result.compareTo(new BigDecimal(high)) > 0) throw invalid();
		return result;
	}
	private static Mode mode(JsonNode item) {
		try { return Mode.valueOf(text(item, "mode")); } catch (IllegalArgumentException failure) { throw invalid(); }
	}
	private static List<Weight> weights(JsonNode body, boolean create) {
		var result = new ArrayList<Weight>();
		for (var item : array(body, "weightPlacements")) {
			Mode mode = create ? Mode.NEW : mode(item);
			if (create) fields(item, "id", "standardExerciseId", "manufacturerId", "loadType", "setCount", "minReps", "maxReps", "initialWeightKg");
			else if (mode == Mode.NEW) fields(item, "mode", "id", "standardExerciseId", "manufacturerId", "loadType", "setCount", "minReps", "maxReps", "initialWeightKg");
			else fields(item, "mode", "id", "manufacturerId", "loadType", "setCount", "minReps", "maxReps");
			int min = count(item, "minReps"), max = count(item, "maxReps"); if (max < min) throw invalid();
			result.add(new Weight(mode, uuid(text(item, "id")), mode == Mode.NEW ? uuid(text(item, "standardExerciseId")) : null,
					nullableUuid(item, "manufacturerId"), loadType(item),
					count(item, "setCount"), min, max, mode == Mode.NEW ? decimal(item, "initialWeightKg") : null));
		}
		return List.copyOf(result);
	}
	private static UUID nullableUuid(JsonNode item, String field) {
		var value = item.get(field); if (value == null) throw invalid();
		return value.isNull() ? null : uuid(text(item, field));
	}
	private static String loadType(JsonNode item) {
		var value = item.get("loadType"); if (value == null) throw invalid();
		if (value.isNull()) return null;
		String code = text(item, "loadType");
		if (!Set.of("PLATE", "STACK", "DUMBBELL", "BODYWEIGHT").contains(code)) throw invalid();
		return code;
	}
	private static List<Cardio> cardio(JsonNode body, boolean create) {
		var result = new ArrayList<Cardio>();
		for (var item : array(body, "cardioPlacements")) {
			if (create) fields(item, "id", "plan"); else fields(item, "mode", "id", "plan");
			result.add(new Cardio(create ? Mode.NEW : mode(item), uuid(text(item, "id")), cardioPlan(item.get("plan"))));
		}
		return List.copyOf(result);
	}
	private static Plan cardioPlan(JsonNode plan) {
		fields(plan, "kind", "name", "targetSeconds", "targetDistanceKm", "intensity", "inclinePercent", "resistance");
		String kind = text(plan, "kind"), name = name(plan, "name");
		if (!Set.of("TREADMILL", "BIKE", "OUTDOOR", "STAIRS", "OTHER").contains(kind) || name.length() > 100) throw invalid();
		int seconds = count(plan, "targetSeconds"); if (seconds > 86400) throw invalid();
		var distance = optionalDecimal(plan, "targetDistanceKm", "0.001", "1000");
		var incline = optionalDecimal(plan, "inclinePercent", "0", "50");
		var resistance = optionalDecimal(plan, "resistance", "1", "100");
		var intensity = plan.get("intensity"); String intensityMode = null; BigDecimal intensityValue = null;
		if (!intensity.isNull()) {
			fields(intensity, "mode", "value"); intensityMode = text(intensity, "mode");
			if (!Set.of("SPEED", "PACE").contains(intensityMode)) throw invalid();
			intensityValue = optionalDecimal(intensity, "value", "0.1", intensityMode.equals("SPEED") ? "100" : "120");
			if (intensityValue == null) throw invalid();
		}
		if ((!Set.of("TREADMILL", "BIKE", "OUTDOOR").contains(kind) && (distance != null || intensityMode != null))
				|| (!kind.equals("TREADMILL") && incline != null) || (!kind.equals("BIKE") && resistance != null)) throw invalid();
		return new Plan(kind, name, seconds, distance, intensityMode, intensityValue, incline, resistance);
	}
	private static void composition(List<Weight> weights, List<Cardio> cardio) {
		if (weights.isEmpty() && cardio.isEmpty()) throw invalid();
		var seen = new HashSet<UUID>();
		for (var weight : weights) if (!seen.add(weight.id())) throw invalid();
		for (var item : cardio) if (!seen.add(item.id())) throw invalid();
	}
	static RoutineFailure invalid() { return new RoutineFailure(RoutineFailure.Reason.INVALID_REQUEST); }
}
