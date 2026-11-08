package com.overload.overload_server.account;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountProvisioningServiceTests {

	private final AccountRepository repository = mock(AccountRepository.class);
	private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
	private final AccountProvisioningService service = new AccountProvisioningService(repository, manager);
	private final VerifiedIdentity identity = VerifiedIdentity.email("account@example.com");

	@Test
	void losingClaimDoesNotSeedEitherAccount() {
		var loser = new Account(UUID.randomUUID(), 0, null);
		var winner = new Account(UUID.randomUUID(), 0, null);
		var losingTransaction = new SimpleTransactionStatus();
		when(manager.getTransaction(any())).thenReturn(losingTransaction, new SimpleTransactionStatus());
		when(repository.findByIdentity(identity)).thenReturn(Optional.empty(), Optional.of(winner));
		when(repository.createUser(null)).thenReturn(loser);
		when(repository.claimIdentity(loser, identity)).thenReturn(false);

		assertThat(service.provision(identity, null)).isEqualTo(winner);
		assertThat(losingTransaction.isRollbackOnly()).isTrue();
		verify(repository, never()).insertInitialWorkoutData(any());
	}

	@Test
	void seedFailureRollsBackTheProvisioningTransaction() {
		var account = new Account(UUID.randomUUID(), 0, null);
		var transaction = new SimpleTransactionStatus();
		var failure = new DataIntegrityViolationException("seed failed");
		when(manager.getTransaction(any())).thenReturn(transaction);
		when(repository.findByIdentity(identity)).thenReturn(Optional.empty());
		when(repository.createUser(null)).thenReturn(account);
		when(repository.claimIdentity(account, identity)).thenReturn(true);
		doThrow(failure).when(repository).insertInitialWorkoutData(account);

		assertThatThrownBy(() -> service.provision(identity, null)).isSameAs(failure);
		verify(manager).rollback(transaction);
		verify(manager, never()).commit(any());
	}
}
