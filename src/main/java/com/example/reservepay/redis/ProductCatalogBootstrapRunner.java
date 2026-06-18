package com.example.reservepay.redis;

import com.example.reservepay.domain.product.Product;
import com.example.reservepay.domain.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 앱 기동 시 DB 상품 메타를 Redis에 미리 적재한다.
 *
 *  오픈 시각·가격을 DB 없이 읽어야 하므로,
 * 기동 직후 { product:{id}:opening_at}, {product:{id}:price} 키를 채운다.
 * 캐시 miss 시  DB 1회 조회 후 보완한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductCatalogBootstrapRunner implements ApplicationRunner {

    private final ProductRepository productRepository;
    private final ProductCatalogCache productCatalogCache;

    /** Spring Boot 기동 완료 후 1회 실행 — 전 상품을 Redis 상품 캐시에 반영 */
    @Override
    public void run(ApplicationArguments args) {
        for (Product product : productRepository.findAll()) {
            productCatalogCache.cache(product);
            log.info("Redis 상품 캐시 초기화: productId={}, openingAt={}",
                    product.getId(), product.getCheckinOpeningAt());
        }
    }
}
