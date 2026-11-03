package com.overload.testfixture;

import com.overload.overload_server.workoutsettings.UserWorkoutSettings;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

public final class MixedPersistenceAssertions {

	private MixedPersistenceAssertions() { }

	public static void verify(ConfigurableApplicationContext app) {
		var dataSource = app.getBean(DataSource.class);
		assertThat(app.getBeansOfType(DataSource.class)).hasSize(1);
		assertThat(app.getBeansOfType(PlatformTransactionManager.class)).containsOnlyKeys("transactionManager", "workoutTransactionManager");
		var jdbc = app.getBean("transactionManager", JdbcTransactionManager.class);
		var jpa = app.getBean("workoutTransactionManager", JpaTransactionManager.class);
		assertThat(app.getBean(PlatformTransactionManager.class)).isSameAs(jdbc);
		assertThat(jdbc.getDataSource()).isSameAs(dataSource);
		assertThat(jpa.getDataSource()).isSameAs(dataSource);
		var factory = app.getBean(EntityManagerFactory.class);
		assertThat(jpa.getEntityManagerFactory()).isSameAs(factory);
		assertThat(((EntityManagerFactoryInfo) factory).getDataSource()).isSameAs(dataSource);
		assertThat(factory.getMetamodel().getEntities()).extracting(entity -> entity.getJavaType().getName())
				.containsExactlyInAnyOrder(UserWorkoutSettings.class.getName(), com.overload.overload_server.routine.Routine.class.getName(), com.overload.overload_server.routine.RoutineWeightPlacement.class.getName(), com.overload.overload_server.routine.RoutineCardioPlacement.class.getName(), com.overload.overload_server.routine.ProgressChangeEvent.class.getName(), com.overload.overload_server.workoutsession.WorkoutSession.class.getName(), "com.overload.overload_server.workoutsession.SessionExercise", "com.overload.overload_server.workoutsession.PerformedSet", "com.overload.overload_server.workoutsession.CardioResult", "com.overload.overload_server.workoutsession.WeightApprovalRequest", "com.overload.overload_server.workoutsession.RecordChangeEvent",
                        "com.overload.overload_server.catalog.StandardExercise", "com.overload.overload_server.catalog.ExerciseAlias",
                        "com.overload.overload_server.catalog.Muscle", "com.overload.overload_server.catalog.ExerciseMuscle", "com.overload.overload_server.catalog.Manufacturer");
		assertThat(app.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
		assertThat(app.getEnvironment().getProperty("spring.jpa.open-in-view")).isEqualTo("false");
		assertThat(app.getEnvironment().getPropertySources()).noneMatch(source -> source.getName().contains(".env"));
	}
}
