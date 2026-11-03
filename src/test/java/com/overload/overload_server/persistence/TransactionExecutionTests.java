package com.overload.overload_server.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class TransactionExecutionTests {
    @Test void commitsResultWithTheSuppliedTemplateSettings() {
        var manager = new Manager();
        var template = new TransactionTemplate(manager);
        template.setReadOnly(true);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setTimeout(7);

        assertThat(TransactionExecution.execute(template, () -> "result")).isEqualTo("result");
        assertThat(manager.definition.isReadOnly()).isTrue();
        assertThat(manager.definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(manager.definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(manager.definition.getTimeout()).isEqualTo(7);
        assertThat(manager.commits).isEqualTo(1);
        assertThat(manager.rollbacks).isZero();
    }

    @Test void nullResultStillCommitsBeforeTheCallerInterpretsIt() {
        var manager = new Manager();
        Object result = TransactionExecution.execute(new TransactionTemplate(manager), () -> null);
        assertThat(result).isNull();
        assertThat(manager.commits).isEqualTo(1);
        assertThat(manager.rollbacks).isZero();
    }

    @Test void rollsBackAndRethrowsTheSameApplicationExceptionAndCause() {
        var manager = new Manager();
        var sql = new SQLException("synthetic connection failure", "08006");
        var original = new IllegalStateException("application", sql);
        assertThatThrownBy(() -> TransactionExecution.execute(new TransactionTemplate(manager), () -> { throw original; }))
                .isSameAs(original).hasCause(sql).hasNoSuppressedExceptions();
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(manager.commits).isZero();
    }

    @Test void rollbackFailureRetainsTheOriginalApplicationExceptionAsSuppressed() {
        var manager = new Manager();
        var sql = new SQLException("synthetic connection failure", "08006");
        var original = new IllegalStateException("application", sql);
        var rollback = new TransactionSystemException("rollback", new SQLException("synthetic rollback failure", "08003"));
        manager.rollbackFailure = rollback;
        assertThatThrownBy(() -> TransactionExecution.execute(new TransactionTemplate(manager), () -> { throw original; }))
                .isSameAs(rollback).hasSuppressedException(original);
        assertThat(original.getCause()).isSameAs(sql);
        assertThat(rollback.getApplicationException()).isSameAs(original);
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(manager.commits).isZero();
    }

    @Test void rollbackCanReplaceFailureWithoutSpringApplicationExceptionMetadata() {
        var manager = new Manager();
        var original = new IllegalArgumentException("application");
        var rollback = new IllegalStateException("rollback");
        manager.rollbackFailure = rollback;
        assertThatThrownBy(() -> TransactionExecution.execute(new TransactionTemplate(manager), () -> { throw original; }))
                .isSameAs(rollback).hasSuppressedException(original);
        assertThat(manager.rollbacks).isEqualTo(1);
    }

    @Test void beginFailureDoesNotInvokeTheActionOrInventASuppressedException() {
        var manager = new Manager();
        var failure = new TransactionSystemException("begin");
        manager.beginFailure = failure;
        var called = new AtomicBoolean();
        assertThatThrownBy(() -> TransactionExecution.execute(new TransactionTemplate(manager), () -> { called.set(true); return 1; }))
                .isSameAs(failure).hasNoSuppressedExceptions();
        assertThat(called).isFalse();
        assertThat(manager.commits).isZero();
        assertThat(manager.rollbacks).isZero();
    }

    @Test void commitFailureIsPropagatedWithoutInventingAnApplicationFailure() {
        var manager = new Manager();
        var failure = new TransactionSystemException("commit");
        manager.commitFailure = failure;
        assertThatThrownBy(() -> TransactionExecution.execute(new TransactionTemplate(manager), () -> "result"))
                .isSameAs(failure).hasNoSuppressedExceptions();
        assertThat(manager.commits).isEqualTo(1);
    }

    @Test void errorsStillUseTheSpringRollbackPath() {
        var manager = new Manager();
        var failure = new AssertionError("application error");
        assertThatThrownBy(() -> TransactionExecution.execute(new TransactionTemplate(manager), () -> { throw failure; }))
                .isSameAs(failure).hasNoSuppressedExceptions();
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(manager.commits).isZero();
    }

    private static class Manager extends AbstractPlatformTransactionManager {
        TransactionDefinition definition;
        RuntimeException beginFailure, commitFailure, rollbackFailure;
        int commits, rollbacks;

        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            this.definition = definition;
            if (beginFailure != null) throw beginFailure;
        }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            commits++;
            if (commitFailure != null) throw commitFailure;
        }
        @Override protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
            if (rollbackFailure != null) throw rollbackFailure;
        }
    }
}
