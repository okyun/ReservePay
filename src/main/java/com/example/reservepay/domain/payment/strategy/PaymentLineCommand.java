package com.example.reservepay.domain.payment.strategy;

import com.example.reservepay.domain.payment.status.PaymentMethod;

public record PaymentLineCommand(PaymentMethod method, long amount, String orderNo) {
}
