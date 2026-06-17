package com.example.reservepay.common.exception.checkout;

import com.example.reservepay.common.exception.ReservePayException;
import com.example.reservepay.web.ErrorResponse;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;

/**
 * {@code checkin_opening_at} 이전에 Checkout을 시도한 경우.
 * 재고(Redis/DB)는 건드리지 않고 즉시 거절한다.
 */
@Getter
public class SaleNotStartedException extends ReservePayException {

    public static final String CODE = "SALE_NOT_STARTED";
    public static final String MESSAGE = "아직 판매 시간이 아닙니다.";

    private final LocalDateTime checkinOpeningAt;

    public SaleNotStartedException(LocalDateTime checkinOpeningAt) {
        super(MESSAGE);
        this.checkinOpeningAt = checkinOpeningAt;
    }

    @Override
    public ResponseEntity<?> toResponseEntity() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse(CODE, MESSAGE));
    }
}
