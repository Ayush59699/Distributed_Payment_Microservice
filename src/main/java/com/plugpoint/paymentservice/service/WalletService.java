package com.plugpoint.paymentservice.service;

import com.plugpoint.paymentservice.dto.WalletTransferEvent;
import com.plugpoint.paymentservice.model.Wallet;
import com.plugpoint.paymentservice.model.WalletTransaction;
import com.plugpoint.paymentservice.repository.WalletRepository;
import com.plugpoint.paymentservice.repository.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class WalletService {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository transactionRepository;
    private final PaymentEventProducer paymentEventProducer;

    @Transactional(readOnly = true)
    public BigDecimal getBalance(String username) {
        return walletRepository.findByUserUsername(username)
                .map(Wallet::getBalance)
                .orElseThrow(() -> new RuntimeException("Wallet not found for user: " + username));
    }

    @Transactional
    public void transfer(String fromUsername, String toUsername, BigDecimal amount) {
        log.info("Initiating transfer of {} from {} to {}", amount, fromUsername, toUsername);

        if (fromUsername.equals(toUsername)) {
            throw new RuntimeException("Cannot transfer money to the same account.");
        }

        Wallet senderWallet = walletRepository.findByUserUsername(fromUsername)
                .orElseThrow(() -> new RuntimeException("Sender wallet not found: " + fromUsername));

        Wallet receiverWallet = walletRepository.findByUserUsername(toUsername)
                .orElseThrow(() -> new RuntimeException("Receiver wallet not found: " + toUsername));

        if (senderWallet.getBalance().compareTo(amount) < 0) {
            throw new RuntimeException("Insufficient funds in sender's wallet.");
        }

        // Atomically update balances
        senderWallet.debit(amount);
        receiverWallet.credit(amount);

        walletRepository.save(senderWallet);
        walletRepository.save(receiverWallet);

        // Save Transaction History record
        transactionRepository.save(WalletTransaction.builder()
                .fromUsername(fromUsername)
                .toUsername(toUsername)
                .amount(amount)
                .currency("USD")
                .timestamp(LocalDateTime.now())
                .build());

        // Send RabbitMQ event
        try {
            paymentEventProducer.sendTransferEvent(WalletTransferEvent.builder()
                    .fromUsername(fromUsername)
                    .toUsername(toUsername)
                    .amount(amount)
                    .currency("USD")
                    .timestamp(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.error("Failed to send wallet transfer MQ event: {}", e.getMessage());
        }

        log.info("Transfer successful.");
    }
}
