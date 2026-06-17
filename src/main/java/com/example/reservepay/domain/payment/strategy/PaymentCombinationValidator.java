package com.example.reservepay.domain.payment.strategy;

import com.example.reservepay.common.exception.ReservePayException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * [Strategy] 복합 결제 조합 검증기.
 * "카드·Y페이 혼용 금지" = primary 결제 수단 최대 1개 규칙으로 환원한다.
 */
@Component
public class PaymentCombinationValidator {

    public void validate(List<PaymentLineCommand> commands, long totalAmount) {
        if (commands.isEmpty()) {
            throw ReservePayException.invalidPaymentCombination("결제 라인이 비어 있습니다.");
        }

        long primaryCount = commands.stream()
                .filter(c -> c.method().isPrimary())
                .count();
        if (primaryCount > 1) {
            throw ReservePayException.invalidPaymentCombination("카드와 Y-pay는 동시에 사용할 수 없습니다.");
        }

        boolean hasInvalidAmount = commands.stream().anyMatch(c -> c.amount() <= 0);
        if (hasInvalidAmount) {
            throw ReservePayException.invalidPaymentCombination("결제 라인 금액은 0보다 커야 합니다.");
        }

        long sum = commands.stream().mapToLong(PaymentLineCommand::amount).sum();
        if (sum != totalAmount) {
            throw ReservePayException.invalidPaymentCombination(
                    "결제 라인 합계가 주문 금액과 일치하지 않습니다. sum=" + sum + ", totalAmount=" + totalAmount);
        }
    }
}
