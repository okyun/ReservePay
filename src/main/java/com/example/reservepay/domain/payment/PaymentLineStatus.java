package com.example.reservepay.domain.payment;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum PaymentLineStatus {

    APPROVED,
    CANCELLED;

    private static final Map<PaymentLineStatus, Set<PaymentLineStatus>> ALLOWED = Map.of(
            APPROVED,  EnumSet.of(CANCELLED),
            CANCELLED, EnumSet.noneOf(PaymentLineStatus.class)
    );

    public boolean canTransitionTo(PaymentLineStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}
