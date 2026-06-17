package com.example.reservepay.common.exception.checkout;

import com.example.reservepay.common.exception.ReservePayException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class ProductNotFoundException extends ReservePayException {

    public ProductNotFoundException(long productId) {
        super("상품을 찾을 수 없습니다. productId=" + productId);
    }

    @Override
    public ResponseEntity<?> toResponseEntity() {
        return errorResponse(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
    }
}
