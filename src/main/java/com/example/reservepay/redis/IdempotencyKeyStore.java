package com.example.reservepay.redis;

import com.example.reservepay.common.exception.DuplicateRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class IdempotencyKeyStore {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;

    public void reserve(String idempotencyKey) {
        boolean acquired = Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(key(idempotencyKey), "1", TTL));
        if (!acquired) {
            throw new DuplicateRequestException(idempotencyKey);
        }
    }

    public void release(String idempotencyKey) {
        redisTemplate.delete(key(idempotencyKey));
    }

    private String key(String idempotencyKey) {
        return "idempotency:" + idempotencyKey;
    }
}
