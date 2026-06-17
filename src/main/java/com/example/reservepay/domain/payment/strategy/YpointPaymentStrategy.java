package com.example.reservepay.domain.payment.strategy;

import com.example.reservepay.common.exception.ReservePayException;
import com.example.reservepay.domain.member.Member;
import com.example.reservepay.domain.member.MemberRepository;
import com.example.reservepay.domain.order.Order;
import com.example.reservepay.domain.order.OrderRepository;
import com.example.reservepay.domain.payment.Payment;
import com.example.reservepay.domain.paymentLine.PaymentLine;
import com.example.reservepay.domain.payment.status.PaymentMethod;
import com.example.reservepay.domain.payment.PaymentRepository;
import com.example.reservepay.domain.pointHistory.PointHistory;
import com.example.reservepay.domain.pointHistory.PointHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** [Strategy] Y포인트 결제 전략 구현체 */
@Component
@RequiredArgsConstructor
public class YpointPaymentStrategy implements PaymentStrategy {

    private final MemberRepository memberRepository;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PointHistoryRepository pointHistoryRepository;

    @Override
    public PaymentMethod getMethod() {
        return PaymentMethod.YPOINT;
    }

    @Override
    public PaymentLineOutcome pay(long memberId, PaymentLineCommand command) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> ReservePayException.memberNotFound(memberId));

        if (!member.hasEnoughPoints(command.amount())) {
            return PaymentLineOutcome.failure("포인트 잔액이 부족합니다.");
        }

        member.usePoints(command.amount());
        return PaymentLineOutcome.successWithoutPg();
    }

    @Override
    public void cancel(PaymentLine line) {
        Order order = resolveOrder(line);
        Member member = memberRepository.findById(order.getMemberId())
                .orElseThrow(() -> new IllegalStateException("회원을 찾을 수 없습니다. memberId=" + order.getMemberId()));

        member.refundPoints(line.getAmount());
        pointHistoryRepository.save(PointHistory.refund(order.getMemberId(), order.getId(), line.getAmount()));
    }

    private Order resolveOrder(PaymentLine line) {
        Payment payment = paymentRepository.findById(line.getPaymentId())
                .orElseThrow(() -> new IllegalStateException("결제를 찾을 수 없습니다. paymentId=" + line.getPaymentId()));
        return orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new IllegalStateException("주문을 찾을 수 없습니다. orderId=" + payment.getOrderId()));
    }
}
