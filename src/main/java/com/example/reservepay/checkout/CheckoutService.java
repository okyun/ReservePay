package com.example.reservepay.checkout;

import com.example.reservepay.checkout.dto.CheckoutResponse;
import com.example.reservepay.common.exception.ReservePayException;
import com.example.reservepay.domain.bookingDead.BookingDeadLetter;
import com.example.reservepay.domain.bookingDead.BookingDeadLetterRepository;
import com.example.reservepay.domain.order.Order;
import com.example.reservepay.domain.order.OrderRepository;
import com.example.reservepay.domain.stock.StockRepository;
import com.example.reservepay.redis.AuditStreamPublisher;
import com.example.reservepay.redis.BookingDeadLetterPublisher;
import com.example.reservepay.redis.ProductCatalogCache;
import com.example.reservepay.redis.ProductCatalogCache.ProductMeta;
import com.example.reservepay.redis.StockGate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * 체크아웃(당첨·주문 생성) 오케스트레이션.
 * Redis에서 1차 필터링한 뒤, 당첨자만 DB에 재고 차감·PENDING 주문을 기록한다.
 */
@Slf4j
@Service
public class CheckoutService {

    private static final String FAILURE_MESSAGE = "예약에 실패하셨습니다.";
    private static final int MAX_RETRIES = 5;
    private static final long RETRY_BACKOFF_MS = 50L;

    private final OrderRepository orderRepository;
    private final StockRepository stockRepository;
    private final ProductCatalogCache productCatalogCache;
    private final StockGate stockGate;
    private final CheckoutDbGate checkoutDbGate;
    private final AuditStreamPublisher auditStreamPublisher;
    private final BookingDeadLetterRepository deadLetterRepository;
    private final BookingDeadLetterPublisher deadLetterPublisher;
    private final TransactionTemplate transactionTemplate;

    public CheckoutService(OrderRepository orderRepository,
                            StockRepository stockRepository,
                            ProductCatalogCache productCatalogCache,
                            StockGate stockGate,
                            CheckoutDbGate checkoutDbGate,
                            AuditStreamPublisher auditStreamPublisher,
                            BookingDeadLetterRepository deadLetterRepository,
                            BookingDeadLetterPublisher deadLetterPublisher,
                            PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.stockRepository = stockRepository;
        this.productCatalogCache = productCatalogCache;
        this.stockGate = stockGate;
        this.checkoutDbGate = checkoutDbGate;
        this.auditStreamPublisher = auditStreamPublisher;
        this.deadLetterRepository = deadLetterRepository;
        this.deadLetterPublisher = deadLetterPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 체크아웃 진입점.
     *   Redis 캐시로 판매 오픈 여부 확인 (DB 없음)
     *   Redis Lua로 재고·1인1예약 원자 판정 — 매진/중복이면 여기서 즉시 종료
     *   당첨자만  CheckoutDbGate를 통해 DB 백스톱 + PENDING 주문 저장
     */
    public CheckoutResponse checkout(long productId, long memberId) {
        productCatalogCache.requireOpen(productId); // 오픈 전이면 403 SALE_NOT_STARTED (DB·Redis 재고 미접촉)
        stockGate.reserve(productId, memberId);     // Lua: 재고 차감 + 1인1예약 (매진/중복은 여기서 종료)

        String orderNo = UUID.randomUUID().toString();
        ProductMeta product = productCatalogCache.resolve(productId); // 가격 조회 (Redis 캐시)
        return checkoutDbGate.execute(() -> persistWinnerWithRetry(orderNo, productId, memberId, product)); // Semaphore(10)로 DB 동시성 제한
    }

    /**
     * Redis 당첨 후 DB 백스톱: 재고 차감 → PENDING 주문 저장.
     * 이 시점에서 Redis Lua는 이미 슬롯을 선점한 상태이므로, DB 일시 장애 시에는
     * StockGate를 호출하지 않고 DB 단계만 재시도한다
     *   일시적 DB 오류 → 최초 1회 Redis Stream DLT 발행 후, 최대 5회 백오프 재시도
     *   재시도 소진(총 6번 시도) → Redis 해제, {giveUp}으로 {booking_dead_letter} 기록
     *
     */
    private CheckoutResponse persistWinnerWithRetry(String orderNo,
                                                     long productId,
                                                     long memberId,
                                                     ProductMeta product) {
        boolean loggedOnce = false; // Redis Stream(dlt:booking)은 최초 실패 1회만 실시간 로그
        int attempts = 0;

        while (true) {
            attempts++;
            try {
                // DB 최종 방어선: remaining_stock > 0 일 때만 1건 차감 (별도 트랜잭션)
                int affected = transactionTemplate.execute(
                        status -> stockRepository.decreaseIfAvailable(productId));
                if (affected == 0) {
                    // Lua는 통과했으나 DB 잔여 0 → 진짜 매진. 재시도해도 결과 동일하므로 즉시 종료
                    stockGate.release(productId, memberId);
                    throw ReservePayException.soldOut(productId);
                }

                // 재고 차감 성공 → PENDING 주문 저장 (멱등 키·감사 로그는 createOrder에서 처리)
                return createOrder(orderNo, productId, memberId, product.price());
            } catch (ReservePayException e) {
                throw e; // 매진 등 비즈니스 예외는 재시도하지 않음
            } catch (DataAccessException | TransactionException e) {
                // 락 타임아웃·연결 끊김 등 일시 장애 — Redis 선점은 유지한 채 DB만 재시도
                if (!loggedOnce) {
                    deadLetterPublisher.publish(orderNo, productId, memberId, e.getMessage(), attempts); // Redis Stream 1회만
                    loggedOnce = true;
                }
                if (attempts <= MAX_RETRIES) {
                    sleepQuietly(RETRY_BACKOFF_MS); // 50ms 백오프 후 재시도
                    continue;
                }
                // 5회 재시도까지 모두 실패 → 슬롯 반환 후 MySQL dead letter에 영구 기록
                stockGate.release(productId, memberId);
                return giveUp(orderNo, productId, memberId, e.getMessage(), attempts);
            }
        }
    }

    /**
     * PENDING 주문을 저장하고 감사 스트림에 기록한다.
     * 멱등 키({memberId}) 중복 시 재고를 복구한 뒤 예외를 다시 던진다.
     */
    private CheckoutResponse createOrder(String orderNo, long productId, long memberId, long price) {
        try {
            String idempotencyKey = "checkout:" + productId + ":" + memberId; // 1인1예약 DB 백스톱
            Order order = Order.pending(orderNo, memberId, productId, price, idempotencyKey);
            orderRepository.save(order);

            auditStreamPublisher.orderEvent(orderNo, "PENDING",
                    Map.of("productId", String.valueOf(productId), "memberId", String.valueOf(memberId)));

            return CheckoutResponse.from(order);
        } catch (DataIntegrityViolationException e) {
            stockGate.release(productId, memberId); // 멱등 키 중복 → Redis 선점 해제
            transactionTemplate.executeWithoutResult(status -> stockRepository.increase(productId)); // DB 재고 복구
            throw ReservePayException.duplicateReservation(productId, memberId); // 409
        }
    }

    /**
     * DB 재시도를 모두 포기했을 때 호출한다.
     * {booking_dead_letter}에 사유·시도 횟수를 남기고 클라이언트에는 실패 응답을 반환한다.
     */
    private CheckoutResponse giveUp(String orderNo, long productId, long memberId, String reason, int attempts) {
        try {
            deadLetterRepository.save(BookingDeadLetter.of(orderNo, productId, memberId, reason, attempts)); // MySQL 영구 기록
        } catch (Exception e) {
            log.error("BookingDeadLetter DB 기록 실패: orderNo={}, productId={}", orderNo, productId, e);
        }
        return CheckoutResponse.failure(FAILURE_MESSAGE); // 200 + success:false
    }

    private void sleepQuietly(long millis) { // DB 일시 장애 재시도 백오프
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 인터럽트 상태 복원
        }
    }
}
