package com.example.reservepay.domain.paymentDead;

import com.example.reservepay.domain.payment.status.PaymentMethod;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [쟁점6] 재시도까지 모두 소진한 영구 실패 결제 라인을 기록하는 Dead Letter.
 * 보상(재고 복구·라인 취소)과는 독립적으로 동작하는 관측용 기록이다.
 */
@Entity
@Table(name = "payment_dead_letter")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentDeadLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 36)
    private String orderNo;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false, length = 255)
    private String reason;

    @Column(nullable = false)
    private Integer attempts;

    public static PaymentDeadLetter of(String orderNo, long memberId, PaymentMethod method,
                                       long amount, String reason, int attempts) {
        PaymentDeadLetter deadLetter = new PaymentDeadLetter();
        deadLetter.orderNo = orderNo;
        deadLetter.memberId = memberId;
        deadLetter.method = method;
        deadLetter.amount = amount;
        deadLetter.reason = reason;
        deadLetter.attempts = attempts;
        return deadLetter;
    }
}
