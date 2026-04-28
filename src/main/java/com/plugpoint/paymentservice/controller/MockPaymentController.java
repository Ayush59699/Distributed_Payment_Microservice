package com.plugpoint.paymentservice.controller;

import com.plugpoint.paymentservice.dto.PaymentRequest;
import com.plugpoint.paymentservice.dto.PaymentResponse;
import com.plugpoint.paymentservice.service.MockPaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/mock/payments")
@RequiredArgsConstructor
@Slf4j
public class MockPaymentController {

    private final MockPaymentService mockPaymentService;

    @PostMapping("/process")
    public ResponseEntity<PaymentResponse> mockProcess(@Valid @RequestBody PaymentRequest request) {
        log.info("[API] Received mock payment request for Order: {}", request.getOrderId());
        PaymentResponse response = mockPaymentService.processPayment(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/fail")
    public ResponseEntity<PaymentResponse> mockFail(@Valid @RequestBody PaymentRequest request) {
        log.info("[API] Received mock failure request for Order: {}", request.getOrderId());
        PaymentResponse response = mockPaymentService.processPayment(request);
        return ResponseEntity.ok(response);
    }
}
