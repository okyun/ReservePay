package com.example.reservepay.domain.payment.strategy;

import com.example.reservepay.booking.dto.BookingResponse;
import com.example.reservepay.common.exception.ReservePayException;
import com.example.reservepay.domain.member.Member;
import com.example.reservepay.domain.member.MemberRepository;
import com.example.reservepay.domain.order.Order;
import com.example.reservepay.domain.order.OrderRepository;
import com.example.reservepay.domain.payment.Payment;
import com.example.reservepay.domain.paymentLine.PaymentLine;
import com.example.reservepay.domain.payment.status.PaymentMethod;
import com.example.reservepay.domain.payment.PaymentRepository;
import com.example.reservepay.domain.pointHistory.PointHistoryRepository;
import com.example.reservepay.domain.pointHistory.status.PointHistoryType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** [Strategy] Y포인트 결제 전략 단위 테스트 */
@ExtendWith(MockitoExtension.class)
class YpointPaymentStrategyTest {

    @Mock
    private MemberRepository memberRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PointHistoryRepository pointHistoryRepository;

    /** 포인트 부족 → Booking 시 HTTP 402, message="포인트 잔액이 부족합니다." */
    @Test
    void 포인트가_부족하면_영구실패를_반환한다() {
        YpointPaymentStrategy strategy = new YpointPaymentStrategy(
                memberRepository, paymentRepository, orderRepository, pointHistoryRepository); // Mockito 주입
        Member member = Member.of("tester", 1_000);
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        // 2,000원 결제 시도 — 잔액 1,000원
        PaymentLineOutcome outcome = strategy.pay(1L, new PaymentLineCommand(PaymentMethod.YPOINT, 2_000, "key"));

        // retryable=false → BookingService가 재시도하지 않고 즉시 fail()
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).isEqualTo("포인트 잔액이 부족합니다.");
        assertThat(outcome.retryable()).isFalse();

        // Booking API 응답 계약: HTTP 402 + BookingResponse.failure
        var response = ReservePayException.paymentFailed("order-1", outcome.message()).toResponseEntity();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        BookingResponse body = (BookingResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isFalse();
        assertThat(body.message()).isEqualTo("포인트 잔액이 부족합니다.");
    }

    @Test
    void 포인트가_충분하면_차감하고_성공을_반환한다() {
        YpointPaymentStrategy strategy = new YpointPaymentStrategy(
                memberRepository, paymentRepository, orderRepository, pointHistoryRepository);
        Member member = Member.of("tester", 5_000);
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        PaymentLineOutcome outcome = strategy.pay(1L, new PaymentLineCommand(PaymentMethod.YPOINT, 2_000, "key"));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.pgTxId()).isNull(); // Y포인트는 PG 승인번호 없음
        assertThat(member.getPointBalance()).isEqualTo(3_000); // 5,000 - 2,000
    }

    @Test
    void cancel_시_포인트를_환불하고_REFUND_이력을_남긴다() {
        YpointPaymentStrategy strategy = new YpointPaymentStrategy(
                memberRepository, paymentRepository, orderRepository, pointHistoryRepository);

        // 결제 후 포인트가 차감된 상태(3,000)를 시뮬레이션
        Member member = Member.of("tester", 3_000);
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));

        Payment payment = Payment.pending(10L, 2_000);
        when(paymentRepository.findById(100L)).thenReturn(Optional.of(payment));

        Order order = Order.pending("order-1", 1L, 1L, 2_000, "checkout:1:1");
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        // 승인됐던 Y포인트 라인 — cancel()이 paymentId로 주문·회원을 역추적
        PaymentLine line = PaymentLine.approved(100L, PaymentMethod.YPOINT, 2_000, null);

        strategy.cancel(line);

        // refundPoints(2,000) → 3,000 + 2,000 = 5,000
        assertThat(member.getPointBalance()).isEqualTo(5_000);
        var saved = org.mockito.ArgumentCaptor.forClass(com.example.reservepay.domain.pointHistory.PointHistory.class);
        org.mockito.Mockito.verify(pointHistoryRepository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(PointHistoryType.REFUND);
        assertThat(saved.getValue().getAmount()).isEqualTo(2_000);
    }
}
