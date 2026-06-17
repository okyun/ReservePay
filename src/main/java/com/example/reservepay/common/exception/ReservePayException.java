package com.example.reservepay.common.exception;

import com.example.reservepay.common.exception.booking.DuplicateRequestException;
import com.example.reservepay.common.exception.booking.InvalidOrderStateException;
import com.example.reservepay.common.exception.booking.InvalidPaymentCombinationException;
import com.example.reservepay.common.exception.booking.MemberNotFoundException;
import com.example.reservepay.common.exception.booking.OrderNotFoundException;
import com.example.reservepay.common.exception.booking.PaymentFailedException;
import com.example.reservepay.common.exception.booking.UnsupportedPaymentMethodException;
import com.example.reservepay.common.exception.checkout.DuplicateReservationException;
import com.example.reservepay.common.exception.checkout.ProductNotFoundException;
import com.example.reservepay.common.exception.checkout.SaleNotStartedException;
import com.example.reservepay.common.exception.checkout.SoldOutException;
import com.example.reservepay.domain.payment.status.PaymentMethod;
import com.example.reservepay.web.ErrorResponse;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;

@Getter
public class ReservePayException extends RuntimeException {

    private final String orderNo;

    protected ReservePayException(String message) {
        this(message, null);
    }

    protected ReservePayException(String message, String orderNo) {
        super(message);
        this.orderNo = orderNo;
    }

    public ResponseEntity<?> toResponseEntity() {
        return errorResponse(HttpStatus.BAD_REQUEST, "RESERVEPAY_ERROR");
    }

    protected ResponseEntity<ErrorResponse> errorResponse(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, getMessage()));
    }

    public static ReservePayException soldOut(long productId) {
        return new SoldOutException(productId);
    }

    public static ReservePayException productNotFound(long productId) {
        return new ProductNotFoundException(productId);
    }

    public static ReservePayException duplicateReservation(long productId, long memberId) {
        return new DuplicateReservationException(productId, memberId);
    }

    public static ReservePayException saleNotStarted(LocalDateTime checkinOpeningAt) {
        return new SaleNotStartedException(checkinOpeningAt);
    }

    public static ReservePayException orderNotFound(String orderNo, long memberId) {
        return new OrderNotFoundException(orderNo, memberId);
    }

    public static ReservePayException duplicateRequest(String orderNo) {
        return new DuplicateRequestException(orderNo);
    }

    public static ReservePayException invalidOrderState(String orderNo, String currentStatus) {
        return new InvalidOrderStateException(orderNo, currentStatus);
    }

    public static ReservePayException invalidPaymentCombination(String message) {
        return new InvalidPaymentCombinationException(message);
    }

    public static ReservePayException paymentFailed(String orderNo, String message) {
        return new PaymentFailedException(orderNo, message);
    }

    public static ReservePayException memberNotFound(long memberId) {
        return new MemberNotFoundException(memberId);
    }

    public static ReservePayException unsupportedPaymentMethod(PaymentMethod method) {
        return new UnsupportedPaymentMethodException(method);
    }

    public static ReservePayException reservePayError(String message) {
        return new ReservePayException(message);
    }
}
