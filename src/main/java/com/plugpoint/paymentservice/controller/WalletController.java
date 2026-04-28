package com.plugpoint.paymentservice.controller;

import com.plugpoint.paymentservice.dto.TransferRequest;
import com.plugpoint.paymentservice.dto.WalletTransferEvent;
import com.plugpoint.paymentservice.service.IdempotencyService;
import com.plugpoint.paymentservice.service.PaymentEventProducer;
import com.plugpoint.paymentservice.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
@Slf4j
public class WalletController {

    private final WalletService walletService;
    private final IdempotencyService idempotencyService;
    private final PaymentEventProducer paymentEventProducer;

    @GetMapping("/balance/{username}")
    public ResponseEntity<Map<String, Object>> getBalance(@PathVariable String username) {
        BigDecimal balance = walletService.getBalance(username);
        Map<String, Object> response = new HashMap<>();
        response.put("username", username);
        response.put("balance", balance);
        response.put("currency", "USD");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/transfer")
    public ResponseEntity<Map<String, String>> transfer(@Valid @RequestBody TransferRequest request) {
        // 1. Idempotency guard — prevents duplicate events being published
        if (!idempotencyService.lock(request.getIdempotencyKey())) {
            log.info("[WalletAPI] Idempotency hit: {}", request.getIdempotencyKey());
            Object status = idempotencyService.getStatus(request.getIdempotencyKey());
            Map<String, String> response = new HashMap<>();
            response.put("status", "IDEMPOTENT_ACCEPTED");
            response.put("message", "Duplicate request ignored. Previous status: " + status);
            return ResponseEntity.ok(response);
        }

        // 2. Fire-and-forget: publish to Service Bus. Consumer executes the actual transfer.
        //    This keeps the REST endpoint non-blocking under load (returns 202 immediately).
        //    NOTE: Database operations (like ensureUserExists) are now handled by the consumer.

        // 3. Fire-and-forget: publish to Service Bus. Consumer executes the actual transfer.
        //    This keeps the REST endpoint non-blocking under load (returns 202 immediately).
        paymentEventProducer.sendTransferEvent(
                WalletTransferEvent.builder()
                        .fromUsername(request.getFromUsername())
                        .toUsername(request.getToUsername())
                        .amount(request.getAmount())
                        .currency("USD")
                        .timestamp(LocalDateTime.now())
                        .idempotencyKey(request.getIdempotencyKey())
                        .build()
        );

        idempotencyService.updateStatus(request.getIdempotencyKey(), "QUEUED");
        log.info("[WalletAPI] Transfer queued → {} -> {} | ${}",
                request.getFromUsername(), request.getToUsername(), request.getAmount());

        Map<String, String> response = new HashMap<>();
        response.put("status", "ACCEPTED");
        response.put("message", "Transfer queued: " + request.getAmount() + " from "
                + request.getFromUsername() + " to " + request.getToUsername());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response); // 202
    }
}
