package com.example.reservepay.domain.payment.strategy;

import com.example.reservepay.domain.payment.PaymentLine;
import com.example.reservepay.domain.payment.PaymentMethod;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** [Strategy] Y페이 결제 전략 구현체 */
@Component
public class YpayPaymentStrategy implements PaymentStrategy {

    @Override
    public PaymentMethod getMethod() {
        return PaymentMethod.YPAY;
    }

    @Override
    public PaymentLineOutcome pay(long memberId, PaymentLineCommand command) {
        String pgTxId = "YPAY-" + UUID.randomUUID();
        return PaymentLineOutcome.success(pgTxId);
    }

    @Override
    public void cancel(PaymentLine line) {
        // Mock PG 승인 취소
    }
}
