package com.overload.overload_server.persistence;

import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Executes an existing template without losing an application failure if rollback also fails. */
public final class TransactionExecution {
    private TransactionExecution() { }

    public static <T> T execute(TransactionTemplate template, Supplier<T> action) {
        var original = new AtomicReference<RuntimeException>();
        try {
            return template.execute(status -> {
                try { return action.get(); }
                catch (RuntimeException failure) { original.set(failure); throw failure; }
            });
        } catch (RuntimeException failure) {
            if (original.get() != null && original.get() != failure) failure.addSuppressed(original.get());
            throw failure;
        }
    }
}
