package com.flashsale.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * What every event on Kafka looks like (JSON). The envelope fields are the same for all event types.
 *
 * @param eventId    unique per event; consumers use it to drop duplicates (delivery is at-least-once)
 * @param type       e.g. "OrderCreated"; tells the consumer how to read {@code data}
 * @param version    schema version of {@code data}; bump it on a breaking change so consumers can handle both
 * @param occurredAt when the business fact happened (not when it was published)
 * @param data       the event itself, e.g. {@link OrderCreated}
 */
public record EventEnvelope<T>(UUID eventId, String type, int version, Instant occurredAt, T data) {

    /**
     * Checks the envelope fields every consumer relies on.
     *
     * @return this, for chaining
     * @throws InvalidEventException if a field is missing or the type is not the one this topic carries
     */
    public EventEnvelope<T> requireValid(String expectedType) {
        if (eventId == null) {
            throw new InvalidEventException("eventId is missing");
        }
        if (!expectedType.equals(type)) {
            throw new InvalidEventException("type must be " + expectedType + ", was " + type);
        }
        if (data == null) {
            throw new InvalidEventException("data is missing in event " + eventId);
        }
        return this;
    }
}
