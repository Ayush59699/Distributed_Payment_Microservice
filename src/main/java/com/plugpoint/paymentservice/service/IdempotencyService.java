package com.plugpoint.paymentservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final String IDEMPOTENCY_KEY_PREFIX = "_P_I_:";

    /**
     * Checks if the key exists. If not, sets it with an 'IN_PROGRESS' status.
     * 
     * @param key The unique idempotency key
     * @return true if the key was successfully set (first time), false if it
     *         already exists.
     */
    public boolean lock(String key) {
        String redisKey = IDEMPOTENCY_KEY_PREFIX + key;
        // setIfAbsent is atomic: SET NX
        Boolean success = redisTemplate.opsForValue().setIfAbsent(redisKey, "IN_PROGRESS", Duration.ofHours(24));
        return success != null && success;
    }

    public void updateStatus(String key, String status) {
        String redisKey = IDEMPOTENCY_KEY_PREFIX + key;
        redisTemplate.opsForValue().set(redisKey, status, Duration.ofSeconds(60));
    }

    public Object getStatus(String key) {
        String redisKey = IDEMPOTENCY_KEY_PREFIX + key;
        return redisTemplate.opsForValue().get(redisKey);
    }
}
