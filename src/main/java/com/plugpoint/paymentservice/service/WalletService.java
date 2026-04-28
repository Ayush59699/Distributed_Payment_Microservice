package com.plugpoint.paymentservice.service;

import com.plugpoint.paymentservice.model.AppUser;
import com.plugpoint.paymentservice.model.Wallet;
import com.plugpoint.paymentservice.model.WalletTransaction;
import com.plugpoint.paymentservice.repository.UserRepository;
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
    private final UserRepository userRepository;
    private final WalletTransactionRepository transactionRepository;
    // NOTE: PaymentEventProducer removed — WalletService no longer publishes events.
    // Events are published exclusively by WalletController (fire-and-forget),
    // and consumed by PaymentEventConsumer which calls executeTransferOrdered() directly.

    @Transactional(readOnly = true)
    public BigDecimal getBalance(String username) {
        return walletRepository.findByUserUsername(username)
                .map(Wallet::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    public void transfer(String fromUsername, String toUsername, BigDecimal amount) {
        if (fromUsername.equals(toUsername)) return;

        // Ensure Wallets Exist (caller should pre-warm these before publishing the event,
        // but this is kept as a safety net for direct calls in tests.)
        ensureUserExists(fromUsername);
        ensureUserExists(toUsername);

        // Execute Transfer with ORDERED LOCKING to prevent deadlocks
        executeTransferOrdered(fromUsername, toUsername, amount);

        // NOTE: triggerTransferEvent() REMOVED intentionally.
        // The event is published by WalletController BEFORE the consumer executes this method.
        // Re-publishing here would create an infinite loop:
        //   Controller → publishes event → Consumer → executeTransferOrdered ✅
        //   (Old path: Controller → transfer() → executeTransferOrdered + publish → Consumer → executeTransferOrdered again ❌)
    }

    @Transactional
    public void executeTransferOrdered(String fromUsername, String toUsername, BigDecimal amount) {
        // DEADLOCK PREVENTION: Always lock in alphabetical order
        String first = fromUsername.compareTo(toUsername) < 0 ? fromUsername : toUsername;
        String second = first.equals(fromUsername) ? toUsername : fromUsername;

        // Lock first resource, then second
        walletRepository.findByUserUsernameWithLock(first);
        walletRepository.findByUserUsernameWithLock(second);

        // Now fetch them for the actual logic (already locked)
        Wallet sender = walletRepository.findByUserUsername(fromUsername)
                .orElseThrow(() -> new RuntimeException("Sender not found"));
        Wallet receiver = walletRepository.findByUserUsername(toUsername)
                .orElseThrow(() -> new RuntimeException("Receiver not found"));

        if (sender.getBalance().compareTo(amount) < 0) {
            log.warn("[Wallet] Insufficient funds for {}", fromUsername);
            return;
        }

        sender.debit(amount);
        receiver.credit(amount);

        walletRepository.save(sender);
        walletRepository.save(receiver);

        transactionRepository.save(WalletTransaction.builder()
                .fromUsername(fromUsername)
                .toUsername(toUsername)
                .amount(amount)
                .currency("USD")
                .timestamp(LocalDateTime.now())
                .build());
    }

    public synchronized void ensureUserExists(String username) {
        if (userRepository.findByUsername(username).isEmpty()) {
            log.info("[Wallet] Auto-provisioning user: {}", username);
            AppUser user = userRepository.save(AppUser.builder()
                    .username(username)
                    .email(username.toLowerCase() + "@example.com")
                    .build());

            walletRepository.save(Wallet.builder()
                    .user(user)
                    .balance(new BigDecimal("10000.00"))
                    .currency("USD")
                    .build());
        }
    }

}
