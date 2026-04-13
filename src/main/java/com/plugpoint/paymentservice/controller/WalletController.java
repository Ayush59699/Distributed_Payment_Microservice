package com.plugpoint.paymentservice.controller;

import com.plugpoint.paymentservice.dto.TransferRequest;
import com.plugpoint.paymentservice.service.IdempotencyService;
import com.plugpoint.paymentservice.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final IdempotencyService idempotencyService;

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
        // 1. Check Redis for Idempotency Lock
        if (!idempotencyService.lock(request.getIdempotencyKey())) {
            Object status = idempotencyService.getStatus(request.getIdempotencyKey());
            Map<String, String> response = new HashMap<>();
            response.put("status", "CONFLICT");
            response.put("message",
                    "Duplicate transfer request detected, already present in REDIS CACHE. Status: " + status);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        try {
            walletService.transfer(request.getFromUsername(), request.getToUsername(), request.getAmount());

            // 2. Update status in Redis
            idempotencyService.updateStatus(request.getIdempotencyKey(), "SUCCEEDED");

            Map<String, String> response = new HashMap<>();
            response.put("status", "SUCCESS");
            response.put("message", "Transferred " + request.getAmount() + " from " + request.getFromUsername() + " to "
                    + request.getToUsername());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            idempotencyService.updateStatus(request.getIdempotencyKey(), "FAILED");
            throw e;
        }
    }
}
