package com.example.reservepay.redis;

import com.example.reservepay.common.exception.ReservePayException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class StockGate {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> stockDecrScript;

    public void reserve(long productId, long memberId) {
        Long result = redisTemplate.execute(
                stockDecrScript,
                List.of(stockKey(productId), reservedKey(productId)),
                String.valueOf(memberId)
        );

        if (result == null) {
            throw ReservePayException.productNotFound(productId);
        }
        if (result == -3) {
            throw ReservePayException.duplicateReservation(productId, memberId);
        }
        if (result == -2) {
            throw ReservePayException.productNotFound(productId);
        }
        if (result == -1) {
            throw ReservePayException.soldOut(productId);
        }
    }

    public void release(long productId, long memberId) {
        redisTemplate.opsForSet().remove(reservedKey(productId), String.valueOf(memberId));
        redisTemplate.opsForValue().increment(stockKey(productId));
    }

    private String stockKey(long productId) {
        return "stock:" + productId;
    }

    private String reservedKey(long productId) {
        return "reserved:" + productId;
    }
}
