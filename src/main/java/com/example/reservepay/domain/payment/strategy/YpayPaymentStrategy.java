package com.example.reservepay.domain.payment.strategy;

import com.example.reservepay.domain.paymentLine.PaymentLine;
import com.example.reservepay.domain.payment.status.PaymentMethod;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** [Strategy] Y페이 결제 전략 구현체 */
@Component
public class YpayPaymentStrategy implements PaymentStrategy {

    private static final Log log = LogFactory.getLog(YpayPaymentStrategy.class);

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
        // 실 PG 연동 시: Y-pay 승인 취소(환불) API 호출 — pgTxId(line.getPgTxId()) 기준으로 전액/부분 환불.
        // 현재 Mock 환경이므로 외부 PG 호출 없이 no-op 처리한다.
        log.info(" Y-pay 승인 취소(환불)");
    }
}
