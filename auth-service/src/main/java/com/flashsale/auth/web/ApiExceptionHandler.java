package com.flashsale.auth.web;

import com.flashsale.auth.account.EmailAlreadyRegisteredException;
import com.flashsale.auth.account.InvalidCredentialsException;
import com.flashsale.auth.token.InvalidRefreshTokenException;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * All errors as RFC 7807 ProblemDetail (application/problem+json). The base class already covers Spring MVC
 * errors such as malformed JSON (400) and unsupported media type (415).
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** 400 with one message per invalid field, e.g. "errors": {"email": "must be a well-formed email address"}. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new TreeMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ProblemDetail problem = ex.getBody();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    ProblemDetail emailAlreadyRegistered(EmailAlreadyRegisteredException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler({InvalidCredentialsException.class, InvalidRefreshTokenException.class})
    ProblemDetail unauthorized(RuntimeException ex) {
        return problem(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }
}
