package com.example.reservepay.domain.bookingDead;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 재고 확보(예약) 단계에서 Redis/DB 일시 장애로 재시도까지 모두 소진한 영구 실패 기록.
 * 실제 매진(정상 비즈니스 결과)은 여기 남기지 않는다 — 재시도해도 해결될 수 없는 "기술적 실패"만 기록한다.
 */
@Entity
@Table(name = "booking_dead_letter")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BookingDeadLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 36)
    private String orderNo;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false, length = 255)
    private String reason;

    @Column(nullable = false)
    private Integer attempts;

    public static BookingDeadLetter of(String orderNo, long productId, long memberId, String reason, int attempts) {
        BookingDeadLetter deadLetter = new BookingDeadLetter();
        deadLetter.orderNo = orderNo;
        deadLetter.productId = productId;
        deadLetter.memberId = memberId;
        deadLetter.reason = reason;
        deadLetter.attempts = attempts;
        return deadLetter;
    }
}
