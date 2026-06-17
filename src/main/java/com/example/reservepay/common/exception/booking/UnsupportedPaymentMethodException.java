package com.example.reservepay.common.exception.booking;

import com.example.reservepay.common.exception.ReservePayException;
import com.example.reservepay.domain.payment.status.PaymentMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class UnsupportedPaymentMethodException extends ReservePayException {

    public UnsupportedPaymentMethodException(PaymentMethod method) {
        super("지원하지 않는 결제 수단입니다. method=" + method);
    }

    @Override
    public ResponseEntity<?> toResponseEntity() {
        return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_PAYMENT_METHOD");
    }
}
