package com.example.reservepay.redis;

import com.example.reservepay.common.exception.ReservePayException;
import com.example.reservepay.common.exception.checkout.DuplicateReservationException;
import com.example.reservepay.common.exception.checkout.SoldOutException;
import com.example.reservepay.domain.stock.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
 * 앱 인스턴스가 2대 이상이어도 재고 정합성이 깨지지 않음을 증명한다.
 * <p>
 * 물리적으로 JVM 2개를 띄우지 않고, <strong>두 개의 스레드 풀</strong>이 동일한 Redis·MySQL을
 * 동시에 치는 방식으로 app1/app2를 시뮬레이션한다. Nginx 유무와 무관하게 모든 앱이 같은
 * {@link StockGate}(Redis Lua)와 DB 백스톱을 통과하므로, 이 테스트가 통과하면 분산 확장 시에도
 * 초과판매가 발생하지 않는다.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DistributedStockConsistencyTest {

    private static final long PRODUCT_ID = 1L;
    private static final int TOTAL_STOCK = 10;
    private static final int CONCURRENT_REQUESTS = 1_000;
    private static final int APP1_THREADS = 500;
    private static final int APP2_THREADS = 500;

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

    @Autowired
    private StockGate stockGate;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TransactionTemplate transactionTemplate;

    @Autowired
    void initTransactionTemplate(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager); // DB 백스톱 테스트용
    }

    @BeforeEach
    void resetStockState() {
        redisTemplate.opsForValue().set("stock:" + PRODUCT_ID, String.valueOf(TOTAL_STOCK)); // Redis 재고 10
        redisTemplate.delete("reserved:" + PRODUCT_ID);
        jdbcTemplate.update("UPDATE stock SET remaining_stock = ? WHERE product_id = ?", TOTAL_STOCK, PRODUCT_ID);
        jdbcTemplate.update("DELETE FROM orders WHERE product_id = ?", PRODUCT_ID);
    }

    /**
     * Redis 1차 게이트({@link StockGate#reserve})만 격리해 검증한다.
     * <p>
     * 스레드 풀 2개(각 500)가 동일 Redis에 1000건을 동시에 치는 것으로 app1/app2 분산을
     * 시뮬레이션한다. Lua 원자 차감이 깨지면 10건을 초과해 성공하거나 Redis 키가 어긋난다.
     */
    @Test
    void 앱_2대_시뮬레이션_1000_동시_요청_시_Redis_재고_10개만_차감된다() throws InterruptedException {
        ConcurrencyResult result = runTwoAppPools(this::reserveOnly); // Redis Lua만 (DB 없음)

        assertThat(result.successCount()).isEqualTo(TOTAL_STOCK); // 정확히 10명 당첨
        assertThat(result.soldOutCount()).isEqualTo(CONCURRENT_REQUESTS - TOTAL_STOCK); // 990명 매진
        assertThat(result.errors()).isEmpty();
        assertThat(redisTemplate.opsForValue().get("stock:" + PRODUCT_ID)).isEqualTo("0");
        assertThat(redisTemplate.opsForSet().size("reserved:" + PRODUCT_ID)).isEqualTo(TOTAL_STOCK); // reserved 10명
    }

    /**
     * Checkout 핵심 경로( Redis 선점 → DB {@code decreaseIfAvailable} 백스톱)까지 포함해 검증한다.
     * <p>
     * DB 반영이 0건이면 {@link StockGate#release}로 Redis 슬롯을 되돌리는 실제 Checkout 로직과
     * 동일하다. Redis·DB 어느 쪽에서도 초과판매가 없어야 한다.
     */
    @Test
    void 앱_2대_시뮬레이션_1000_동시_요청_시_Redis와_DB_모두_초과판매_없음() throws InterruptedException {
        ConcurrencyResult result = runTwoAppPools(this::reserveWithDbBackstop); // Redis + DB decreaseIfAvailable

        assertThat(result.successCount()).isEqualTo(TOTAL_STOCK);
        assertThat(result.soldOutCount()).isEqualTo(CONCURRENT_REQUESTS - TOTAL_STOCK);
        assertThat(result.errors()).isEmpty();
        assertThat(redisTemplate.opsForValue().get("stock:" + PRODUCT_ID)).isEqualTo("0");
        assertThat(stockRepository.findById(PRODUCT_ID).orElseThrow().getRemainingStock()).isEqualTo(0); // DB도 0
    }

    /** Redis Lua만 실행 (DB 백스톱 없음) */
    private void reserveOnly(long memberId) {
        stockGate.reserve(PRODUCT_ID, memberId);
    }

    /** CheckoutService.persistWinnerWithRetry()와 동일한 Redis → DB → 실패 시 release 흐름 */
    private void reserveWithDbBackstop(long memberId) {
        stockGate.reserve(PRODUCT_ID, memberId);
        int affected = transactionTemplate.execute(status -> stockRepository.decreaseIfAvailable(PRODUCT_ID));
        if (affected == 0) {
            stockGate.release(PRODUCT_ID, memberId);
            throw ReservePayException.soldOut(PRODUCT_ID);
        }
    }

    /**
     * app1(스레드 0~499) / app2(스레드 500~999) 두 풀이 동일 Redis·MySQL을 공유하며 동시 요청.
     */
    private ConcurrencyResult runTwoAppPools(MemberAction action) throws InterruptedException {
        ExecutorService app1 = Executors.newFixedThreadPool(APP1_THREADS);
        ExecutorService app2 = Executors.newFixedThreadPool(APP2_THREADS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_REQUESTS);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger soldOutCount = new AtomicInteger();
        List<Throwable> errors = new ArrayList<>();

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            long memberId = 100L + i; // 회원마다 고유 ID → DUPLICATE_RESERVATION 방지
            ExecutorService pool = i < APP1_THREADS ? app1 : app2;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    action.run(memberId);
                    successCount.incrementAndGet();
                } catch (ReservePayException e) {
                    // 정상 거절: SOLD_OUT(매진) 또는 DUPLICATE_RESERVATION(1인 1예약) — 그 외는 오류
                    if (e instanceof SoldOutException || e instanceof DuplicateReservationException) {
                        soldOutCount.incrementAndGet();
                    } else {
                        synchronized (errors) {
                            errors.add(e);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    synchronized (errors) {
                        errors.add(t);
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown(); // 1000 스레드 동시 출발
        done.await();
        app1.shutdown();
        app2.shutdown();

        return new ConcurrencyResult(successCount.get(), soldOutCount.get(), errors);
    }

    @FunctionalInterface
    private interface MemberAction {
        void run(long memberId) throws Exception;
    }

    private record ConcurrencyResult(int successCount, int soldOutCount, List<Throwable> errors) {
    }
}
