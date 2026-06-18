package com.example.reservepay.redis;

import com.example.reservepay.domain.payment.status.PaymentMethod;
import com.example.reservepay.domain.paymentDead.PaymentDeadLetter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 재시도(최대 2회)까지 모두 실패한 결제 라인을 실시간 로그처럼 보기 위한 Redis Stream 기록.
 * 영구 보존·SQL 조회가 필요한 진본 기록은 MySQL {@code payment_dead_letter} 테이블
 */
@Slf4j
@Component
public class PaymentDeadLetterPublisher {

    private static final String DLT_STREAM = "dlt:payment";

    private final StringRedisTemplate redisTemplate;

    public PaymentDeadLetterPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void publish(String orderNo, long memberId, PaymentMethod method, long amount, String reason, int attempts) {
        try {
            Map<String, String> body = Map.of(
                    "orderNo", orderNo,
                    "memberId", String.valueOf(memberId),
                    "method", method.name(),
                    "amount", String.valueOf(amount),
                    "reason", String.valueOf(reason),
                    "attempts", String.valueOf(attempts)
            );
            MapRecord<String, String, String> record = StreamRecords.newRecord()
                    .ofMap(body)
                    .withStreamKey(DLT_STREAM);
            redisTemplate.opsForStream().add(record);
        } catch (Exception e) {
            log.warn("DLT 로그 스트림 기록 실패(DB 기록에는 영향 없음): orderNo={}, method={}", orderNo, method, e);
        }
    }
}
