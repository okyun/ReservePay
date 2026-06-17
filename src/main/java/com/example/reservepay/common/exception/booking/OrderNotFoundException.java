package com.example.reservepay.common.exception.booking;

import com.example.reservepay.common.exception.ReservePayException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class OrderNotFoundException extends ReservePayException {

    public OrderNotFoundException(String orderNo, long memberId) {
        super("주문을 찾을 수 없습니다. orderNo=" + orderNo + ", memberId=" + memberId);
    }

    @Override
    public ResponseEntity<?> toResponseEntity() {
        return errorResponse(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
    }
}
