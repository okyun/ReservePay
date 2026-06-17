package com.example.reservepay.common.exception;

public class DuplicateRequestException extends ReservePayException {

    public DuplicateRequestException(String idempotencyKey) {
        super("이미 처리 중이거나 처리된 요청입니다. idempotencyKey=" + idempotencyKey);
    }
}
