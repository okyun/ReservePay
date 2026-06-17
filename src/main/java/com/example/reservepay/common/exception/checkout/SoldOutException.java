package com.example.reservepay.common.exception;

public class SoldOutException extends ReservePayException {

    public SoldOutException(long productId) {
        super("상품이 매진되었습니다. productId=" + productId);
    }
}
