package com.example.reservepay.common.exception;

public class ProductNotFoundException extends ReservePayException {

    public ProductNotFoundException(long productId) {
        super("상품을 찾을 수 없습니다. productId=" + productId);
    }
}
