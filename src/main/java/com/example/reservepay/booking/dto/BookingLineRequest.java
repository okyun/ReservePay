package com.example.reservepay.booking.dto;

import com.example.reservepay.domain.payment.status.PaymentMethod;

public record BookingLineRequest(PaymentMethod method, long amount) {
}
