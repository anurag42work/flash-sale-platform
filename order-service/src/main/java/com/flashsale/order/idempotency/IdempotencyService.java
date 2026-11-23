package com.flashsale.order.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Runs an action at most once per (user, Idempotency-Key) and replays the stored response on retries.
 *
 * <p>Flow (incident 002):
 * <ol>
 *   <li>Claim: {@code INSERT ... ON CONFLICT DO NOTHING}. The primary key lets exactly one request win, even
 *       when 20 identical requests arrive at once. The claim commits immediately, so the others see it.</li>
 *   <li>Winner: runs the action, stores the response as COMPLETED. If the action fails, the key is deleted so
 *       the client can retry with the same key (the failed order already gave its stock back).</li>
 *   <li>Others: same key + different body → 422; still IN_PROGRESS → 409; COMPLETED → replay.</li>
 * </ol>
 *
 * <p>No @Transactional: each statement commits on its own. Holding a transaction around the action would keep
 * a DB connection busy during the catalog HTTP calls and hide the claim from concurrent requests.
 */
@Service
public class IdempotencyService {

    /** Letters, digits, '-' and '_', at most 64 (fits a UUID and the column). */
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public IdempotencyService(JdbcClient jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public record Result<T>(int status, T body, boolean replayed) {
    }

    /** Throws 400 when the header is missing or malformed. */
    public static String requireKey(String key) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
        return key;
    }

    public <T> Result<T> execute(UUID userId, String key, Object request, Class<T> responseType, int successStatus,
            Supplier<T> action) {
        String requestHash = sha256(toJson(request));

        if (!claim(userId, key, requestHash)) {
            return replay(userId, key, requestHash, responseType);
        }

        T body;
        try {
            body = action.get();
        } catch (RuntimeException e) {
            release(userId, key);
            throw e;
        }
        complete(userId, key, successStatus, toJson(body));
        return new Result<>(successStatus, body, false);
    }

    private boolean claim(UUID userId, String key, String requestHash) {
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_keys (user_id, idem_key, request_hash, status, created_at, updated_at)
                        VALUES (:user, :key, :hash, 'IN_PROGRESS', :now, :now)
                        ON CONFLICT (user_id, idem_key) DO NOTHING""")
                .param("user", userId)
                .param("key", key)
                .param("hash", requestHash)
                .param("now", now())
                .update();
        return inserted == 1;
    }

    private <T> Result<T> replay(UUID userId, String key, String requestHash, Class<T> responseType) {
        Optional<StoredKey> stored = jdbc.sql("""
                        SELECT request_hash, status, response_status, response_body::text AS response_body
                          FROM idempotency_keys
                         WHERE user_id = :user AND idem_key = :key""")
                .param("user", userId)
                .param("key", key)
                .query(StoredKey.class)
                .optional();

        // Gone: the first request failed and released the key just now. Ask the client to retry.
        StoredKey row = stored.orElseThrow(IdempotencyKeyInProgressException::new);
        if (!row.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException();
        }
        if (!"COMPLETED".equals(row.status())) {
            throw new IdempotencyKeyInProgressException();
        }
        return new Result<>(row.responseStatus(), fromJson(row.responseBody(), responseType), true);
    }

    private void complete(UUID userId, String key, int status, String body) {
        jdbc.sql("""
                        UPDATE idempotency_keys
                           SET status = 'COMPLETED', response_status = :status,
                               response_body = CAST(:body AS jsonb), updated_at = :now
                         WHERE user_id = :user AND idem_key = :key""")
                .param("status", status)
                .param("body", body)
                .param("now", now())
                .param("user", userId)
                .param("key", key)
                .update();
    }

    private void release(UUID userId, String key) {
        jdbc.sql("DELETE FROM idempotency_keys WHERE user_id = :user AND idem_key = :key")
                .param("user", userId)
                .param("key", key)
                .update();
    }

    private record StoredKey(String requestHash, String status, Integer responseStatus, String responseBody) {
    }

    // ---- helpers ----

    /** The parsed request turned back into JSON: whitespace/field order of the raw body don't change the hash. */
    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize " + value.getClass().getSimpleName(), e);
        }
    }

    private <T> T fromJson(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored response is not a valid " + type.getSimpleName(), e);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }
}
