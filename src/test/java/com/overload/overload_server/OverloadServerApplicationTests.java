package com.overload.overload_server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.session.SessionRepository;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude="
				+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
@MockitoBean(types = {JdbcTemplate.class, PlatformTransactionManager.class, SessionRepository.class,
		com.overload.overload_server.workoutsession.WorkoutRecordService.class,
		com.overload.overload_server.workoutsession.WorkoutSnapshotService.class, com.overload.overload_server.workoutsession.WorkoutSnapshotRepository.class, com.overload.overload_server.workoutsession.WorkoutSessionService.class, com.overload.overload_server.workoutsession.WorkoutSessionRepository.class,
		com.overload.overload_server.workout.WorkoutReadService.class,
		com.overload.overload_server.catalog.CatalogReadService.class,
		com.overload.overload_server.routine.RoutineService.class, com.overload.overload_server.routine.RoutineRepository.class, com.overload.overload_server.workout.WorkoutWriteService.class})
class OverloadServerApplicationTests {

	@Test
	void contextLoads() {
	}

}
