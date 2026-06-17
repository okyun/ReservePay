package com.example.reservepay.common.exception;

public class OrderNotFoundException extends ReservePayException {

    public OrderNotFoundException(String orderNo, long memberId) {
        super("주문을 찾을 수 없습니다. orderNo=" + orderNo + ", memberId=" + memberId);
    }
}
