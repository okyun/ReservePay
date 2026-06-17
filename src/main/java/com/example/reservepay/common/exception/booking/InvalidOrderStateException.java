package com.example.reservepay.common.exception.booking;

import com.example.reservepay.common.exception.ReservePayException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class InvalidOrderStateException extends ReservePayException {

    public InvalidOrderStateException(String orderNo, String currentStatus) {
        super("결제할 수 없는 주문 상태입니다. orderNo=" + orderNo + ", status=" + currentStatus);
    }

    @Override
    public ResponseEntity<?> toResponseEntity() {
        return errorResponse(HttpStatus.CONFLICT, "INVALID_ORDER_STATE");
    }
}
