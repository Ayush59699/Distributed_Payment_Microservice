package com.plugpoint.paymentservice.controller;

import com.plugpoint.paymentservice.dto.PaymentRequest;
import com.plugpoint.paymentservice.dto.PaymentResponse;
import com.plugpoint.paymentservice.service.StripePaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Profile("!mock")
public class PaymentController {

    private final StripePaymentService stripePaymentService;

    @PostMapping("/process")
    public ResponseEntity<PaymentResponse> processPayment(@Valid @RequestBody PaymentRequest request) {
        PaymentResponse response = stripePaymentService.processPayment(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/status/{orderId}")
    public ResponseEntity<String> getPaymentStatus(@PathVariable String orderId) {
        // Simple status check endpoint
        return ResponseEntity.ok("Payment status for order " + orderId + " is currently under investigation (Mock)");
    }
}
