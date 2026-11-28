package com.flashsale.payment.payment;

import com.flashsale.payment.payment.PaymentService.PaymentStatus;
import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service API for order-service (step 2.6). /internal/** is never routed by the gateway; on the VM only
 * the gateway is reachable from outside (docs/todo.md).
 */
@RestController
@RequestMapping("/internal/payments")
class InternalPaymentController {

    private final PaymentService payments;

    InternalPaymentController(PaymentService payments) {
        this.payments = payments;
    }

    record ExpireRequest(BigDecimal amount) {
    }

    /**
     * The final payment state of an order: the existing payment, or a new FAILED / EXPIRED one if there is none.
     * POST because it may create that row; safe to repeat.
     */
    @PostMapping("/{orderId}/expire")
    ResponseEntity<?> expire(@PathVariable UUID orderId, @RequestBody(required = false) ExpireRequest request) {
        if (request == null || request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0
                || request.amount().scale() > 2) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "amount is required, > 0, at most 2 decimals");
            problem.setType(URI.create("about:blank"));
            return ResponseEntity.badRequest().body(problem);
        }
        PaymentStatus status = payments.expireIfMissing(orderId, request.amount());
        return ResponseEntity.ok(status);
    }
}
