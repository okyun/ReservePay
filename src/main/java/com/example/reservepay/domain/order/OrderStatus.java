package com.example.reservepay.domain.order;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum OrderStatus {

    PENDING,
    CONFIRMED,
    FAILED,
    CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            PENDING,   EnumSet.of(CONFIRMED, FAILED, CANCELLED),
            CONFIRMED, EnumSet.of(CANCELLED),   // 확정 후에도 취소(환불)는 가능
            FAILED,    EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class)
    );

    public boolean canTransitionTo(OrderStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}