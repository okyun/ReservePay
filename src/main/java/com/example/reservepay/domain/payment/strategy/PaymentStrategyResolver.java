package com.example.reservepay.domain.payment.strategy;

import com.example.reservepay.domain.payment.PaymentMethod;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * [Strategy] Spring 빈으로 등록된 전략 구현체를 PaymentMethod 기준 Map으로 수집·조회한다.
 */
@Component
public class PaymentStrategyResolver {

    private final Map<PaymentMethod, PaymentStrategy> strategies;

    public PaymentStrategyResolver(List<PaymentStrategy> strategies) {
        this.strategies = strategies.stream()
                .collect(Collectors.toMap(PaymentStrategy::getMethod, Function.identity()));
    }

    public PaymentStrategy resolve(PaymentMethod method) {
        PaymentStrategy strategy = strategies.get(method); // [Strategy] 런타임 전략 선택
        if (strategy == null) {
            throw new IllegalArgumentException("지원하지 않는 결제 수단입니다. method=" + method);
        }
        return strategy;
    }
}
