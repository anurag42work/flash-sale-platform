package com.flashsale.order.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashsale.order.catalog.CatalogUnavailableException;
import com.flashsale.order.catalog.OutOfStockException;
import com.flashsale.order.order.OrderDtos.OrderResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
    private static final String ONE_ITEM = "{\"items\":[{\"productId\":1,\"quantity\":2}]}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OrderService orders;

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

        mvc.perform(asUser(post("/api/orders")).content(ONE_ITEM))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/orders/" + id))
                .andExpect(jsonPath("$.status").value("PENDING"));
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

        mvc.perform(asUser(post("/api/orders")).content(ONE_ITEM))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.productId").value(1));
    }

    @Test
    void catalogDownReturns503() throws Exception {
        given(orders.create(eq(USER), any())).willThrow(new CatalogUnavailableException(new RuntimeException("x")));

        mvc.perform(asUser(post("/api/orders")).content(ONE_ITEM))
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
