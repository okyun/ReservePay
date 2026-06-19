package com.example.reservepay.booking;

import com.example.reservepay.booking.dto.BookingLineRequest;
import com.example.reservepay.booking.dto.BookingRequest;
import com.example.reservepay.checkout.CheckoutService;
import com.example.reservepay.checkout.dto.CheckoutResponse;
import com.example.reservepay.common.exception.booking.PaymentFailedException;
import com.example.reservepay.domain.member.Member;
import com.example.reservepay.domain.member.MemberRepository;
import com.example.reservepay.domain.order.Order;
import com.example.reservepay.domain.order.OrderRepository;
import com.example.reservepay.domain.order.OrderStatus;
import com.example.reservepay.domain.payment.Payment;
import com.example.reservepay.domain.paymentLine.PaymentLine;
import com.example.reservepay.domain.paymentLine.PaymentLineRepository;
import com.example.reservepay.domain.paymentLine.status.PaymentLineStatus;
import com.example.reservepay.domain.payment.status.PaymentMethod;
import com.example.reservepay.domain.payment.PaymentRepository;
import com.example.reservepay.domain.payment.status.PaymentStatus;
import com.example.reservepay.domain.payment.strategy.PaymentLineCommand;
import com.example.reservepay.domain.payment.strategy.PaymentLineOutcome;
import com.example.reservepay.domain.payment.strategy.PaymentStrategy;
import com.example.reservepay.domain.payment.strategy.PaymentStrategyResolver;
import com.example.reservepay.domain.payment.strategy.YpayPaymentStrategy;
import com.example.reservepay.domain.payment.strategy.YpointPaymentStrategy;
import com.example.reservepay.domain.pointHistory.PointHistory;
import com.example.reservepay.domain.pointHistory.PointHistoryRepository;
import com.example.reservepay.domain.pointHistory.status.PointHistoryType;
import com.example.reservepay.domain.product.Product;
import com.example.reservepay.domain.product.ProductRepository;
import com.example.reservepay.domain.stock.StockRepository;
import com.example.reservepay.redis.ProductCatalogCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 결제 실패 시 {@link BookingService} 보상 트랜잭션(포인트 환불·라인 취소·재고 복구·주문/결제 취소)을 검증한다.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    @Import(BookingCompensationIntegrationTest.FailingCreditCardConfig.class) // CREDIT_CARD만 항상 실패하는 Mock 전략
class BookingCompensationIntegrationTest {

    private static final long PRODUCT_ID = 1L;
    private static final long MEMBER_ID = 1L;
    private static final long ORDER_AMOUNT = 100_000L;
    private static final long POINT_USE_AMOUNT = 50_000L;
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

    @Autowired
    private BookingService bookingService;

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentLineRepository paymentLineRepository;

    @Autowired
    private PointHistoryRepository pointHistoryRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductCatalogCache productCatalogCache;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        // 판매 오픈 상태로 맞추고 상품 캐시를 비운다.
        productCatalogCache.evict(PRODUCT_ID);
        Product product = productRepository.findById(PRODUCT_ID).orElseThrow();
        product.updateCheckinOpeningAt(LocalDateTime.of(2020, 1, 1, 0, 0));
        productRepository.save(product);

        // DB·Redis 재고를 초기값으로 되돌린다.
        jdbcTemplate.update("UPDATE stock SET remaining_stock = ? WHERE product_id = ?", TOTAL_STOCK, PRODUCT_ID);
        redisTemplate.opsForValue().set("stock:" + PRODUCT_ID, String.valueOf(TOTAL_STOCK));
        redisTemplate.delete("reserved:" + PRODUCT_ID);

        // 이전 테스트의 주문·결제·포인트 이력을 지우고 회원 포인트를 5만으로 맞춘다.
        clearMemberBookingState(MEMBER_ID);
        resetMemberPoints(MEMBER_ID, POINT_USE_AMOUNT);
    }

    /**
     * 복합결제_두번째_결제_실패_시_포인트_재고_주문을_보상한다
     *
     * 시나리오: Y포인트(5만) 성공 → 신용카드(5만) 실패
     * 보상 검증:
     * - 성공했던 Y포인트 라인 strategy.cancel() → 포인트 환불·REFUND 이력
     * - 결제 라인·주문·결제 → CANCELLED
     * - stockGate.release() + DB 재고 increase → Redis·DB 재고 원복
     */
    @Test
    void 복합결제_두번째_결제_실패_시_포인트_재고_주문을_보상한다() {
        // 1) Checkout으로 PENDING 주문 생성 + DB 재고 1 차감 + Redis 예약 확보
        CheckoutResponse checkout = checkoutService.checkout(PRODUCT_ID, MEMBER_ID);
        assertThat(checkout.success()).isTrue();

        int stockAfterCheckout = stockRepository.findById(PRODUCT_ID).orElseThrow().getRemainingStock();
        assertThat(stockAfterCheckout).isEqualTo(TOTAL_STOCK - 1);
        assertThat(redisTemplate.opsForSet().isMember("reserved:" + PRODUCT_ID, String.valueOf(MEMBER_ID))).isTrue();

        // 2) Y포인트 5만 + 신용카드 5만 복합 결제 요청 (카드는 FailingCreditCardConfig로 항상 실패)
        BookingRequest request = new BookingRequest(
                checkout.orderNo(),
                MEMBER_ID,
                List.of(
                        new BookingLineRequest(PaymentMethod.YPOINT, POINT_USE_AMOUNT),
                        new BookingLineRequest(PaymentMethod.CREDIT_CARD, ORDER_AMOUNT - POINT_USE_AMOUNT)
                )
        );

        // 3) 두 번째 라인 실패 → PaymentFailedException. noRollbackFor로 보상 트랜잭션은 DB에 커밋됨
        assertThatThrownBy(() -> bookingService.book(request))
                .isInstanceOf(PaymentFailedException.class)
                .hasMessage("카드 승인 거절");

        // 4) 주문·결제 헤더가 CANCELLED로 전이됐는지 확인
        Order order = orderRepository.findByOrderNoAndMemberId(checkout.orderNo(), MEMBER_ID).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);

        // 5) 성공했던 Y포인트 라인 1건만 존재하고, line.cancel()으로 CANCELLED 처리됐는지 확인
        List<PaymentLine> lines = paymentLineRepository.findByPaymentId(payment.getId());
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().getMethod()).isEqualTo(PaymentMethod.YPOINT);
        assertThat(lines.getFirst().getStatus()).isEqualTo(PaymentLineStatus.CANCELLED);

        // 6) strategy.cancel() → 포인트 환불. 차감 전 잔액(5만)으로 복구됐는지 확인
        Member member = memberRepository.findById(MEMBER_ID).orElseThrow();
        assertThat(member.getPointBalance()).isEqualTo(POINT_USE_AMOUNT);

        // 7) 결제 시 USE 이력 + 보상 시 REFUND 이력이 모두 남았는지 확인
        List<PointHistory> pointHistories = pointHistoryRepository.findAll().stream()
                .filter(history -> MEMBER_ID == history.getMemberId())
                .toList();
        assertThat(pointHistories).extracting(PointHistory::getType)
                .containsExactlyInAnyOrder(PointHistoryType.USE, PointHistoryType.REFUND);

        // 8) stockGate.release() + stockRepository.increase() → DB·Redis 재고·예약 원복
        assertThat(stockRepository.findById(PRODUCT_ID).orElseThrow().getRemainingStock()).isEqualTo(TOTAL_STOCK);
        assertThat(redisTemplate.opsForValue().get("stock:" + PRODUCT_ID)).isEqualTo(String.valueOf(TOTAL_STOCK));
        assertThat(redisTemplate.opsForSet().isMember("reserved:" + PRODUCT_ID, String.valueOf(MEMBER_ID))).isFalse();
    }

    /**
     * 첫결제_실패_시_성공한_라인_없이_재고와_주문을_보상한다
     *
     * 시나리오: Y포인트 단독 결제, 잔액 부족으로 첫 라인부터 실패
     * 보상 검증:
     * - succeededLines 없음 → strategy.cancel() 호출 없음, 포인트·이력 변동 없음
     * - 주문·결제 → CANCELLED, 결제 라인 미생성
     * - stockGate.release() + DB 재고 increase → Redis·DB 재고 원복
     */
    @Test
    void 첫결제_실패_시_성공한_라인_없이_재고와_주문을_보상한다() {
        // 1) Checkout으로 PENDING 주문 생성 (보상 대상 재고·예약 상태 확보)
        CheckoutResponse checkout = checkoutService.checkout(PRODUCT_ID, MEMBER_ID);
        assertThat(checkout.success()).isTrue();

        // 2) Y포인트 단독 10만 원 결제 — 회원 잔액 5만으로 첫 라인부터 실패
        BookingRequest request = new BookingRequest(
                checkout.orderNo(),
                MEMBER_ID,
                List.of(new BookingLineRequest(PaymentMethod.YPOINT, ORDER_AMOUNT))
        );

        // 3) 결제 실패 예외 발생. succeededLines가 비어 있어 strategy.cancel()은 호출되지 않음
        assertThatThrownBy(() -> bookingService.book(request))
                .isInstanceOf(PaymentFailedException.class)
                .hasMessage("포인트 잔액이 부족합니다.");

        // 4) 주문·결제만 CANCELLED. 승인된 결제 라인은 생성되지 않음
        Order order = orderRepository.findByOrderNoAndMemberId(checkout.orderNo(), MEMBER_ID).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(paymentLineRepository.findByPaymentId(payment.getId())).isEmpty();

        // 5) 포인트 차감·USE/REFUND 이력 없음 — 보상 대상 라인이 없었기 때문
        Member member = memberRepository.findById(MEMBER_ID).orElseThrow();
        assertThat(member.getPointBalance()).isEqualTo(POINT_USE_AMOUNT);

        assertThat(pointHistoryRepository.findAll().stream()
                .anyMatch(history -> history.getMemberId().equals(MEMBER_ID))).isFalse();

        // 6) 재고·Redis 예약만 복구 (fail()의 stockGate.release + increase)
        assertThat(stockRepository.findById(PRODUCT_ID).orElseThrow().getRemainingStock()).isEqualTo(TOTAL_STOCK);
        assertThat(redisTemplate.opsForSet().isMember("reserved:" + PRODUCT_ID, String.valueOf(MEMBER_ID))).isFalse();
    }

    private void clearMemberBookingState(long memberId) {
        // FK 순서: payment_line → payment → orders
        jdbcTemplate.update("""
                DELETE pl FROM payment_line pl
                INNER JOIN payment p ON pl.payment_id = p.id
                INNER JOIN orders o ON p.order_id = o.id
                WHERE o.member_id = ?
                """, memberId);
        jdbcTemplate.update("""
                DELETE p FROM payment p
                INNER JOIN orders o ON p.order_id = o.id
                WHERE o.member_id = ?
                """, memberId);
        jdbcTemplate.update("DELETE FROM payment_dead_letter WHERE member_id = ?", memberId);
        jdbcTemplate.update("DELETE FROM point_history WHERE member_id = ?", memberId);
        jdbcTemplate.update("DELETE FROM orders WHERE member_id = ?", memberId);
    }

    private void resetMemberPoints(long memberId, long pointBalance) {
        jdbcTemplate.update("UPDATE member SET point_balance = ? WHERE id = ?", pointBalance, memberId);
    }

    @TestConfiguration
    static class FailingCreditCardConfig {

        // 실 PG 없이 두 번째 결제 실패를 재현하기 위해 CREDIT_CARD 전략만 실패하도록 교체
        @Bean
        @Primary
        PaymentStrategyResolver paymentStrategyResolver(YpointPaymentStrategy ypoint,
                                                        YpayPaymentStrategy ypay) {
            PaymentStrategy failingCard = new PaymentStrategy() {
                @Override
                public PaymentMethod getMethod() {
                    return PaymentMethod.CREDIT_CARD;
                }

                // 카드 승인 거절 시뮬레이션
                @Override
                public PaymentLineOutcome pay(long memberId, PaymentLineCommand command) {
                    return PaymentLineOutcome.failure("카드 승인 거절");
                }

                // Mock PG 환불 no-op (이 테스트에서는 카드 라인이 승인되지 않으므로 호출되지 않음)
                @Override
                public void cancel(PaymentLine line) {
                }
            };
            return new PaymentStrategyResolver(List.of(ypoint, ypay, failingCard));
        }
    }
}
