package com.example.reservepay.domain.payment.strategy;

import com.example.reservepay.domain.payment.PaymentMethod;

public record PaymentLineCommand(PaymentMethod method, long amount, String idempotencyKey) {
}
