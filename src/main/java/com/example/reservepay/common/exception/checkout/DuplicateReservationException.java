package com.example.reservepay.common.exception.checkout;

import com.example.reservepay.common.exception.ReservePayException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class DuplicateReservationException extends ReservePayException {

    public DuplicateReservationException(long productId, long memberId) {
        super("이미 예약한 상품입니다. productId=" + productId + ", memberId=" + memberId);
    }

    @Override
    public ResponseEntity<?> toResponseEntity() {
        return errorResponse(HttpStatus.CONFLICT, "DUPLICATE_RESERVATION");
    }
}
