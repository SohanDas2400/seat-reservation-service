package com.seatreserve.util;

import org.springframework.dao.TransientDataAccessException;

import java.util.function.Supplier;

/**
 * Bounded retry for transient DB failures (deadlock victim, lock-wait timeout). These are expected
 * under heavy contention; InnoDB aborts one transaction of a deadlock pair and we simply retry it.
 * Deterministic outcomes (ConflictException, DuplicateKeyException, ...) are NOT transient and pass
 * straight through without retry.
 */
public final class Retries {

    private Retries() {
    }

    public static <T> T withRetry(int maxAttempts, Supplier<T> op) {
        int attempt = 0;
        while (true) {
            try {
                return op.get();
            } catch (TransientDataAccessException ex) {
                if (++attempt >= maxAttempts) {
                    throw ex;
                }
                try {
                    Thread.sleep(Math.min(10L * attempt, 100));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }
}
