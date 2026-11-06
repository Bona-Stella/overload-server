package com.overload.overload_server.routine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class RoutineRequestsTests {
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings={"PLATE","STACK","DUMBBELL","BODYWEIGHT"})
	void acceptsOnlyNewEquipmentCodes(String code) {
		var input=weight(); input.put("loadType",code); input.put("manufacturerId",ID);
		var result=RoutineRequests.create(json(create(List.of(input),List.of()))).weightPlacements().getFirst();
		assertThat(result.loadType()).isEqualTo(code);assertThat(result.manufacturerId()).isEqualTo(UUID.fromString(ID));
	}
	@Test void equipmentKeysAreRequiredNullableAndOldIdentityOrLoadCodesAreRejected() {
		for(String field:List.of("manufacturerId","loadType")) {
			var input=weight();input.remove(field);
			assertThatThrownBy(()->RoutineRequests.create(json(create(List.of(input),List.of())))).isInstanceOf(RoutineFailure.class);
		}
		for(Object value:List.of("FREE_WEIGHT","PLATE_LOADED","WEIGHT_STACK","NULL","UNKNOWN","plate",1,true)) {
			var input=weight();input.put("loadType",value);
			assertThatThrownBy(()->RoutineRequests.create(json(create(List.of(input),List.of())))).isInstanceOf(RoutineFailure.class);
		}
		var input=weight();input.put("exerciseDefinitionId",input.remove("standardExerciseId"));
		assertThatThrownBy(()->RoutineRequests.create(json(create(List.of(input),List.of())))).isInstanceOf(RoutineFailure.class);
	}
	static final JsonMapper JSON = JsonMapper.builder().build();
	static final String ID = "00000000-0000-4000-8000-000000000001";
	static final String EXERCISE = "00000000-0000-4000-8000-000000000002";
	static Map<String, Object> weight() { var value = new LinkedHashMap<String,Object>(Map.of("id", ID, "standardExerciseId", EXERCISE, "setCount", 3, "minReps", 6, "maxReps", 10, "initialWeightKg", "61.3")); value.put("manufacturerId", null); value.put("loadType", null); return value; }
	static Map<String, Object> plan() {
		var value = new LinkedHashMap<String, Object>(); value.put("kind", "TREADMILL"); value.put("name", "러닝머신"); value.put("targetSeconds", 86400);
		value.put("targetDistanceKm", "1000"); value.put("intensity", Map.of("mode", "PACE", "value", "120")); value.put("inclinePercent", "50"); value.put("resistance", null); return value;
	}
	static Map<String, Object> create(Object weight, Object cardio) { return Map.of("id", UUID.randomUUID(), "dataGeneration", "0", "name", "  Ω 루틴  ", "weightPlacements", weight, "cardioPlacements", cardio); }
	static String json(Object value) { return JSON.writeValueAsString(value); }
	static Stream<Arguments> invalidWeights() {
		var cases = new ArrayList<Arguments>();
		for (Object value : Arrays.asList("-1", "-0", "+1", "01", "1e3", "NaN", "Infinity", "0.0000001", "100000000000000", "60.0", " 60", "", 60, true, null)) cases.add(Arguments.of("initialWeightKg", value));
		for (String field : List.of("setCount", "minReps", "maxReps")) for (Object value : Arrays.asList(0, -1, 1.5, 1.0, 2147483648L, "3", true, null)) cases.add(Arguments.of(field, value));
		cases.add(Arguments.of("minReps", 11));
		return cases.stream();
	}
	@ParameterizedTest @MethodSource("invalidWeights")
	void rejectsLossyDecimalsAndInvalidCounts(String field, Object value) {
		var weight = weight(); weight.put(field, value);
		assertThatThrownBy(() -> RoutineRequests.create(json(create(List.of(weight), List.of())))).isInstanceOf(RoutineFailure.class)
				.satisfies(failure -> assertThat(((RoutineFailure) failure).reason).isEqualTo(RoutineFailure.Reason.INVALID_REQUEST));
	}
	static Stream<Arguments> invalidCardio() {
		return Stream.of(Arguments.of("targetSeconds", 0), Arguments.of("targetSeconds", 86401), Arguments.of("targetSeconds", 1.0),
				Arguments.of("targetDistanceKm", "0"), Arguments.of("targetDistanceKm", "1000.000001"), Arguments.of("targetDistanceKm", "0.000999"),
				Arguments.of("targetDistanceKm", "0.0010001"), Arguments.of("inclinePercent", "50.000001"), Arguments.of("resistance", "1"),
				Arguments.of("kind", "STAIRS"), Arguments.of("kind", "treadmill"), Arguments.of("intensity", Map.of("mode", "SPEED", "value", "100.000001")),
				Arguments.of("intensity", Map.of("mode", "PACE", "value", "0.099999")), Arguments.of("name", " "), Arguments.of("name", "x".repeat(101)),
				Arguments.of("intensity", Map.of("mode", "SPEED", "value", 5)));
	}
	@ParameterizedTest @MethodSource("invalidCardio")
	void rejectsCardioRangeAndKindIncompatibilities(String field, Object value) {
		var plan = plan(); plan.put(field, value);
		assertThatThrownBy(() -> RoutineRequests.create(json(create(List.of(), List.of(Map.of("id", ID, "plan", plan)))))).isInstanceOf(RoutineFailure.class);
	}
	@Test void acceptsExactLimitsNamesAndBikeFractionalResistanceWithoutCoercion() {
		var weight = weight(); weight.put("setCount", Integer.MAX_VALUE); weight.put("minReps", Integer.MAX_VALUE); weight.put("maxReps", Integer.MAX_VALUE); weight.put("initialWeightKg", "99999999999999.999999");
		var result = RoutineRequests.create(json(create(List.of(weight), List.of())));
		assertThat(result.name()).isEqualTo("  Ω 루틴  "); assertThat(result.weightPlacements().getFirst().setCount()).isEqualTo(Integer.MAX_VALUE);
		var plan = plan(); plan.put("kind", "BIKE"); plan.put("inclinePercent", null); plan.put("resistance", "1.000001");
		assertThat(RoutineRequests.create(json(create(List.of(), List.of(Map.of("id", ID, "plan", plan))))).cardioPlacements().getFirst().plan().resistance()).isEqualByComparingTo("1.000001");
	}
	@Test void rejectsEmptyCompositionDuplicateIdsInvalidUnicodeAndDuplicateJsonKeys() {
		assertThatThrownBy(() -> RoutineRequests.create(json(create(List.of(), List.of())))).isInstanceOf(RoutineFailure.class);
		assertThatThrownBy(() -> RoutineRequests.create(json(create(List.of(weight(), weight()), List.of())))).isInstanceOf(RoutineFailure.class);
		String valid = json(create(List.of(weight()), List.of()));
		for (String invalid : List.of("null", "[]", "{bad", valid.replace("\"name\":", "\"name\":\"duplicate\",\"name\":"),
				valid.replace("  Ω 루틴  ", "\\uD800"), valid.replace("  Ω 루틴  ", "\\u0000"))) {
			assertThatThrownBy(() -> RoutineRequests.create(invalid)).isInstanceOf(RoutineFailure.class);
		}
	}

 @Test void tagIsOptionalNullableAndRestrictedToTheConfirmedTenValues(){
   var body=new LinkedHashMap<String,Object>(create(List.of(weight()),List.of()));
   assertThat(RoutineRequests.create(json(body)).tag()).isNull();
   for(String tag:List.of("PUSH","PULL","LEGS","FRONT","BACK","FULL","UPPER","LOWER","CARDIO","MIXED")){body.put("tag",tag);assertThat(RoutineRequests.create(json(body)).tag()).isEqualTo(tag);}
   body.put("tag",null);assertThat(RoutineRequests.create(json(body)).tag()).isNull();
   for(Object invalid:List.of("ANTERIOR","POSTERIOR","push","",1,true)){body.put("tag",invalid);assertThatThrownBy(()->RoutineRequests.create(json(body))).isInstanceOf(RoutineFailure.class);}
 }
 @Test void optionalCatalogIdentityPreservesOldCanonicalCardioJson(){
   var old=JSON.valueToTree(plan());var canonical=RoutineRequests.canonicalCardioPlan(old).orElseThrow();assertThat(canonical.has("activityId")).isFalse();
   var withId=plan();withId.put("activityId",ID);assertThat(RoutineRequests.canonicalCardioPlan(JSON.valueToTree(withId)).orElseThrow().get("activityId").asString()).isEqualTo(ID);
   withId.put("activityId",null);assertThat(RoutineRequests.canonicalCardioPlan(JSON.valueToTree(withId))).isEmpty();
 }
}
