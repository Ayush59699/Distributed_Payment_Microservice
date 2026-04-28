package com.plugpoint.paymentservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletTransferEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    private String fromUsername;
    private String toUsername;
    private BigDecimal amount;
    private String currency;
    private LocalDateTime timestamp;
    private String idempotencyKey;
}
