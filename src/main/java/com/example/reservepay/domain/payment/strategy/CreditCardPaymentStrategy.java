package com.example.reservepay.domain.payment.strategy;

import com.example.reservepay.domain.payment.PaymentLine;
import com.example.reservepay.domain.payment.PaymentMethod;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** [Strategy] 카드 결제 전략 구현체 */
@Component
public class CreditCardPaymentStrategy implements PaymentStrategy {

    @Override
    public PaymentMethod getMethod() {
        return PaymentMethod.CREDIT_CARD;
    }

    @Override
    public PaymentLineOutcome pay(long memberId, PaymentLineCommand command) {
        // Mock PG 연동. 실제 연동 시에도 idempotencyKey를 PG 요청에 함께 전달해
        // 동일 요청 재시도 시 이중 청구를 막는다.
        String pgTxId = "CARD-" + UUID.randomUUID();
        return PaymentLineOutcome.success(pgTxId);
    }

    @Override
    public void cancel(PaymentLine line) {
        // Mock PG 승인 취소
    }
}
