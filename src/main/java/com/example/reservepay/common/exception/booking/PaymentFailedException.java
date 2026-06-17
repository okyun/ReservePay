package com.example.reservepay.common.exception.booking;

import com.example.reservepay.booking.dto.BookingResponse;
import com.example.reservepay.common.exception.ReservePayException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class PaymentFailedException extends ReservePayException {

    public static final String DEFAULT_MESSAGE = "결제에 실패했습니다.";

    public PaymentFailedException(String orderNo, String message) {
        super(message, orderNo);
    }

    @Override
    public ResponseEntity<?> toResponseEntity() {
        String message = getMessage() != null ? getMessage() : DEFAULT_MESSAGE;
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(BookingResponse.failure(getOrderNo(), message));
    }
}
