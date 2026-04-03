package com.plugpoint.paymentservice.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class PaymentResponse {
    private UUID paymentId;
    private String orderId;
    private String status;
    private String gatewayReference;
    private String message;
}
