package com.overload.overload_server.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.overload.overload_server.deload.DeloadAnalysisRepository;

import java.util.Optional;
import java.util.UUID;

@Repository
class AccountRepository {

	private final JdbcTemplate jdbc;
	private final DeloadAnalysisRepository analysis;

	AccountRepository(JdbcTemplate jdbc, DeloadAnalysisRepository analysis) {
		this.jdbc = jdbc;
		this.analysis = analysis;
	}

	Optional<Account> findByIdentity(VerifiedIdentity identity) {
		var result=jdbc.query("""
				SELECT u.id, u.data_generation, u.nickname
				FROM users u JOIN auth_identities a ON a.user_id = u.id
				WHERE a.provider = ? AND a.issuer = ? AND a.subject = ?
				FOR UPDATE OF u
				""", (row, index) -> new Account(row.getObject("id", UUID.class),
				row.getLong("data_generation"), row.getString("nickname")),
				identity.provider().databaseValue(), identity.issuer(), identity.subject()).stream().findFirst();
		// A SELECT waiting for the user lock may have seen a credential deleted by unlink.
		// Re-read its ownership in this READ COMMITTED transaction after the lock was acquired.
		return result.filter(account->!jdbc.queryForList("""
				SELECT id FROM auth_identities WHERE user_id=? AND provider=? AND issuer=? AND subject=? FOR SHARE
				""",UUID.class,account.id(),identity.provider().databaseValue(),identity.issuer(),identity.subject()).isEmpty());
	}

	Account createUser(String nickname) {
		var userId = UUID.randomUUID();
		return jdbc.queryForObject("""
				INSERT INTO users (id, nickname) VALUES (?, ?)
				RETURNING id, data_generation, nickname
				""", (row, index) -> new Account(row.getObject("id", UUID.class),
				row.getLong("data_generation"), row.getString("nickname")), userId, nickname);
	}

	boolean claimIdentity(Account account, VerifiedIdentity identity) {
		return jdbc.update("""
				INSERT INTO auth_identities (id, user_id, provider, issuer, subject)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT ON CONSTRAINT auth_identities_credential_unique DO NOTHING
				""", UUID.randomUUID(), account.id(), identity.provider().databaseValue(),
				identity.issuer(), identity.subject()) == 1;
	}

	void insertInitialWorkoutData(Account account) {
		var settings = AccountSeed.WORKOUT_SETTINGS;
		jdbc.update("""
				INSERT INTO user_workout_settings
				(user_id, data_generation, rest_low_seconds, rest_medium_seconds, rest_high_seconds)
				VALUES (?, ?, ?, ?, ?)
				""", account.id(), account.dataGeneration(), settings.restLowSeconds(),
				settings.restMediumSeconds(), settings.restHighSeconds());
		analysis.initializeNewAccount(account.id(), account.dataGeneration());
	}

}
