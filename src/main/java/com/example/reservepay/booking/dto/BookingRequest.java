package com.example.reservepay.booking.dto;

import java.util.List;

public record BookingRequest(String orderNo, long memberId, List<BookingLineRequest> paymentLines) {
}
