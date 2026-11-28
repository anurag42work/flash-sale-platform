package com.flashsale.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    @Test
    void validEnvelopePasses() {
        EventEnvelope<String> envelope = new EventEnvelope<>(UUID.randomUUID(), "OrderCreated", 1, Instant.now(), "x");

        assertThat(envelope.requireValid("OrderCreated")).isSameAs(envelope);
    }

    @Test
    void missingEventIdWrongTypeOrMissingDataAreInvalid() {
        assertThatThrownBy(() -> new EventEnvelope<>(null, "OrderCreated", 1, Instant.now(), "x")
                .requireValid("OrderCreated"))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("eventId");
        assertThatThrownBy(() -> new EventEnvelope<>(UUID.randomUUID(), "PaymentFailed", 1, Instant.now(), "x")
                .requireValid("OrderCreated"))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("type");
        assertThatThrownBy(() -> new EventEnvelope<>(UUID.randomUUID(), null, 1, Instant.now(), "x")
                .requireValid("OrderCreated"))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("type");
        assertThatThrownBy(() -> new EventEnvelope<>(UUID.randomUUID(), "OrderCreated", 1, Instant.now(), null)
                .requireValid("OrderCreated"))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("data");
    }
}
