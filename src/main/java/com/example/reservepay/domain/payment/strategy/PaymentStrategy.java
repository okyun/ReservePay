package com.example.reservepay.domain.payment.strategy;

import com.example.reservepay.domain.paymentLine.PaymentLine;
import com.example.reservepay.domain.payment.status.PaymentMethod;

/**
 * [Strategy] 결제 수단별 실행 전략 인터페이스.
 * <p>
 * {@link com.example.reservepay.booking.BookingService}는 카드/포인트/Y페이를 if/else로 분기하지 않고
 * {@link PaymentStrategyResolver#resolve(PaymentMethod)}로 구현체를 고른 뒤 이 인터페이스만 호출한다.
 * <p>
 * 구현체: {@link CreditCardPaymentStrategy}, {@link YpayPaymentStrategy}, {@link YpointPaymentStrategy}
 */
public interface PaymentStrategy {

    /**
     * 이 전략이 담당하는 결제 수단.
     * {@link PaymentStrategyResolver}가 앱 기동 시 {@link PaymentMethod} → 전략 Map을 만들 때 키로 사용한다.
     */
    PaymentMethod getMethod();

    /**
     * 결제 라인 1건을 실행한다.
     *
     * @param memberId 결제 주체 회원 ID
     * @param command  수단·금액·주문번호({@link PaymentLineCommand})
     * @return 성공 시 {@link PaymentLineOutcome#success(String)} 또는 {@link PaymentLineOutcome#successWithoutPg()},
     *         실패 시 {@link PaymentLineOutcome#failure(String)} / {@link PaymentLineOutcome#transientFailure(String)}
     */
    PaymentLineOutcome pay(long memberId, PaymentLineCommand command);

    /**
     * 결제 실패 보상 시 이미 승인된 라인을 되돌린다.
     * <p>
     * Y포인트는 포인트 환불·이력 저장, 카드/Y페이는 PG 승인 취소(현재 Mock은 no-op).
     * {@link com.example.reservepay.booking.BookingService}가 성공한 라인을 역순으로 호출한다.
     *
     * @param line 승인됐던 결제 라인 ({@link PaymentLine})
     */
    void cancel(PaymentLine line);
}
