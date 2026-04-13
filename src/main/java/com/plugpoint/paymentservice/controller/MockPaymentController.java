package com.plugpoint.paymentservice.controller;

import com.plugpoint.paymentservice.dto.PaymentEvent;
import com.plugpoint.paymentservice.dto.PaymentRequest;
import com.plugpoint.paymentservice.dto.PaymentResponse;
import com.plugpoint.paymentservice.model.Payment;
import com.plugpoint.paymentservice.repository.PaymentRepository;
import com.plugpoint.paymentservice.service.IdempotencyService;
import com.plugpoint.paymentservice.service.PaymentEventProducer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * MockPaymentController – bypasses Stripe, PostgreSQL, Redis and RabbitMQ.
 * Use profile "mock" to run the app without any external services.
 */
@RestController
@RequestMapping("/api/v1/mock/payments")
@RequiredArgsConstructor
@Slf4j
public class MockPaymentController {

    private final PaymentRepository paymentRepository;
    private final PaymentEventProducer paymentEventProducer;
    private final IdempotencyService idempotencyService;

    @PostMapping("/process")
    public ResponseEntity<PaymentResponse> mockProcess(@Valid @RequestBody PaymentRequest request) {
        // 1. Check Redis Cache for Idempotency Lock
        if (!idempotencyService.lock(request.getIdempotencyKey())) {
            Object status = idempotencyService.getStatus(request.getIdempotencyKey());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(PaymentResponse.builder()
                            .orderId(request.getOrderId())
                            .status(status != null ? status.toString() : "UNKNOWN")
                            .message("Duplicate request detected in Cache. Status: " + status)
                            .build());
        }

        try {
            // 2. Create the Payment model to persist in DB
            Payment payment = Payment.builder()
                    .orderId(request.getOrderId())
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .paymentMethodId(request.getPaymentMethodId())
                    .idempotencyKey(request.getIdempotencyKey())
                    .status(Payment.PaymentStatus.SUCCEEDED)
                    .gatewayTransactionId("mock_tx_" + UUID.randomUUID().toString().substring(0, 8))
                    .build();

            // 3. Save to H2 Database
            payment = paymentRepository.save(payment);

            // 4. Update Cache Status
            idempotencyService.updateStatus(request.getIdempotencyKey(), "SUCCEEDED");

            // 5. Trigger asynchronous RabbitMQ event
            try {
                paymentEventProducer.sendPaymentEvent(PaymentEvent.builder()
                        .paymentId(payment.getId())
                        .orderId(payment.getOrderId())
                        .amount(payment.getAmount())
                        .currency(payment.getCurrency())
                        .status(payment.getStatus().name())
                        .gatewayReference(payment.getGatewayTransactionId())
                        .build());
            } catch (Exception e) {
                // Log error but don't fail the payment if MQ is down
                log.error("Failed to send MQ event for Order {}: {}", payment.getOrderId(), e.getMessage());
            }

            // 6. Return the response
            PaymentResponse response = PaymentResponse.builder()
                    .paymentId(payment.getId())
                    .orderId(payment.getOrderId())
                    .status(payment.getStatus().name())
                    .gatewayReference(payment.getGatewayTransactionId())
                    .message("[MOCK] Success! Payment recorded in H2 + Protected by Redis Caching. Amount: "
                            + request.getAmount() + " " + request.getCurrency().toUpperCase())
                    .build();

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            idempotencyService.updateStatus(request.getIdempotencyKey(), "FAILED");
            throw e;
        }
    }

    @PostMapping("/fail")
    public ResponseEntity<PaymentResponse> mockFail(@Valid @RequestBody PaymentRequest request) {
        // Save failed payment too
        Payment payment = Payment.builder()
                .orderId(request.getOrderId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .paymentMethodId(request.getPaymentMethodId())
                .idempotencyKey(request.getIdempotencyKey())
                .status(Payment.PaymentStatus.FAILED)
                .build();
        
        payment = paymentRepository.save(payment);

        PaymentResponse response = PaymentResponse.builder()
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .status("FAILED")
                .gatewayReference(null)
                .message("[MOCK] Payment FAILED simulation. Data saved to DB.")
                .build();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/status/{orderId}")
    public ResponseEntity<PaymentResponse> mockStatus(@PathVariable String orderId) {
        return paymentRepository.findByOrderId(orderId)
                .map(payment -> ResponseEntity.ok(PaymentResponse.builder()
                        .paymentId(payment.getId())
                        .orderId(payment.getOrderId())
                        .status(payment.getStatus().name())
                        .gatewayReference(payment.getGatewayTransactionId())
                        .message("[MOCK] Status found in DB.")
                        .build()))
                .orElse(ResponseEntity.notFound().build());
    }
}
