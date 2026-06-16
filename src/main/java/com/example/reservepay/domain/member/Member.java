package com.example.reservepay.domain.member;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "point_balance", nullable = false)
    private Long pointBalance;

    @Version
    private Long version;

    public static Member of(String name, long pointBalance) {
        Member member = new Member();
        member.name = name;
        member.pointBalance = pointBalance;
        return member;
    }

    public void usePoints(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("사용 포인트는 0보다 커야 합니다.");
        }
        if (pointBalance < amount) {
            throw new IllegalStateException("포인트 잔액이 부족합니다.");
        }
        pointBalance -= amount;
    }

    public void refundPoints(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("환불 포인트는 0보다 커야 합니다.");
        }
        pointBalance += amount;
    }

    public boolean hasEnoughPoints(long amount) {
        return pointBalance >= amount;
    }
}
