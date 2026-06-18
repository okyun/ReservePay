package com.example.reservepay.checkout.dto;

import com.example.reservepay.domain.order.Order;
import com.example.reservepay.domain.order.OrderStatus;

public record CheckoutResponse(String orderNo, OrderStatus status, long totalAmount, boolean success, String message) {

    public static CheckoutResponse from(Order order) {
        return new CheckoutResponse(order.getOrderNo(), order.getStatus(), order.getTotalAmount(), true, null);
    }

    public static CheckoutResponse failure(String message) {
        return new CheckoutResponse(null, null, 0, false, message);
    }
}
