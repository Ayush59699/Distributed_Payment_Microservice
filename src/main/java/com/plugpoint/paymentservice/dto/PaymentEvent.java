package com.plugpoint.paymentservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private UUID paymentId;
    private String orderId;
    private BigDecimal amount;
    private String currency;
    private String status;
    private String gatewayReference;
}
