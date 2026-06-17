package com.example.reservepay.common.exception;

public class DuplicateReservationException extends ReservePayException {

    public DuplicateReservationException(long productId, long memberId) {
        super("이미 예약한 상품입니다. productId=" + productId + ", memberId=" + memberId);
    }
}
