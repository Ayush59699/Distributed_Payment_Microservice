package com.plugpoint.paymentservice.service;

import com.plugpoint.paymentservice.dto.PaymentRequest;
import com.plugpoint.paymentservice.dto.PaymentResponse;
import com.plugpoint.paymentservice.model.Payment;
import com.plugpoint.paymentservice.repository.PaymentRepository;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
@Profile("!mock")
public class StripePaymentService {

    private final PaymentRepository paymentRepository;
    private final IdempotencyService idempotencyService;
    private final PaymentEventProducer paymentEventProducer;

    @Value("${stripe.api.key}")
    private String stripeApiKey;

    @PostConstruct
    public void init() {
        Stripe.apiKey = stripeApiKey;
    }

    @Transactional
    public PaymentResponse processPayment(PaymentRequest request) {
        log.info("Processing payment for order: {} with idempotency key: {}", request.getOrderId(), request.getIdempotencyKey());

        // 1. Check Idempotency
        if (!idempotencyService.lock(request.getIdempotencyKey())) {
            log.warn("Duplicate request detected for idempotency key: {}", request.getIdempotencyKey());
            return handleDuplicateRequest(request.getIdempotencyKey());
        }

        try {
            // 2. Create internal payment record (PENDING)
            Payment payment = Payment.builder()
                    .orderId(request.getOrderId())
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .idempotencyKey(request.getIdempotencyKey())
                    .status(Payment.PaymentStatus.PROCESSING)
                    .paymentMethodId(request.getPaymentMethodId())
                    .build();
            
            payment = paymentRepository.save(payment);

            // 3. Call Stripe API
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(request.getAmount().multiply(new BigDecimal(100)).longValue()) // Stripe uses cents
                    .setCurrency(request.getCurrency().toLowerCase())
                    .setPaymentMethod(request.getPaymentMethodId())
                    .setConfirm(true)
                    .setAutomaticPaymentMethods(
                            PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                    .setEnabled(true)
                                    .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                                    .build()
                    )
                    .build();

            PaymentIntent intent = PaymentIntent.create(params);

            // 4. Update status based on Stripe response
            payment.setGatewayTransactionId(intent.getId());
            if ("succeeded".equals(intent.getStatus())) {
                payment.setStatus(Payment.PaymentStatus.SUCCEEDED);
                idempotencyService.updateStatus(request.getIdempotencyKey(), "SUCCEEDED");
            } else {
                payment.setStatus(Payment.PaymentStatus.FAILED);
                idempotencyService.updateStatus(request.getIdempotencyKey(), "FAILED");
            }

            paymentRepository.save(payment);

            // 5. Trigger asynchronous RabbitMQ event
            if (payment.getStatus() == Payment.PaymentStatus.SUCCEEDED) {
                try {
                    paymentEventProducer.sendPaymentEvent(com.plugpoint.paymentservice.dto.PaymentEvent.builder()
                            .paymentId(payment.getId())
                            .orderId(payment.getOrderId())
                            .amount(payment.getAmount())
                            .currency(payment.getCurrency())
                            .status(payment.getStatus().name())
                            .gatewayReference(payment.getGatewayTransactionId())
                            .build());
                } catch (Exception e) {
                    log.error("Failed to send MQ event: {}", e.getMessage());
                }
            }

            return PaymentResponse.builder()
                    .paymentId(payment.getId())
                    .orderId(payment.getOrderId())
                    .status(payment.getStatus().name())
                    .gatewayReference(payment.getGatewayTransactionId())
                    .message("Payment processed successfully")
                    .build();

        } catch (StripeException e) {
            log.error("Stripe error: {}", e.getMessage());
            idempotencyService.updateStatus(request.getIdempotencyKey(), "FAILED");
            throw new RuntimeException("Payment failed: " + e.getMessage());
        }
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
