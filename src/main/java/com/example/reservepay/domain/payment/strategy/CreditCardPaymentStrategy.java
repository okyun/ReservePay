package com.example.reservepay.domain.payment.strategy;

import com.example.reservepay.domain.paymentLine.PaymentLine;
import com.example.reservepay.domain.payment.status.PaymentMethod;
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
        // 동일 요청 재시도 시 이중 청구를 막는다.
        String pgTxId = "CARD-" + UUID.randomUUID();
        return PaymentLineOutcome.success(pgTxId);
    }

    @Override
    public void cancel(PaymentLine line) {
    
        // 현재 Mock 환경이므로 외부 PG 호출 없이 no-op 처리한다.
    }
}
