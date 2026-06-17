package com.example.reservepay.domain.payment.status;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum PaymentStatus {

    PENDING,
    APPROVED,
    FAILED,
    CANCELLED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.of(
            PENDING,   EnumSet.of(APPROVED, FAILED, CANCELLED),
            APPROVED,  EnumSet.of(CANCELLED),
            FAILED,    EnumSet.noneOf(PaymentStatus.class),
            CANCELLED, EnumSet.noneOf(PaymentStatus.class)
    );

    public boolean canTransitionTo(PaymentStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}
