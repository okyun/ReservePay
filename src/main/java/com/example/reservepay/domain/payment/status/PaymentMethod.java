package com.example.reservepay.domain.payment.status;

public enum PaymentMethod {

    CREDIT_CARD,
    YPAY,
    YPOINT;

    public boolean isPrimary() {
        return this != YPOINT;
    }

    public boolean isPoint() {
        return this == YPOINT;
    }
}
