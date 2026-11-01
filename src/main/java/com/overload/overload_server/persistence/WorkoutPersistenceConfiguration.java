package com.overload.overload_server.persistence;

import com.overload.overload_server.exercise.ExerciseDefinitionRepository;
import com.overload.overload_server.catalog.StandardExerciseRepository;
import com.overload.overload_server.workoutsettings.UserWorkoutSettingsRepository;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.session.jdbc.config.annotation.SpringSessionTransactionManager;

import javax.sql.DataSource;

/** Select managers explicitly before Boot supplies either transaction-manager default. */
@AutoConfiguration(after = DataSourceAutoConfiguration.class, before = {
		DataSourceTransactionManagerAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
		DataJpaRepositoriesAutoConfiguration.class})
@ConditionalOnSingleCandidate(DataSource.class)
@EnableJpaRepositories(basePackageClasses = {ExerciseDefinitionRepository.class, UserWorkoutSettingsRepository.class, StandardExerciseRepository.class},
		transactionManagerRef = "workoutTransactionManager")
public class WorkoutPersistenceConfiguration {

	@Bean
	@Primary
	@SpringSessionTransactionManager
	DataSourceTransactionManager transactionManager(DataSource dataSource, Environment environment,
			ObjectProvider<TransactionManagerCustomizers> customizers) {
		// Preserve Boot's existing JDBC exception-translation switch as well as its default.
		var manager = environment.getProperty("spring.dao.exceptiontranslation.enabled", Boolean.class, true)
				? new JdbcTransactionManager(dataSource) : new DataSourceTransactionManager(dataSource);
		customizers.ifAvailable(value -> value.customize(manager));
		return manager;
	}

	@Bean
	JpaTransactionManager workoutTransactionManager(EntityManagerFactory entityManagerFactory, DataSource dataSource,
			ObjectProvider<TransactionManagerCustomizers> customizers) {
		var manager = new JpaTransactionManager(entityManagerFactory);
		manager.setDataSource(dataSource);
		customizers.ifAvailable(value -> value.customize(manager));
		return manager;
	}
}
