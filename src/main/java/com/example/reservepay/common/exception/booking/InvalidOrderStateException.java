package com.example.reservepay.common.exception;

public class InvalidOrderStateException extends ReservePayException {

    public InvalidOrderStateException(String orderNo, String currentStatus) {
        super("결제할 수 없는 주문 상태입니다. orderNo=" + orderNo + ", status=" + currentStatus);
    }
}
