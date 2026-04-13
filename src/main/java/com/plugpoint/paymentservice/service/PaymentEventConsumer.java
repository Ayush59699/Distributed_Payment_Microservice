package com.plugpoint.paymentservice.service;

import com.plugpoint.paymentservice.config.MQConfig;
import com.plugpoint.paymentservice.dto.PaymentEvent;
import com.plugpoint.paymentservice.dto.WalletTransferEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class PaymentEventConsumer {
    private final AtomicInteger paymentCounter = new AtomicInteger(0);
    private final AtomicInteger transferCounter = new AtomicInteger(0);

    @RabbitListener(queues = MQConfig.PAYMENT_QUEUE)
    public void consumePaymentEvent(PaymentEvent event) {
        int count = paymentCounter.incrementAndGet();
        log.info("========================================");
        log.info("[MQ] RECEIVED PAYMENT NOTIFICATION - Total: {}", count);
        log.info("Order ID: {}", event.getOrderId());
        log.info("Amount: {} {}", event.getAmount(), event.getCurrency());
        log.info("Status: {}", event.getStatus());
        log.info("Gateway Ref: {}", event.getGatewayReference());
        log.info("========================================");
    }

    @RabbitListener(queues = MQConfig.WALLET_TRANSFER_QUEUE)
    public void consumeWalletTransferEvent(WalletTransferEvent event) {
        int count = transferCounter.incrementAndGet();
        log.info("========================================");
        log.info("[MQ] RECEIVED WALLET TRANSFER NOTIFICATION - Total: {}", count);
        log.info("From: {}", event.getFromUsername());
        log.info("To: {}", event.getToUsername());
        log.info("Amount: {} {}", event.getAmount(), event.getCurrency());
        log.info("Time: {}", event.getTimestamp());
        log.info("========================================");
    }
}
