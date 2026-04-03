package com.plugpoint.paymentservice.controller;

import com.plugpoint.paymentservice.dto.PaymentRequest;
import com.plugpoint.paymentservice.dto.PaymentResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * MockPaymentController – bypasses Stripe, PostgreSQL, Redis and RabbitMQ.
 * Use profile "mock" to run the app without any external services.
 * Endpoints:
 *   POST /api/v1/mock/payments/process   – simulate a successful payment
 *   POST /api/v1/mock/payments/fail       – simulate a failed payment
 *   GET  /api/v1/mock/payments/status/{orderId} – mock status check
 */
@RestController
@RequestMapping("/api/v1/mock/payments")
public class MockPaymentController {

    @PostMapping("/process")
    public ResponseEntity<PaymentResponse> mockProcess(@Valid @RequestBody PaymentRequest request) {
        PaymentResponse response = PaymentResponse.builder()
                .paymentId(UUID.randomUUID())
                .orderId(request.getOrderId())
                .status("SUCCEEDED")
                .gatewayReference("mock_pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .message("[MOCK] Payment processed successfully. Amount: "
                        + request.getAmount() + " " + request.getCurrency().toUpperCase())
                .build();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/fail")
    public ResponseEntity<PaymentResponse> mockFail(@Valid @RequestBody PaymentRequest request) {
        PaymentResponse response = PaymentResponse.builder()
                .paymentId(UUID.randomUUID())
                .orderId(request.getOrderId())
                .status("FAILED")
                .gatewayReference(null)
                .message("[MOCK] Payment FAILED simulation. Card declined (mock error).")
                .build();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/status/{orderId}")
    public ResponseEntity<PaymentResponse> mockStatus(@PathVariable String orderId) {
        PaymentResponse response = PaymentResponse.builder()
                .paymentId(UUID.randomUUID())
                .orderId(orderId)
                .status("SUCCEEDED")
                .gatewayReference("mock_pi_status_check")
                .message("[MOCK] Payment for order " + orderId + " is SUCCEEDED (mock response).")
                .build();
        return ResponseEntity.ok(response);
    }
}
