package com.plugpoint.paymentservice.config;

import com.plugpoint.paymentservice.model.AppUser;
import com.plugpoint.paymentservice.model.Wallet;
import com.plugpoint.paymentservice.repository.UserRepository;
import com.plugpoint.paymentservice.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;

    @Override
    public void run(String... args) {
        log.info("Checking for seed data...");

        if (userRepository.findByUsername("Ayush").isEmpty()) {
            log.info("Seeding Ayush and Shivam...");

            AppUser user_1 = userRepository.save(AppUser.builder()
                    .username("Ayush")
                    .email("ayush@gmail.com")
                    .build());

            AppUser user_2 = userRepository.save(AppUser.builder()
                    .username("Shivam")
                    .email("shivam@gmail.com")
                    .build());

            walletRepository.save(Wallet.builder()
                    .user(user_1)
                    .balance(new BigDecimal("1000.00"))
                    .currency("USD")
                    .build());

            walletRepository.save(Wallet.builder()
                    .user(user_2)
                    .balance(new BigDecimal("1000.00"))
                    .currency("USD")
                    .build());

            log.info("Seed data created successfully.");
        } else {
            log.info("Seed data already exists.");
        }
    }
}
