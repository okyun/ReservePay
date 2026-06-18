package com.example.reservepay.redis;

import com.example.reservepay.domain.bookingDead.BookingDeadLetter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 예약(재고 확보) Dead Letter를 실시간 로그처럼 보기 위한 Redis Stream 기록.
 * 진본·영구 기록은 MySQL {@code booking_dead_letter} 테이블
 */
@Slf4j
@Component
public class BookingDeadLetterPublisher {

    private static final String DLT_STREAM = "dlt:booking";

    private final StringRedisTemplate redisTemplate;

    public BookingDeadLetterPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void publish(String orderNo, long productId, long memberId, String reason, int attempts) {
        try {
            Map<String, String> body = Map.of(
                    "orderNo", orderNo,
                    "productId", String.valueOf(productId),
                    "memberId", String.valueOf(memberId),
                    "reason", String.valueOf(reason),
                    "attempts", String.valueOf(attempts)
            );
            MapRecord<String, String, String> record = StreamRecords.newRecord()
                    .ofMap(body)
                    .withStreamKey(DLT_STREAM);
            redisTemplate.opsForStream().add(record);
        } catch (Exception e) {
            log.warn("DLT 로그 스트림 기록 실패(DB 기록에는 영향 없음): orderNo={}, productId={}", orderNo, productId, e);
        }
    }
}
