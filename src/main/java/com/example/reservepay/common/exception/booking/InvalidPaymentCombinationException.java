package com.example.reservepay.common.exception.booking;

import com.example.reservepay.common.exception.ReservePayException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class InvalidPaymentCombinationException extends ReservePayException {

    public InvalidPaymentCombinationException(String message) {
        super(message);
    }

    @Override
    public ResponseEntity<?> toResponseEntity() {
        return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PAYMENT_COMBINATION");
    }
}
