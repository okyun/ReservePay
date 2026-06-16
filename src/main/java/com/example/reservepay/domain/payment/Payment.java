package com.example.reservepay.domain.payment;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_order", columnNames = "order_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    public static Payment pending(long orderId, long totalAmount) {
        if (totalAmount < 0) {
            throw new IllegalArgumentException("결제 금액은 0 이상이어야 합니다.");
        }

        Payment payment = new Payment();
        payment.orderId = orderId;
        payment.status = PaymentStatus.PENDING;
        payment.totalAmount = totalAmount;
        return payment;
    }

    public void approve() {
        transitionTo(PaymentStatus.APPROVED);
    }

    public void fail() {
        transitionTo(PaymentStatus.FAILED);
    }

    public void cancel() {
        transitionTo(PaymentStatus.CANCELLED);
    }

    private void transitionTo(PaymentStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "결제 상태를 " + status + "에서 " + next + "(으)로 변경할 수 없습니다.");
        }
        this.status = next;
    }
}
