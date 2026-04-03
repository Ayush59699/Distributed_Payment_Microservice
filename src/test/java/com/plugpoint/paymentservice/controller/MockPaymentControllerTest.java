package com.plugpoint.paymentservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@WebMvcTest(MockPaymentController.class)
@DisplayName("Mock Payment API Tests")
class MockPaymentControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        // ─── Shared payload builder ────────────────────────────────────────────────

        private Map<String, Object> buildPayload(String orderId, double amount, String currency, String methodId,
                        String idemKey) {
                return Map.of(
                                "orderId", orderId,
                                "amount", amount,
                                "currency", currency,
                                "paymentMethodId", methodId,
                                "idempotencyKey", idemKey);
        }

        // ─── Process tests ─────────────────────────────────────────────────────────

        @Test
        @DisplayName("POST /mock/payments/process → 200 SUCCEEDED")
        void processPayment_shouldReturnSucceeded() throws Exception {
                var payload = buildPayload("ORDER-001", 99.99, "USD", "pm_mock_visa_4242", "idem-key-001");

                mockMvc.perform(post("/api/v1/mock/payments/process")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(payload)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status", is("SUCCEEDED")))
                                .andExpect(jsonPath("$.orderId", is("ORDER-001")))
                                .andExpect(jsonPath("$.paymentId", notNullValue()))
                                .andExpect(jsonPath("$.gatewayReference", startsWith("mock_pi_")))
                                .andExpect(jsonPath("$.message", containsString("99.99")))
                                .andExpect(jsonPath("$.message", containsString("USD")));
        }

        @Test
        @DisplayName("POST /mock/payments/process → 400 when orderId blank")
        void processPayment_shouldReturn400_whenOrderIdMissing() throws Exception {
                var payload = Map.of(
                                "amount", 50.00,
                                "currency", "INR",
                                "paymentMethodId", "pm_mock_card",
                                "idempotencyKey", "idem-key-002");

                mockMvc.perform(post("/api/v1/mock/payments/process")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(payload)))
                                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /mock/payments/process → 400 when amount missing")
        void processPayment_shouldReturn400_whenAmountMissing() throws Exception {
                var payload = Map.of(
                                "orderId", "ORDER-003",
                                "currency", "EUR",
                                "paymentMethodId", "pm_mock_card",
                                "idempotencyKey", "idem-key-003");

                mockMvc.perform(post("/api/v1/mock/payments/process")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(payload)))
                                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /mock/payments/process → Indian Rupee payment")
        void processPayment_shouldHandleINR() throws Exception {
                var payload = buildPayload("ORDER-INR-001", 1500.00, "INR", "pm_mock_upi", "idem-key-inr-001");

                mockMvc.perform(post("/api/v1/mock/payments/process")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(payload)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status", is("SUCCEEDED")))
                                .andExpect(jsonPath("$.message", containsString("INR")));
        }

        // ─── Fail tests ────────────────────────────────────────────────────────────

        @Test
        @DisplayName("POST /mock/payments/fail → 200 FAILED (simulated decline)")
        void failPayment_shouldReturnFailed() throws Exception {
                var payload = buildPayload("ORDER-002", 200.00, "USD", "pm_mock_decline_4000", "idem-key-fail-001");

                mockMvc.perform(post("/api/v1/mock/payments/fail")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(payload)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status", is("FAILED")))
                                .andExpect(jsonPath("$.orderId", is("ORDER-002")))
                                .andExpect(jsonPath("$.gatewayReference", nullValue()))
                                .andExpect(jsonPath("$.message", containsString("declined")));
        }

        // ─── Status tests ──────────────────────────────────────────────────────────

        @Test
        @DisplayName("GET /mock/payments/status/{orderId} → returns mock status")
        void getStatus_shouldReturnMockStatus() throws Exception {
                mockMvc.perform(get("/api/v1/mock/payments/status/ORDER-001"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status", is("SUCCEEDED")))
                                .andExpect(jsonPath("$.orderId", is("ORDER-001")))
                                .andExpect(jsonPath("$.message", containsString("SUCCEEDED")));
        }

        @Test
        @DisplayName("GET /mock/payments/status → 404 when no orderId path variable")
        void getStatus_without_orderId_should404() throws Exception {
                mockMvc.perform(get("/api/v1/mock/payments/status/"))
                                .andExpect(status().isNotFound());
        }
}
