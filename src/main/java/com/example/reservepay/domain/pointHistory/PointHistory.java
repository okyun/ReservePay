package com.example.reservepay.domain.pointHistory;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "point_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PointHistoryType type;

    public static PointHistory use(long memberId, long orderId, long amount) {
        return record(memberId, orderId, -amount, PointHistoryType.USE);
    }

    public static PointHistory refund(long memberId, long orderId, long amount) {
        return record(memberId, orderId, amount, PointHistoryType.REFUND);
    }

    public static PointHistory earn(long memberId, Long orderId, long amount) {
        return record(memberId, orderId, amount, PointHistoryType.EARN);
    }

    private static PointHistory record(long memberId, Long orderId, long amount, PointHistoryType type) {
        PointHistory history = new PointHistory();
        history.memberId = memberId;
        history.orderId = orderId;
        history.amount = amount;
        history.type = type;
        return history;
    }
}
