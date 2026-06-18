package com.example.reservepay.redis;

import com.example.reservepay.common.exception.ReservePayException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 주문 결제(Booking) 동시 실행을 막는 Redis 분산 락.
 * 처리가 끝나면 토큰 검증 후 해제한다 (Idempotency-Key 선점과 달리 unlock 한다).
 */
@Component
@RequiredArgsConstructor
public class OrderBookingLock {

    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> unlockScript;

    public LockHandle acquire(String orderNo) {
        String token = UUID.randomUUID().toString();
        boolean acquired = Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(lockKey(orderNo), token, LOCK_TTL));
        if (!acquired) {
            throw ReservePayException.duplicateRequest(orderNo);
        }
        return new LockHandle(orderNo, token);
    }

    public void release(LockHandle handle) {
        redisTemplate.execute(unlockScript, List.of(lockKey(handle.orderNo())), handle.token());
    }

    private String lockKey(String orderNo) {
        return "lock:booking:" + orderNo;
    }

    public record LockHandle(String orderNo, String token) {
    }
}
