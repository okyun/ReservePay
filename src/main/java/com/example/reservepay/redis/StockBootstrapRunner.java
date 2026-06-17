package com.example.reservepay.redis;

import com.example.reservepay.domain.stock.Stock;
import com.example.reservepay.domain.stock.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockBootstrapRunner implements ApplicationRunner {

    private final StockRepository stockRepository;
    private final StringRedisTemplate redisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        for (Stock stock : stockRepository.findAll()) {
            String key = "stock:" + stock.getProductId();
            boolean wasAbsent = Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(key, String.valueOf(stock.getRemainingStock())));
            if (wasAbsent) {
                log.info("Redis 재고 키 초기화: {} = {}", key, stock.getRemainingStock());
            }
        }
    }
}
