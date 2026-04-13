package com.plugpoint.paymentservice.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class HomeController {

    @GetMapping("/")
    public Map<String, Object> home() {
        Map<String, Object> response = new HashMap<>();
        response.put("service", "Payment Processing Microservice");
        response.put("status", "UP");
        response.put("message", "Welcome! The microservice is running successfully.");
        
        Map<String, String> endpoints = new HashMap<>();
        endpoints.put("Mock Process Payment", "POST /api/v1/mock/payments/process");
        endpoints.put("Mock Fail Payment", "POST /api/v1/mock/payments/fail");
        endpoints.put("Mock Status Check", "GET /api/v1/mock/payments/status/{orderId}");
        endpoints.put("Check Wallet Balance", "GET /api/v1/wallets/balance/{username}");
        endpoints.put("User-to-User Transfer", "POST /api/v1/wallets/transfer");
        endpoints.put("H2 Console", "/h2-console");
        
        response.put("available_endpoints", endpoints);
        return response;
    }
}
