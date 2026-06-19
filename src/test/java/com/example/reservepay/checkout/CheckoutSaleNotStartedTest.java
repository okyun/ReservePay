package com.example.reservepay.checkout;

import com.example.reservepay.common.exception.checkout.SaleNotStartedException;
import com.example.reservepay.domain.product.Product;
import com.example.reservepay.domain.product.ProductRepository;
import com.example.reservepay.domain.stock.StockRepository;
import com.example.reservepay.redis.ProductCatalogCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Product#isSaleOpen()} 이 false일 때 Checkout이 판매 미시작 예외를 던지고 재고에 손대지 않는지 검증한다.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CheckoutSaleNotStartedTest {

    private static final long PRODUCT_ID = 1L;
    private static final long MEMBER_ID = 1L;
    private static final int TOTAL_STOCK = 10;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0")) // Testcontainers MySQL
            .withDatabaseName("reservepay")
            .withUsername("reservepay")
            .withPassword("reservepay")
            .withInitScript("db/test-schema.sql"); // 테스트용 스키마·시드

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl); // Spring Boot → Testcontainers DB
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ProductCatalogCache productCatalogCache;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetState() {
        productCatalogCache.evict(PRODUCT_ID); // Redis 캐시 초기화
        setOpeningAt(LocalDateTime.of(2020, 1, 1, 0, 0)); // 기본: 이미 오픈된 상태
        jdbcTemplate.update("UPDATE stock SET remaining_stock = ? WHERE product_id = ?", TOTAL_STOCK, PRODUCT_ID);
        redisTemplate.opsForValue().set("stock:" + PRODUCT_ID, String.valueOf(TOTAL_STOCK));
        redisTemplate.delete("reserved:" + PRODUCT_ID);
        jdbcTemplate.update("DELETE FROM orders WHERE product_id = ?", PRODUCT_ID); // 이전 주문 제거
    }

    @Test
    void 판매_미오픈_상품_Checkout_시_판매_시간이_아니라고_예외를_던진다() {
        LocalDateTime futureOpen = LocalDateTime.now().plusDays(1); // 아직 오픈 전
        Product product = setOpeningAt(futureOpen);

        assertThat(product.isSaleOpen()).isFalse();

        assertThatThrownBy(() -> checkoutService.checkout(PRODUCT_ID, MEMBER_ID)) // requireOpen()에서 거절
                .isInstanceOf(SaleNotStartedException.class)
                .satisfies(ex -> {
                    SaleNotStartedException saleNotStarted = (SaleNotStartedException) ex;
                    assertThat(saleNotStarted.getMessage()).isEqualTo(SaleNotStartedException.MESSAGE);
                    assertThat(saleNotStarted.getCheckinOpeningAt()).isEqualToIgnoringNanos(futureOpen);
                });
    }

    @Test
    void 판매_미오픈_상품_Checkout_시_Redis와_DB_재고를_건드리지_않는다() {
        Product product = setOpeningAt(LocalDateTime.of(2099, 1, 1, 0, 0));
        assertThat(product.isSaleOpen()).isFalse();

        assertThatThrownBy(() -> checkoutService.checkout(PRODUCT_ID, MEMBER_ID))
                .isInstanceOf(SaleNotStartedException.class);

        assertThat(redisTemplate.opsForValue().get("stock:" + PRODUCT_ID)).isEqualTo("10"); // Lua 미실행
        assertThat(redisTemplate.opsForSet().size("reserved:" + PRODUCT_ID)).isZero();
        assertThat(stockRepository.findById(PRODUCT_ID).orElseThrow().getRemainingStock()).isEqualTo(TOTAL_STOCK);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE product_id = ?", Integer.class, PRODUCT_ID)).isZero(); // 주문 미생성
    }

    private Product setOpeningAt(LocalDateTime openingAt) {
        Product product = productRepository.findById(PRODUCT_ID).orElseThrow();
        product.updateCheckinOpeningAt(openingAt);
        productRepository.save(product);
        productCatalogCache.evict(PRODUCT_ID); // DB 변경 반영을 위해 캐시 무효화
        return product;
    }
}
