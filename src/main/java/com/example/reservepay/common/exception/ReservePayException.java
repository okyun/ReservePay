package com.example.reservepay.common.exception;

public abstract class ReservePayException extends RuntimeException {

    protected ReservePayException(String message) {
        super(message);
    }
}
