package com.flashsale.order.reconciliation;

import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A lease lock in the job_locks table (step 2.6): "this job belongs to me until locked_until". The same idea as
 * ShedLock, in two SQL statements.
 *
 * <ul>
 *   <li>Acquire: insert the row, or take it over if the current lease has run out. One statement, so two instances
 *       can't both win: Postgres locks the row for the conflicting INSERT/UPDATE and re-checks the WHERE.</li>
 *   <li>Crash safety: nobody has to release a dead instance's lock; its lease simply expires.</li>
 *   <li>Clock: now() is the database's clock, so instances with skewed clocks still agree.</li>
 *   <li>No transaction is held while the job runs (unlike a row lock or an advisory transaction lock).</li>
 * </ul>
 */
@Component
public class JobLock {

    private final JdbcClient jdbc;
    /** Who holds a lock: unique per running instance (shown in job_locks.locked_by). */
    private final String owner;

    JobLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.owner = hostName() + "/" + UUID.randomUUID();
    }

    /** @return true if this instance now holds {@code name} for {@code lease} (measured on the DB clock) */
    public boolean tryAcquire(String name, Duration lease) {
        return jdbc.sql("""
                INSERT INTO job_locks (name, locked_until, locked_by)
                VALUES (:name, now() + :leaseMillis * interval '1 millisecond', :owner)
                ON CONFLICT (name) DO UPDATE
                    SET locked_until = EXCLUDED.locked_until, locked_by = EXCLUDED.locked_by
                    WHERE job_locks.locked_until < now()
                """)
                .param("name", name)
                .param("leaseMillis", lease.toMillis())
                .param("owner", owner)
                .update() == 1;
    }

    /** Ends our lease early; does nothing if the lease already expired and someone else took it. */
    public void release(String name) {
        jdbc.sql("UPDATE job_locks SET locked_until = now() WHERE name = :name AND locked_by = :owner")
                .param("name", name)
                .param("owner", owner)
                .update();
    }

    String owner() {
        return owner;
    }

    private static String hostName() {
        String host = System.getenv("HOSTNAME"); // set in containers
        return host == null || host.isBlank() ? "local" : host;
    }
}
