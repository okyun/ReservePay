package com.example.reservepay.domain.payment.strategy;

import com.example.reservepay.common.exception.ReservePayException;
import com.example.reservepay.domain.payment.status.PaymentMethod;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * [Strategy] 결제 수단({@link PaymentMethod})에 맞는 {@link PaymentStrategy} 구현체를 찾아주는 조회기.
 * <p>
 * {@link com.example.reservepay.booking.BookingService}는 카드/포인트/Y페이를 if-else로 분기하지 않고
 * {@code resolve(method)} → {@code strategy.pay()} / {@code strategy.cancel()} 만 호출한다.
 * <p>
 * Spring이 등록한 전략 빈({@link CreditCardPaymentStrategy}, {@link YpayPaymentStrategy},
 * {@link YpointPaymentStrategy})을 앱 기동 시 {@link PaymentMethod} 기준 Map으로 수집한다.
 */
@Component
public class PaymentStrategyResolver {

    private final Map<PaymentMethod, PaymentStrategy> strategies;

    /**
     * 컨테이너에 등록된 모든 {@link PaymentStrategy} 빈을 주입받아 수단별 Map을 구성한다.
     * 각 구현체의 {@link PaymentStrategy#getMethod()}가 Map의 키가 된다.
     */
    public PaymentStrategyResolver(List<PaymentStrategy> strategies) {
        this.strategies = strategies.stream()
                .collect(Collectors.toMap(PaymentStrategy::getMethod, Function.identity()));
    }

    /**
     * 결제 수단에 대응하는 전략을 반환한다.
     *
     * @param method 결제 라인의 수단 (CREDIT_CARD / YPAY / YPOINT)
     * @return 해당 수단 전략 (pay·cancel 실행)
     * @throws ReservePayException 지원하지 않는 수단이거나 전략 빈이 없을 때 ({@code UNSUPPORTED_PAYMENT_METHOD})
     */
    public PaymentStrategy resolve(PaymentMethod method) {
        PaymentStrategy strategy = strategies.get(method);
        if (strategy == null) {
            throw ReservePayException.unsupportedPaymentMethod(method);
        }
        return strategy;
    }
}
