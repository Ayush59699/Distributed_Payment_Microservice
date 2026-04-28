package com.plugpoint.paymentservice.service;

import com.azure.messaging.servicebus.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plugpoint.paymentservice.dto.PaymentEvent;
import com.plugpoint.paymentservice.dto.WalletTransferEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final AtomicInteger paymentCounter = new AtomicInteger(0);
    private final AtomicInteger transferCounter = new AtomicInteger(0);
    private final ObjectMapper objectMapper;
    private final WalletService walletService;
    private final IdempotencyService idempotencyService; // Added for consumer idempotency
    private ServiceBusProcessorClient processor;

    @Value("${SERVICEBUS_CONNECTION_STRING:}")
    private String connectionString;

    @Value("${spring.cloud.azure.servicebus.entity-name:payment-queue}")
    private String queueName;

    @PostConstruct
    public void startListening() {
        if (connectionString == null || connectionString.isEmpty()) {
            log.error("[Consumer] CRITICAL: SERVICEBUS_CONNECTION_STRING is missing!");
            return;
        }

        this.processor = new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .processor()
                .queueName(queueName)
                .maxConcurrentCalls(5) // Match DB connection pool size (5) to prevent timeouts
                .processMessage(context -> {
                    String body = context.getMessage().getBody().toString();
                    Object typeObj = context.getMessage().getApplicationProperties().get("eventType");
                    String eventType = typeObj != null ? typeObj.toString() : "UNKNOWN";

                    try {
                        if ("PAYMENT".equals(eventType)) {
                            PaymentEvent event = objectMapper.readValue(body, PaymentEvent.class);
                            handlePayment(event);
                        } else if ("TRANSFER".equals(eventType)) {
                            WalletTransferEvent event = objectMapper.readValue(body, WalletTransferEvent.class);
                            handleTransfer(event);
                        }
                    } catch (Exception e) {
                        log.error("[Consumer] Processing error: {}", e.getMessage());
                        // FIX 2: Rethrow so Service Bus retries the message instead of silently
                        // dropping it
                        throw new RuntimeException("[Consumer] Failed to process message, triggering retry", e);
                    }
                })
                .processError(context -> log.error("Service Bus Error: {}", context.getException().getMessage()))
                .buildProcessorClient();

        log.info("[Consumer] Starting Service Bus Processor (MaxConcurrentCalls: 5) for queue: {}...", queueName);
        this.processor.start();
    }

    @PreDestroy
    public void shutdown() {
        if (this.processor != null) {
            log.info("Shutting down Service Bus Processor...");
            this.processor.close();
        }
    }

    private void handlePayment(PaymentEvent event) {
        int count = paymentCounter.incrementAndGet();
        log.info("[Consumer] PAYMENT RECEIVED [{}] - Order: {}", count, event.getOrderId());
    }

    private void handleTransfer(WalletTransferEvent event) {
        int count = transferCounter.incrementAndGet();
        log.info("[Consumer] TRANSFER RECEIVED [{}] - {} -> {} | Amount: {}",
                count, event.getFromUsername(), event.getToUsername(), event.getAmount());

        // FIX 1: Actually execute the transfer business logic
        // NOTE: WalletService.transfer() publishes a TRANSFER event itself (for
        // audit/notification).
        // To prevent an infinite loop (Consumer -> WalletService -> publish -> Consumer
        // -> ...),
        // the consumer calls walletService.executeTransferOrdered() directly, which
        // runs the
        // DB transaction WITHOUT firing another event. See WalletService for details.
        // FIX: Consumer-level Idempotency
        // If the message is retried by Service Bus, we shouldn't double-debit.
        // The API layer set status="QUEUED". We update to "PROCESSING" or skip if already "SUCCEEDED".
        String key = event.getIdempotencyKey();
        if (key != null) {
            Object status = idempotencyService.getStatus(key);
            if ("SUCCEEDED".equals(status)) {
                log.info("[Consumer] Skipping duplicate transfer (Idempotency Key: {})", key);
                return; // Already processed!
            }
            idempotencyService.updateStatus(key, "PROCESSING");
        }

        try {
            // FIX: Ensure wallets exist here, outside the HTTP thread
            walletService.ensureUserExists(event.getFromUsername());
            walletService.ensureUserExists(event.getToUsername());

            walletService.executeTransferOrdered(
                    event.getFromUsername(),
                    event.getToUsername(),
                    event.getAmount());
            log.info("[Consumer] TRANSFER SUCCESS [{}] - {} -> {} | Amount: {}",
                    count, event.getFromUsername(), event.getToUsername(), event.getAmount());
            if (key != null) {
                idempotencyService.updateStatus(key, "SUCCEEDED");
            }
        } catch (Exception e) {
            log.error("[Consumer] TRANSFER FAILED [{}]: {}", count, e.getMessage());
            if (key != null) {
                // Do not mark FAILED if we want retries, or let it retry. Let's mark it FAILED to indicate issue,
                // but rethrow so Service Bus keeps it in queue.
                idempotencyService.updateStatus(key, "FAILED");
            }
            throw e; // Propagate to outer catch to trigger Service Bus retry
        }
    }
}
