package com.flashsale.catalog.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashsale.catalog.product.ProductDtos.ProductResponse;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** HTTP layer only: validation, admin checks, paging/sort parameters, ProblemDetail. Service is mocked. */
@WebMvcTest(ProductController.class)
class ProductControllerTest {

    private static final String VALID_PRODUCT =
            "{\"sku\":\"SKU-1\",\"name\":\"Phone\",\"price\":199.99,\"initialStock\":50}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ProductService products;

    @Test
    void createWithoutUserReturns401() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(products);
    }

    @Test
    void createAsNormalUserReturns403() throws Exception {
        mvc.perform(asUser(post("/api/products"), "USER").content(VALID_PRODUCT))
                .andExpect(status().isForbidden());
        verifyNoInteractions(products);
    }

    @Test
    void createAsAdminReturns201WithLocation() throws Exception {
        given(products.create(any())).willReturn(
                new ProductResponse(7L, "SKU-1", "Phone", null, new BigDecimal("199.99"), true));

        mvc.perform(asUser(post("/api/products"), "USER,ADMIN").content(VALID_PRODUCT))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/products/7"))
                .andExpect(jsonPath("$.price").value(199.99));
    }

    @Test
    void invalidProductReturns400WithFieldErrors() throws Exception {
        String invalid = "{\"sku\":\"bad sku!\",\"name\":\"\",\"price\":0,\"initialStock\":-1}";

        mvc.perform(asUser(post("/api/products"), "ADMIN").content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sku").exists())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.price").exists())
                .andExpect(jsonPath("$.errors.initialStock").exists());
    }

    @Test
    void priceWithThreeDecimalsIsRejected() throws Exception {
        String invalid = "{\"sku\":\"SKU-1\",\"name\":\"Phone\",\"price\":1.999,\"initialStock\":1}";

        mvc.perform(asUser(post("/api/products"), "ADMIN").content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").exists());
    }

    @Test
    void unknownSortFieldReturns400() throws Exception {
        mvc.perform(get("/api/products").param("sort", "password,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(products);
    }

    @Test
    void pageSizeAbove100Returns400() throws Exception {
        mvc.perform(get("/api/products").param("size", "500"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(products);
    }

    @Test
    void missingProductReturns404Problem() throws Exception {
        given(products.get(anyLong())).willThrow(new ProductNotFoundException(99));

        mvc.perform(get("/api/products/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Product 99 not found"));
    }

    /** Anything unexpected (DB down, a bug) is still a ProblemDetail, and leaks no internal details. */
    @Test
    void unexpectedErrorReturns500ProblemWithoutDetails() throws Exception {
        given(products.get(anyLong())).willThrow(new IllegalStateException("connection to db-host:5432 refused"));

        mvc.perform(get("/api/products/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Unexpected error, please try again later"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("db-host"))));
    }

    private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request, String roles) {
        return request.contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", "8a6e0804-2bd0-4672-b79d-d97027f9071a")
                .header("X-User-Roles", roles);
    }
}
