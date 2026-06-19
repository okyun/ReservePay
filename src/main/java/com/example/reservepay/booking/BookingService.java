package com.example.reservepay.booking;

import com.example.reservepay.booking.dto.BookingRequest;
import com.example.reservepay.booking.dto.BookingResponse;
import com.example.reservepay.common.exception.booking.PaymentFailedException;
import com.example.reservepay.common.exception.ReservePayException;
import com.example.reservepay.domain.order.Order;
import com.example.reservepay.domain.order.OrderRepository;
import com.example.reservepay.domain.order.OrderStatus;
import com.example.reservepay.domain.payment.Payment;
import com.example.reservepay.domain.paymentDead.PaymentDeadLetter;
import com.example.reservepay.domain.paymentDead.PaymentDeadLetterRepository;
import com.example.reservepay.domain.paymentLine.PaymentLine;
import com.example.reservepay.domain.paymentLine.PaymentLineRepository;
import com.example.reservepay.domain.payment.PaymentRepository;
import com.example.reservepay.domain.payment.strategy.PaymentCombinationValidator;
import com.example.reservepay.domain.payment.strategy.PaymentLineCommand;
import com.example.reservepay.domain.payment.strategy.PaymentLineOutcome;
import com.example.reservepay.domain.payment.strategy.PaymentStrategy;
import com.example.reservepay.domain.payment.strategy.PaymentStrategyResolver;
import com.example.reservepay.domain.pointHistory.PointHistory;
import com.example.reservepay.domain.pointHistory.PointHistoryRepository;
import com.example.reservepay.domain.stock.StockRepository;
import com.example.reservepay.redis.AuditStreamPublisher;
import com.example.reservepay.redis.OrderBookingLock;
import com.example.reservepay.redis.PaymentDeadLetterPublisher;
import com.example.reservepay.redis.StockGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Booking(결제 확정) 오케스트레이션.
 * Checkout으로 생성된 PENDING 주문에 결제 라인을 적용하고, 실패 시 보상 트랜잭션으로 되돌린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final int MAX_TRANSIENT_RETRIES = 2;
    private static final long RETRY_BACKOFF_MS = 50L;

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentLineRepository paymentLineRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final PaymentStrategyResolver strategyResolver;       // [Strategy] 결제 수단 → 전략 매핑
    private final PaymentCombinationValidator combinationValidator; // [Strategy] 복합 결제 조합 검증
    private final OrderBookingLock orderBookingLock;
    private final StockGate stockGate;
    private final StockRepository stockRepository;
    private final AuditStreamPublisher auditStreamPublisher;
    private final PaymentDeadLetterRepository deadLetterRepository;
    private final PaymentDeadLetterPublisher deadLetterPublisher;

    /**
     * 결제 확정 진입점.
     *   Redis 분산 락({@code lock:booking:{orderNo}})으로 동일 주문 동시 결제 차단
     *  주문 조회·PENDING 상태 검증·복합 결제 조합 검증
     *  strategy 으로 라인별 결제 실행
     */
    @Transactional(noRollbackFor = PaymentFailedException.class) // 보상(fail)은 커밋, 402만 클라이언트에 반환
    public BookingResponse book(BookingRequest request) {
        OrderBookingLock.LockHandle lock = orderBookingLock.acquire(request.orderNo()); // 동일 orderNo 동시 결제 차단
        try {
            Order order = orderRepository.findByOrderNoAndMemberId(request.orderNo(), request.memberId())
                    .orElseThrow(() -> ReservePayException.orderNotFound(request.orderNo(), request.memberId()));

            if (order.getStatus() != OrderStatus.PENDING) {
                throw ReservePayException.invalidOrderState(order.getOrderNo(), order.getStatus().name()); // 이미 확정/취소됨
            }

            List<PaymentLineCommand> commands = request.paymentLines().stream()
                    .map(line -> new PaymentLineCommand(line.method(), line.amount(), order.getOrderNo()))
                    .toList();
            combinationValidator.validate(commands, order.getTotalAmount()); // 카드+Y페이 금지·합계 검증

            return executeBooking(order, commands);
        } finally {
            orderBookingLock.release(lock); // 성공/실패 무관하게 락 해제
        }
    }

    /**
     * 결제 라인을 순서대로 실행하고, 전부 성공하면 주문·결제를 확정
     * 한 라인이라도 영구 실패하면 dead letter 기록
     */
    private BookingResponse executeBooking(Order order, List<PaymentLineCommand> commands) {
        Payment payment = Payment.pending(order.getId(), order.getTotalAmount()); // 주문 1:1 결제 헤더
        paymentRepository.save(payment);

        List<PaymentLine> succeededLines = new ArrayList<>(); // 보상 시 역순 취소 대상

        for (PaymentLineCommand command : commands) {
            PaymentStrategy strategy = strategyResolver.resolve(command.method()); // CREDIT_CARD / YPAY / YPOINT
            RetryResult retryResult = payWithRetry(strategy, order.getMemberId(), command); // PG 일시 실패 시 재시도
            PaymentLineOutcome outcome = retryResult.outcome();

            if (!outcome.success()) {
                deadLetterRepository.save(PaymentDeadLetter.of(order.getOrderNo(), order.getMemberId(),
                        command.method(), command.amount(), outcome.message(), retryResult.attempts())); // MySQL DLT
                deadLetterPublisher.publish(order.getOrderNo(), order.getMemberId(), command.method(),
                        command.amount(), outcome.message(), retryResult.attempts()); // Redis Stream DLT
                fail(order, payment, succeededLines, outcome.message()); // 보상 트랜잭션
                throw ReservePayException.paymentFailed(order.getOrderNo(), outcome.message()); // 402 응답
            }

            PaymentLine line = PaymentLine.approved(payment.getId(), command.method(), command.amount(), outcome.pgTxId());
            paymentLineRepository.save(line);
            succeededLines.add(line);

            if (command.method().isPoint()) {
                pointHistoryRepository.save(PointHistory.use(order.getMemberId(), order.getId(), command.amount())); // 포인트 차감 이력
            }
        }

        order.confirm();   // PENDING → CONFIRMED
        payment.approve(); // PENDING → APPROVED

        auditStreamPublisher.paymentEvent(order.getOrderNo(), "APPROVED", Map.of());

        return BookingResponse.success(order.getOrderNo());
    }

    /**
     * 결제 실패 보상 트랜잭션. 이미 성공한 라인을 역순으로 취소하고 재고·주문·결제 상태를 되돌림
     *  성공 라인 역순 + 라인 CANCELLED
     *  Redis 재고 선점 해제 + DB 재고 복구
     *  주문 CANCELLED, 결제 CANCELLED (API 응답 status는 FAILED)
     * 
     */
    private void fail(Order order, Payment payment, List<PaymentLine> succeededLines, String reason) {
        for (PaymentLine line : reversed(succeededLines)) { // 나중에 성공한 라인부터 역순 취소
            PaymentStrategy strategy = strategyResolver.resolve(line.getMethod());
            strategy.cancel(line); // PG 환불 또는 Y포인트 REFUND
            line.cancel();         // APPROVED → CANCELLED
        }

        stockGate.release(order.getProductId(), order.getMemberId()); // Redis reserved 해제
        stockRepository.increase(order.getProductId());               // DB 재고 1건 복구
        order.cancel();   // DB orders → CANCELLED (API 응답은 FAILED)
        payment.cancel(); // DB payment → CANCELLED

        auditStreamPublisher.paymentEvent(order.getOrderNo(), "FAILED", Map.of("reason", String.valueOf(reason)));
    }

    /**
     * 결제 라인 1건 실행 일시 실패(PG 타임아웃 등)는 최대 2회 백오프 재시도
     */
    private RetryResult payWithRetry(PaymentStrategy strategy, long memberId, PaymentLineCommand command) {
        PaymentLineOutcome outcome = strategy.pay(memberId, command);
        int attempts = 1;
        while (!outcome.success() && outcome.retryable() && attempts <= MAX_TRANSIENT_RETRIES) { // PG 타임아웃 등만 재시도
            attempts++;
            sleepQuietly(RETRY_BACKOFF_MS);
            outcome = strategy.pay(memberId, command);
        }
        return new RetryResult(outcome, attempts);
    }

    private record RetryResult(PaymentLineOutcome outcome, int attempts) { // dead letter에 시도 횟수 기록용
    }

    private void sleepQuietly(long millis) { // 재시도 백오프
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 인터럽트 상태 복원
        }
    }

    private <T> List<T> reversed(List<T> list) { // Saga 보상 역순
        List<T> copy = new ArrayList<>(list);
        Collections.reverse(copy);
        return copy;
    }
}
