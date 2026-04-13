package com.plugpoint.paymentservice.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MQConfig {

    public static final String PAYMENT_QUEUE = "payment_queue";
    public static final String PAYMENT_EXCHANGE = "payment_exchange";
    public static final String PAYMENT_ROUTING_KEY = "payment_routingKey";

    public static final String WALLET_TRANSFER_QUEUE = "wallet_transfer_queue";
    public static final String WALLET_TRANSFER_EXCHANGE = "wallet_transfer_exchange";
    public static final String WALLET_TRANSFER_ROUTING_KEY = "wallet_transfer_routingKey";

    @Bean
    public Queue queue() {
        return new Queue(PAYMENT_QUEUE);
    }

    @Bean
    public TopicExchange exchange() {
        return new TopicExchange(PAYMENT_EXCHANGE);
    }

    @Bean
    public Binding binding(Queue queue, TopicExchange exchange) {
        return BindingBuilder
                .bind(queue)
                .to(exchange)
                .with(PAYMENT_ROUTING_KEY);
    }

    // --- Wallet Transfer MQ Beans ---

    @Bean
    public Queue walletTransferQueue() {
        return new Queue(WALLET_TRANSFER_QUEUE);
    }

    @Bean
    public TopicExchange walletTransferExchange() {
        return new TopicExchange(WALLET_TRANSFER_EXCHANGE);
    }

    @Bean
    public Binding walletTransferBinding() {
        return BindingBuilder
                .bind(walletTransferQueue())
                .to(walletTransferExchange())
                .with(WALLET_TRANSFER_ROUTING_KEY);
    }

    @Bean
    public org.springframework.amqp.support.converter.MessageConverter messageConverter() {
        return new org.springframework.amqp.support.converter.Jackson2JsonMessageConverter();
    }
}
