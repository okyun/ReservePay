package com.example.reservepay.domain.payment.strategy;

import com.example.reservepay.domain.payment.PaymentLine;
import com.example.reservepay.domain.payment.PaymentMethod;

/**
 * [Strategy] 결제 수단별 실행 전략 인터페이스.
 * BookingService는 if/else 분기 없이 이 인터페이스에만 의존한다.
 */
public interface PaymentStrategy {

    PaymentMethod getMethod();

    PaymentLineOutcome pay(long memberId, PaymentLineCommand command);

    void cancel(PaymentLine line);
}
