package com.example.reservepay.domain.payment.strategy;

public record PaymentLineOutcome(boolean success, String pgTxId, PaymentFailureType failureType, String message) {

    public static PaymentLineOutcome success(String pgTxId) {
        return new PaymentLineOutcome(true, pgTxId, null, null);
    }

    public static PaymentLineOutcome failure(PaymentFailureType failureType, String message) {
        return new PaymentLineOutcome(false, null, failureType, message);
    }
}
