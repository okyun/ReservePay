package com.example.reservepay.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class AuditStreamPublisher {

    private static final String ORDER_STREAM = "events:order";
    private static final String PAYMENT_STREAM = "events:payment";

    private final StringRedisTemplate redisTemplate;

    public AuditStreamPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void orderEvent(String orderNo, String status, Map<String, String> extra) {
        publish(ORDER_STREAM, orderNo, status, extra);
    }

    public void paymentEvent(String orderNo, String status, Map<String, String> extra) {
        publish(PAYMENT_STREAM, orderNo, status, extra);
    }

    private void publish(String stream, String orderNo, String status, Map<String, String> extra) {
        try {
            Map<String, String> body = new java.util.HashMap<>(extra);
            body.put("orderNo", orderNo);
            body.put("status", status);
            MapRecord<String, String, String> record = StreamRecords.newRecord()
                    .ofMap(body)
                    .withStreamKey(stream);
            redisTemplate.opsForStream().add(record);
        } catch (Exception e) {
            log.warn("감사 이벤트 기록 실패 (본 흐름에는 영향 없음): stream={}, orderNo={}", stream, orderNo, e);
        }
    }
}
