package com.example.reservepay.booking.dto;

import com.example.reservepay.domain.order.OrderStatus;

public record BookingResponse(String orderNo, OrderStatus status, boolean success, String message) {

    public static BookingResponse success(String orderNo) {
        return new BookingResponse(orderNo, OrderStatus.CONFIRMED, true, null);
    }

    public static BookingResponse failure(String orderNo, String message) {
        return new BookingResponse(orderNo, OrderStatus.FAILED, false, message);
    }
}
