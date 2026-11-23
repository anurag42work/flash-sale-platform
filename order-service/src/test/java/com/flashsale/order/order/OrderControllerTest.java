package com.flashsale.order.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashsale.order.catalog.CatalogUnavailableException;
import com.flashsale.order.catalog.OutOfStockException;
import com.flashsale.order.idempotency.IdempotencyKeyInProgressException;
import com.flashsale.order.idempotency.IdempotencyKeyReusedException;
import com.flashsale.order.idempotency.IdempotencyService;
import com.flashsale.order.idempotency.IdempotencyService.Result;
import com.flashsale.order.order.OrderDtos.OrderResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** HTTP layer only: user header, validation, status codes, ProblemDetail. Service is mocked. */
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    private static final UUID USER = UUID.fromString("8a6e0804-2bd0-4672-b79d-d97027f9071a");
    private static final String KEY = "c0ffee00-0000-4000-8000-000000000001";
    private static final String ONE_ITEM = "{\"items\":[{\"productId\":1,\"quantity\":2}]}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OrderService orders;

    @MockitoBean
    IdempotencyService idempotency;

    /** By default the mocked idempotency layer just runs the action, like a first request with a new key. */
    @BeforeEach
    void idempotencyRunsTheAction() {
        given(idempotency.execute(any(), any(), any(), any(), anyInt(), any())).willAnswer(call -> {
            Supplier<?> action = call.getArgument(5);
            return new Result<>(call.getArgument(4), action.get(), false);
        });
    }

    @Test
    void missingUserHeaderReturns401() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(ONE_ITEM))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(orders);
    }

    @Test
    void userHeaderThatIsNotAUuidReturns401() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(ONE_ITEM)
                        .header("X-User-Id", "admin"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        UUID id = UUID.randomUUID();
        given(orders.create(eq(USER), any())).willReturn(new OrderResponse(id, OrderStatus.PENDING,
                new BigDecimal("20.00"), Instant.now(), List.of()));

        mvc.perform(asUser(post("/api/orders")).header("Idempotency-Key", KEY).content(ONE_ITEM))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/orders/" + id))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(ONE_ITEM)
                        .header("X-User-Id", USER.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Idempotency-Key")));
        verifyNoInteractions(orders);
    }

    @Test
    void malformedIdempotencyKeyReturns400() throws Exception {
        mvc.perform(asUser(post("/api/orders")).header("Idempotency-Key", "has spaces!").content(ONE_ITEM))
                .andExpect(status().isBadRequest());
        mvc.perform(asUser(post("/api/orders")).header("Idempotency-Key", "x".repeat(65)).content(ONE_ITEM))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orders);
    }

    @Test
    void replayedResponseIsMarked() throws Exception {
        OrderResponse stored = new OrderResponse(UUID.randomUUID(), OrderStatus.PENDING, new BigDecimal("20.00"),
                Instant.now(), List.of());
        willReturn(new Result<>(201, stored, true)).given(idempotency)
                .execute(any(), any(), any(), any(), anyInt(), any());

        mvc.perform(asUser(post("/api/orders")).header("Idempotency-Key", KEY).content(ONE_ITEM))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(stored.id().toString()));
        verifyNoInteractions(orders);
    }

    @Test
    void keyInProgressReturns409WithCode() throws Exception {
        willThrow(new IdempotencyKeyInProgressException()).given(idempotency)
                .execute(any(), any(), any(), any(), anyInt(), any());

        mvc.perform(asUser(post("/api/orders")).header("Idempotency-Key", KEY).content(ONE_ITEM))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_IN_PROGRESS"));
    }

    @Test
    void keyReusedWithOtherBodyReturns422WithCode() throws Exception {
        willThrow(new IdempotencyKeyReusedException()).given(idempotency)
                .execute(any(), any(), any(), any(), anyInt(), any());

        mvc.perform(asUser(post("/api/orders")).header("Idempotency-Key", KEY).content(ONE_ITEM))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void emptyItemsReturn400() throws Exception {
        mvc.perform(asUser(post("/api/orders")).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.items").exists());
        verifyNoInteractions(orders);
    }

    @Test
    void invalidQuantityReturns400() throws Exception {
        mvc.perform(asUser(post("/api/orders")).content("{\"items\":[{\"productId\":1,\"quantity\":0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['items[0].quantity']").exists());
    }

    @Test
    void sameProductTwiceReturns400() throws Exception {
        String duplicate = "{\"items\":[{\"productId\":1,\"quantity\":1},{\"productId\":1,\"quantity\":2}]}";

        mvc.perform(asUser(post("/api/orders")).content(duplicate))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.uniqueProducts").exists());
        verifyNoInteractions(orders);
    }

    @Test
    void outOfStockReturns409WithCode() throws Exception {
        given(orders.create(eq(USER), any())).willThrow(new OutOfStockException(1));

        mvc.perform(asUser(post("/api/orders")).header("Idempotency-Key", KEY).content(ONE_ITEM))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.productId").value(1));
    }

    @Test
    void catalogDownReturns503() throws Exception {
        given(orders.create(eq(USER), any())).willThrow(new CatalogUnavailableException(new RuntimeException("x")));

        mvc.perform(asUser(post("/api/orders")).header("Idempotency-Key", KEY).content(ONE_ITEM))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void orderIdThatIsNotAUuidReturns400() throws Exception {
        mvc.perform(get("/api/orders/not-a-uuid").header("X-User-Id", USER.toString()))
                .andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request) {
        return request.contentType(MediaType.APPLICATION_JSON).header("X-User-Id", USER.toString());
    }
}
