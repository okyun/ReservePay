package com.example.reservepay.checkout;

import com.example.reservepay.checkout.dto.CheckoutResponse;
import com.example.reservepay.domain.stock.StockRepository;
import com.example.reservepay.redis.ProductCatalogCache;
import com.example.reservepay.web.ErrorResponse;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checkout HTTP API 통합 테스트.
 * {@link TestMethodOrder}로 테스트 순서를 고정한다 — 동시성 테스트 전에 재고·회원 상태가 누적되도록.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConcurrentCheckoutIntegrationTest {

    private static final int PRODUCT_ID = 1;
    private static final int CONCURRENT_REQUESTS = 50;
    private static final int TOTAL_STOCK = 10;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("reservepay")
            .withUsername("reservepay")
            .withPassword("reservepay")
            .withInitScript("db/test-schema.sql");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort
    private int port; // RANDOM_PORT — 실제 HTTP로 /api/checkout 호출

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ProductCatalogCache productCatalogCache;

    @Autowired
    private TestRestTemplate restTemplate;

    /** 오픈 전 HTTP: 403, code=SALE_NOT_STARTED, message="아직 판매 시간이 아닙니다." */
    @Test
    @Order(0)
    void 판매_시작_전에는_403_SALE_NOT_STARTED를_반환한다() {
        // 오픈 시각을 미래로 설정하고 캐시를 비워 ProductCatalogCache가 DB에서 다시 읽게 한다.
        jdbcTemplate.update(
                "UPDATE product SET checkin_opening_at = '2099-01-01 00:00:00' WHERE id = ?", PRODUCT_ID);
        productCatalogCache.evict(PRODUCT_ID);

        ResponseEntity<ErrorResponse> response = restTemplate.getForEntity(
                checkoutUrl(PRODUCT_ID, 63L), ErrorResponse.class);

        // HTTP 403 + SALE_NOT_STARTED, 재고는 Redis·DB 모두 미접촉
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("SALE_NOT_STARTED");
        assertThat(response.getBody().message()).isEqualTo("아직 판매 시간이 아닙니다.");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT remaining_stock FROM stock WHERE product_id = ?", Integer.class, PRODUCT_ID))
                .isEqualTo(TOTAL_STOCK);
        assertThat(redisTemplate.opsForValue().get("stock:" + PRODUCT_ID)).isEqualTo("10");

        // 이후 테스트를 위해 판매 오픈 상태로 복구
        jdbcTemplate.update(
                "UPDATE product SET checkin_opening_at = '2020-01-01 00:00:00' WHERE id = ?", PRODUCT_ID);
        productCatalogCache.evict(PRODUCT_ID);
    }

    /** 존재하지 않는 상품: HTTP 404, code=PRODUCT_NOT_FOUND */
    @Test
    @Order(1)
    void 존재하지_않는_상품은_404를_반환한다() {
        // 캐시·DB 모두에 없는 productId
        ResponseEntity<ErrorResponse> response = restTemplate.getForEntity(
                checkoutUrl(PRODUCT_ID + 9_999, 1L), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("PRODUCT_NOT_FOUND");
    }

    /** 1인 1예약 위반: HTTP 409, code=DUPLICATE_RESERVATION */
    @Test
    @Order(2)
    void 동일_회원의_중복_예약은_409를_반환한다() {
        long memberId = 1L;
        // 첫 Checkout 성공 → Redis reserved 세트에 회원 등록
        ResponseEntity<CheckoutResponse> first = restTemplate.getForEntity(
                checkoutUrl(PRODUCT_ID, memberId), CheckoutResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).isNotNull();
        assertThat(first.getBody().success()).isTrue();

        // 동일 회원 재요청 → StockGate Lua가 DUPLICATE_RESERVATION(-3) 반환
        ResponseEntity<ErrorResponse> second = restTemplate.getForEntity(
                checkoutUrl(PRODUCT_ID, memberId), ErrorResponse.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().code()).isEqualTo("DUPLICATE_RESERVATION");
    }

    /** 재고 10개에 50명 동시 Checkout — Redis Lua + DB 백스톱으로 정확히 9명만 성공 (Order(2)에서 1건 선점) */
    @Test
    @Order(3)
    void 재고_10개에_50명이_동시에_요청해도_정확히_10명만_성공한다() throws InterruptedException {
        // 50개 스레드가 동시에 출발하도록 ready/start 래치 구성
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_REQUESTS);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger soldOutCount = new AtomicInteger();
        List<HttpStatusCode> unexpected = new ArrayList<>();

        for (int i = 1; i <= CONCURRENT_REQUESTS; i++) {
            long memberId = i + 3; // 1~3은 schema.sql 기본 시드, 4~53을 동시성 테스트용으로 사용
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    ResponseEntity<CheckoutResponse> response = restTemplate.getForEntity(
                            checkoutUrl(PRODUCT_ID, memberId), CheckoutResponse.class);

                    if (response.getStatusCode() != HttpStatus.OK) {
                        synchronized (unexpected) {
                            unexpected.add(response.getStatusCode());
                        }
                    } else if (response.getBody() != null && response.getBody().success()) {
                        successCount.incrementAndGet();
                    } else {
                        // 매진: HTTP 200 + success=false, message="판매가 종료되었습니다."
                        soldOutCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown(); // 모든 스레드 동시 시작
        doneLatch.await();
        executor.shutdown();

        assertThat(unexpected).isEmpty(); // 4xx/5xx 없어야 함
        assertThat(successCount.get()).isEqualTo(TOTAL_STOCK - 1); // @Order(2)에서 memberId=1이 1건 선점
        assertThat(soldOutCount.get()).isEqualTo(CONCURRENT_REQUESTS - (TOTAL_STOCK - 1)); // 나머지는 매진
        assertThat(stockRepository.findById((long) PRODUCT_ID).orElseThrow().getRemainingStock()).isEqualTo(0); // DB 재고 0
    }

    /** 매진 HTTP: 200 + success=false, message="판매가 종료되었습니다." */
    @Test
    @Order(4)
    void 매진된_상품은_200_실패_본문을_반환한다() {
        // Order(3)까지 재고 소진된 상태에서 memberId=3(아직 예약 안 한 시드 회원) 요청
        ResponseEntity<CheckoutResponse> response = restTemplate.getForEntity(
                checkoutUrl(PRODUCT_ID, 3L), CheckoutResponse.class);

        // 매진은 HTTP 200 + success=false (4xx가 아님)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().message()).isEqualTo("판매가 종료되었습니다.");
    }

    private String checkoutUrl(long productId, long memberId) {
        return "http://localhost:" + port + "/api/checkout?productId=" + productId + "&memberId=" + memberId;
    }
}
