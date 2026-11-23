package com.flashsale.auth.account;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashsale.auth.account.AccountDtos.TokenResponse;
import com.flashsale.auth.account.AccountDtos.UserResponse;
import com.flashsale.auth.config.SecurityConfig;
import com.flashsale.auth.token.InvalidRefreshTokenException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/** HTTP layer only (validation, status codes, ProblemDetail bodies, security rules). The service is mocked. */
@WebMvcTest(AccountController.class)
@Import(SecurityConfig.class)
class AccountControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccountService accounts;

    @Test
    void registerReturns201WithUser() throws Exception {
        UUID id = UUID.randomUUID();
        given(accounts.register(any())).willReturn(new UserResponse(id, "a@example.com", "USER"));

        mvc.perform(json("/api/auth/register", "{\"email\":\"a@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void registerWithInvalidFieldsReturns400ProblemWithFieldErrors() throws Exception {
        mvc.perform(json("/api/auth/register", "{\"email\":\"not-an-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists());
        verifyNoInteractions(accounts);
    }

    /** BCrypt uses at most 72 BYTES. 40 x 'é' is 40 characters (passes @Size) but 80 bytes in UTF-8. */
    @Test
    void registerWithPasswordOver72BytesReturns400() throws Exception {
        String password = "é".repeat(40);

        mvc.perform(json("/api/auth/register", "{\"email\":\"a@example.com\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.password").value(containsString("72 bytes")));
        verifyNoInteractions(accounts);
    }

    /** Anything unexpected (DB down, a bug) is still a ProblemDetail, and leaks no internal details. */
    @Test
    void unexpectedErrorReturns500ProblemWithoutDetails() throws Exception {
        given(accounts.login(any())).willThrow(new IllegalStateException("connection to db-host:5432 refused"));

        mvc.perform(json("/api/auth/login", "{\"email\":\"a@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Unexpected error, please try again later"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("db-host"))));
    }

    @Test
    void malformedJsonReturns400Problem() throws Exception {
        mvc.perform(json("/api/auth/login", "{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void duplicateEmailReturns409Problem() throws Exception {
        given(accounts.register(any())).willThrow(new EmailAlreadyRegisteredException());

        mvc.perform(json("/api/auth/register", "{\"email\":\"a@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Email already registered"));
    }

    @Test
    void wrongCredentialsReturn401Problem() throws Exception {
        given(accounts.login(any())).willThrow(new InvalidCredentialsException());

        mvc.perform(json("/api/auth/login", "{\"email\":\"a@example.com\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    @Test
    void loginReturnsTokensAndIsNeverCached() throws Exception {
        given(accounts.login(any())).willReturn(new TokenResponse("access", "Bearer", 900, "refresh"));

        mvc.perform(json("/api/auth/login", "{\"email\":\"a@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.refreshToken").value("refresh"))
                // Spring Security adds this by default; tokens must not be stored by browsers or proxies.
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void invalidRefreshTokenReturns401Problem() throws Exception {
        willThrow(new InvalidRefreshTokenException()).given(accounts).refresh("stolen");

        mvc.perform(json("/api/auth/refresh", "{\"refreshToken\":\"stolen\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid refresh token"));
    }

    @Test
    void blankRefreshTokenReturns400() throws Exception {
        mvc.perform(json("/api/auth/refresh", "{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.refreshToken").exists());
    }

    @Test
    void logoutReturns204() throws Exception {
        mvc.perform(json("/api/auth/logout", "{\"refreshToken\":\"abc\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void unknownPathsAreDenied() throws Exception {
        mvc.perform(get("/api/auth/users"))
                .andExpect(status().isForbidden());
    }

    private static RequestBuilder json(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
