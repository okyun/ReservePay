package com.example.reservepay.redis;

import com.example.reservepay.common.exception.ReservePayException;
import com.example.reservepay.domain.product.Product;
import com.example.reservepay.domain.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Checkout 핫 패스용 상품 메타 캐시. DB 조회 없이 오픈 시각·가격을 Redis에서 읽는다.
 * 키가 없으면 DB에서 1회 로드 후 캐시한다.
 */
@Component
@RequiredArgsConstructor
public class ProductCatalogCache {

    private final ProductRepository productRepository;
    private final StringRedisTemplate redisTemplate;

    public ProductMeta requireOpen(long productId) {
        ProductMeta meta = resolve(productId);
        if (!meta.saleOpen()) {
            throw ReservePayException.saleNotStarted(meta.checkinOpeningAt());
        }
        return meta;
    }

    public ProductMeta resolve(long productId) {
        String openingRaw = redisTemplate.opsForValue().get(openingKey(productId));
        String priceRaw = redisTemplate.opsForValue().get(priceKey(productId));
        if (openingRaw != null && priceRaw != null) {
            return new ProductMeta(productId, LocalDateTime.parse(openingRaw), Long.parseLong(priceRaw));
        }

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> ReservePayException.productNotFound(productId));
        cache(product);
        return new ProductMeta(product.getId(), product.getCheckinOpeningAt(), product.getPrice());
    }

    public void cache(Product product) {
        redisTemplate.opsForValue().set(openingKey(product.getId()), product.getCheckinOpeningAt().toString());
        redisTemplate.opsForValue().set(priceKey(product.getId()), String.valueOf(product.getPrice()));
    }

    public void evict(long productId) {
        redisTemplate.delete(openingKey(productId));
        redisTemplate.delete(priceKey(productId));
    }

    private String openingKey(long productId) {
        return "product:" + productId + ":opening_at";
    }

    private String priceKey(long productId) {
        return "product:" + productId + ":price";
    }

    public record ProductMeta(long productId, LocalDateTime checkinOpeningAt, long price) {

        public boolean saleOpen() {
            return Product.isSaleOpenAt(checkinOpeningAt);
        }
    }
}
