package com.example.reservepay.domain.payment.strategy;

import com.example.reservepay.domain.member.Member;
import com.example.reservepay.domain.member.MemberRepository;
import com.example.reservepay.domain.order.Order;
import com.example.reservepay.domain.order.OrderRepository;
import com.example.reservepay.domain.payment.Payment;
import com.example.reservepay.domain.payment.PaymentLine;
import com.example.reservepay.domain.payment.PaymentMethod;
import com.example.reservepay.domain.payment.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** [Strategy] Y포인트 결제 전략 구현체 */
@Component
@RequiredArgsConstructor
public class YpointPaymentStrategy implements PaymentStrategy {

    private final MemberRepository memberRepository;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;

    @Override
    public PaymentMethod getMethod() {
        return PaymentMethod.YPOINT;
    }

    @Override
    public PaymentLineOutcome pay(long memberId, PaymentLineCommand command) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalStateException("회원을 찾을 수 없습니다. memberId=" + memberId));

        if (!member.hasEnoughPoints(command.amount())) {
            return PaymentLineOutcome.failure(PaymentFailureType.PERMANENT, "포인트 잔액이 부족합니다.");
        }

        member.usePoints(command.amount());
        return PaymentLineOutcome.success(null);
    }

    @Override
    public void cancel(PaymentLine line) {
        long memberId = resolveMemberId(line);
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalStateException("회원을 찾을 수 없습니다. memberId=" + memberId));
        member.refundPoints(line.getAmount());
    }

    private long resolveMemberId(PaymentLine line) {
        Payment payment = paymentRepository.findById(line.getPaymentId())
                .orElseThrow(() -> new IllegalStateException("결제를 찾을 수 없습니다. paymentId=" + line.getPaymentId()));
        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("주문을 찾을 수 없습니다. orderId=" + payment.getOrderId()));
        return order.getMemberId();
    }
}
