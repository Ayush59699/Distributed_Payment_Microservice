package com.plugpoint.paymentservice.service;

import com.plugpoint.paymentservice.config.MQConfig;
import com.plugpoint.paymentservice.dto.PaymentEvent;
import com.plugpoint.paymentservice.dto.WalletTransferEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventProducer {

    private final RabbitTemplate rabbitTemplate;

    public void sendPaymentEvent(PaymentEvent event) {
        log.info("Sending payment event to MQ for Order: {}", event.getOrderId());
        rabbitTemplate.convertAndSend(
                MQConfig.PAYMENT_EXCHANGE,
                MQConfig.PAYMENT_ROUTING_KEY,
                event);
        log.info("Payment event sent successfully.");
    }

    public void sendTransferEvent(WalletTransferEvent event) {
        log.info("Sending wallet transfer event to MQ from {} to {}", event.getFromUsername(), event.getToUsername());
        rabbitTemplate.convertAndSend(
                MQConfig.WALLET_TRANSFER_EXCHANGE,
                MQConfig.WALLET_TRANSFER_ROUTING_KEY,
                event);
        log.info("Wallet transfer event sent successfully.");
    }
}
