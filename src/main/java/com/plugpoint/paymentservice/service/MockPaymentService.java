package com.plugpoint.paymentservice.service;

import com.plugpoint.paymentservice.dto.PaymentRequest;
import com.plugpoint.paymentservice.dto.PaymentResponse;
import com.plugpoint.paymentservice.model.Payment;
import com.plugpoint.paymentservice.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MockPaymentService {

    private final PaymentRepository paymentRepository;
    private final IdempotencyService idempotencyService;
    private final PaymentEventProducer paymentEventProducer;

    @Value("${payment.mock.latency-ms:500}")
    private long latencyMs;

    @Value("${payment.mock.failure-rate:0.1}")
    private double failureRate;

    /**
     * Entry point for payment processing.
     * Logic is split to keep the @Transactional block as short as possible.
     */
    public PaymentResponse processPayment(PaymentRequest request) {
        log.info("[PaymentProcessor] START - Order: {}", request.getOrderId());

        // 1. Idempotency Check (Redis - Fast)
        if (!idempotencyService.lock(request.getIdempotencyKey())) {
            log.warn("[PaymentProcessor] Duplicate request: {}", request.getIdempotencyKey());
            return handleDuplicateRequest(request.getIdempotencyKey());
        }

        // 2. Initialize Record (Transactional - Fast)
        Payment payment = initializePaymentRecord(request);

        try {
            // 3. Simulated Gateway Delay (Non-Transactional - Connection Released!)
            if (latencyMs > 0) {
                Thread.sleep(latencyMs);
            }

            // 4. Determine Result
            boolean isSuccess = Math.random() > failureRate;
            String gatewayId = "mock_tx_" + UUID.randomUUID().toString().substring(0, 8);

            // 5. Finalize Record (Transactional - Fast)
            finalizePaymentRecord(payment.getId(), isSuccess, gatewayId, request.getIdempotencyKey());

            // 6. Async Messaging
            if (isSuccess) {
                triggerEvent(payment, isSuccess, gatewayId);
            }

            return PaymentResponse.builder()
                    .paymentId(payment.getId())
                    .orderId(payment.getOrderId())
                    .status(isSuccess ? "SUCCEEDED" : "FAILED")
                    .gatewayReference(gatewayId)
                    .message(isSuccess ? "Processed (Optimized)" : "Failed (Optimized)")
                    .build();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Process interrupted");
        } catch (Exception e) {
            log.error("[PaymentProcessor] Processing error: {}", e.getMessage());
            updateStatusToFailed(request.getIdempotencyKey());
            throw e;
        }
    }

    @Transactional
    public Payment initializePaymentRecord(PaymentRequest request) {
        Payment payment = Payment.builder()
                .orderId(request.getOrderId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .idempotencyKey(request.getIdempotencyKey())
                .status(Payment.PaymentStatus.PROCESSING)
                .paymentMethodId(request.getPaymentMethodId())
                .build();
        return paymentRepository.save(payment);
    }

    @Transactional
    public void finalizePaymentRecord(UUID id, boolean success, String gatewayId, String idempotencyKey) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Payment not found: " + id));
        
        payment.setGatewayTransactionId(gatewayId);
        payment.setStatus(success ? Payment.PaymentStatus.SUCCEEDED : Payment.PaymentStatus.FAILED);
        paymentRepository.save(payment);
        
        idempotencyService.updateStatus(idempotencyKey, payment.getStatus().name());
    }

    private void triggerEvent(Payment payment, boolean success, String gatewayId) {
        paymentEventProducer.sendPaymentEvent(com.plugpoint.paymentservice.dto.PaymentEvent.builder()
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .status(success ? "SUCCEEDED" : "FAILED")
                .gatewayReference(gatewayId)
                .build());
    }

    private void updateStatusToFailed(String key) {
        idempotencyService.updateStatus(key, "FAILED");
    }

    private PaymentResponse handleDuplicateRequest(String key) {
        return paymentRepository.findByIdempotencyKey(key)
                .map(p -> PaymentResponse.builder()
                        .paymentId(p.getId())
                        .orderId(p.getOrderId())
                        .status(p.getStatus().name())
                        .gatewayReference(p.getGatewayTransactionId())
                        .message("Duplicate request results returned from cache")
                        .build())
                .orElseThrow(() -> new RuntimeException("Inconsistent idempotency state"));
    }
}
