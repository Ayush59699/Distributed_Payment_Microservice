package com.plugpoint.paymentservice.service;

import com.azure.messaging.servicebus.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plugpoint.paymentservice.dto.PaymentEvent;
import com.plugpoint.paymentservice.dto.WalletTransferEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEventProducer {

    private final ObjectMapper objectMapper;
    private ServiceBusSenderAsyncClient sender;

    @Value("${SERVICEBUS_CONNECTION_STRING:}")
    private String connectionString;

    @Value("${spring.cloud.azure.servicebus.entity-name:payment-queue}")
    private String queueName;

    @PostConstruct
    public void init() {
        log.info("[Messaging] Initializing ASYNC Service Bus sender for queue: {}", queueName);
        
        if (connectionString == null || connectionString.isEmpty()) {
            log.error("[Messaging] CRITICAL: SERVICEBUS_CONNECTION_STRING is missing!");
            return;
        }
        try {
            // Using AsyncClient for non-blocking sends
            this.sender = new ServiceBusClientBuilder()
                    .connectionString(connectionString)
                    .sender()
                    .queueName(queueName)
                    .buildAsyncClient();
            log.info("[Messaging] Async Service Bus sender initialized successfully.");
        } catch (Exception e) {
            log.error("[Messaging] Failed to initialize Async sender: {}", e.getMessage());
        }
    }

    public void sendPaymentEvent(PaymentEvent event) {
        if (sender == null) return;

        try {
            String json = objectMapper.writeValueAsString(event);
            ServiceBusMessage message = new ServiceBusMessage(json);
            message.getApplicationProperties().put("eventType", "PAYMENT");
            
            // Non-blocking send
            sender.sendMessage(message)
                  .subscribe(
                      unused -> log.info("[Messaging] PaymentEvent sent (Async) for Order: {}", event.getOrderId()),
                      error -> log.error("[Messaging] Failed to send PaymentEvent (Async): {}", error.getMessage())
                  );
        } catch (Exception e) {
            log.error("[Messaging] Serialization error: {}", e.getMessage());
        }
    }

    public void sendTransferEvent(WalletTransferEvent event) {
        if (sender == null) return;

        try {
            String json = objectMapper.writeValueAsString(event);
            ServiceBusMessage message = new ServiceBusMessage(json);
            message.getApplicationProperties().put("eventType", "TRANSFER");

            sender.sendMessage(message)
                  .subscribe(
                      unused -> log.info("[Messaging] TransferEvent sent (Async)"),
                      error -> log.error("[Messaging] Failed to send TransferEvent (Async): {}", error.getMessage())
                  );
        } catch (Exception e) {
            log.error("[Messaging] Serialization error: {}", e.getMessage());
        }
    }
}
