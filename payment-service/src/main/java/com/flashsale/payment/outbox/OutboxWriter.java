package com.flashsale.payment.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.event.EventEnvelope;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Records "this event must be published" in the caller's transaction. Publishing is OutboxRelay's job. */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    OutboxWriter(JdbcClient jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Must run inside the caller's transaction (fails fast otherwise). An outbox row committed on its own, apart from the
     * business change, would bring the dual-write problem back.
     *
     * @return the eventId
     */
    public UUID add(String topic, UUID aggregateId, String type, int version, Object data) {
        // Same check as @Transactional(propagation = MANDATORY), without a proxy around this bean.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OutboxWriter.add needs an active transaction");
        }
        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant();
        String payload;
        try {
            payload = json.writeValueAsString(new EventEnvelope<>(eventId, type, version, now, data));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise " + type, e);
        }
        jdbc.sql("""
                INSERT INTO outbox (id, aggregate_id, topic, event_type, payload, created_at)
                VALUES (:id, :aggregateId, :topic, :type, CAST(:payload AS jsonb), :createdAt)
                """)
                .param("id", eventId)
                .param("aggregateId", aggregateId)
                .param("topic", topic)
                .param("type", type)
                .param("payload", payload)
                .param("createdAt", Timestamp.from(now))
                .update();
        return eventId;
    }
}
