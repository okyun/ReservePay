package com.example.reservepay.common.exception.checkout;

import com.example.reservepay.checkout.dto.CheckoutResponse;
import com.example.reservepay.common.exception.ReservePayException;
import org.springframework.http.ResponseEntity;

public class SoldOutException extends ReservePayException {

    public static final String MESSAGE = "판매가 종료되었습니다.";

    public SoldOutException(long productId) {
        super("상품이 매진되었습니다. productId=" + productId);
    }

    @Override
    public ResponseEntity<?> toResponseEntity() {
        return ResponseEntity.ok(CheckoutResponse.failure(MESSAGE));
    }
}
