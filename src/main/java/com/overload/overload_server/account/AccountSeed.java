package com.overload.overload_server.account;

import java.util.List;

/** Operational rest defaults. Historical personal names are retained for internal fixtures, never provisioned. */
public final class AccountSeed {

	public static final List<String> WEIGHT_EXERCISE_NAMES = List.of(
			"벤치프레스", "인클라인 덤벨 프레스", "체스트 프레스", "케이블 플라이",
			"랫풀다운", "시티드 케이블 로우", "바벨 로우", "풀업",
			"스쿼트", "레그 프레스", "레그 익스텐션", "레그 컬", "루마니안 데드리프트",
			"숄더 프레스", "사이드 레터럴 레이즈", "리어 델트 플라이",
			"바벨 컬", "덤벨 컬", "케이블 푸시다운", "오버헤드 트라이셉스 익스텐션");

	public static final WorkoutSettings WORKOUT_SETTINGS = new WorkoutSettings(180, 120, 90);

	private AccountSeed() {
	}

}
